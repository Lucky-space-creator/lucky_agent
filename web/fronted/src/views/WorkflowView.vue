<script setup lang="ts">
/**
 * 工作流页：列表（定义 + 运行记录）与画布编排两种模式。
 *
 * <p><b>数据流约定：</b>编辑期间画布上的 nodes/edges 是唯一事实源，
 * {@link toDef} 在保存时把它们转换为后端 {@code WorkflowDef}；反向由
 * {@link applyDef} 转换。编辑器中不再保留第二份 WorkflowDef 副本，
 * 避免「改了 A 忘了同步 B」这类隐性漂移。两个转换是 graph.ts 中的纯函数，
 * 可脱离画布单测。</p>
 *
 * <p><b>运行期约定：</b>运行态一律经 {@code useWorkflowRun} 获取（ASYNC 触发 +
 * SSE 事件流 + 实例快照对齐），本视图只负责「选中态、面板开关、按钮禁用」，
 * 不自己拼运行数据。画布节点上的运行标记由 WorkflowCanvas 经 provide 下发，
 * 视图不参与（避免整体替换 nodes 数组打断拖拽）。</p>
 */
import { computed, onMounted, ref, watch } from 'vue'
import Icon from '@/components/common/Icon.vue'
import WorkflowCanvas from '@/components/workflow/WorkflowCanvas.vue'
import NodeInspector from '@/components/workflow/NodeInspector.vue'
import WfRunPanel from '@/components/workflow/WfRunPanel.vue'
import { autoLayout, defToGraph, graphToDef } from '@/components/workflow/graph'
import type { WfGraphEdge, WfGraphNode } from '@/components/workflow/graph'
import { fmtMs } from '@/components/workflow/format'
import { useWorkflowRun } from '@/components/workflow/useWorkflowRun'
import { workflowApi } from '@/api'
import type {
  WorkflowDef,
  WorkflowInstance,
  WorkflowInstanceStatus,
  WorkflowNodeType,
  WorkflowNodeTypeMeta,
} from '@/api/types'
import { useToastStore } from '@/stores/toast'

const toast = useToastStore()

const loading = ref(false)
const workflows = ref<WorkflowDef[]>([])
const instances = ref<WorkflowInstance[]>([])
const triggeringId = ref<string | null>(null)
const creating = ref(false)
const newName = ref('')
const newDesc = ref('')
const savingNew = ref(false)

/** 节点类型目录（组件面板 + 属性表单的唯一事实源，来自后端）。 */
const metas = ref<WorkflowNodeTypeMeta[]>([])
const metasError = ref('')

// ---------------- 编辑态 ----------------

const view = ref<'list' | 'edit'>('list')
/** 仅承载身份信息（id/name/desc/trigger/enabled），节点与边在画布数组中。 */
const editing = ref<WorkflowDef | null>(null)
const nodes = ref<WfGraphNode[]>([])
const edges = ref<WfGraphEdge[]>([])
const selectedId = ref<string | null>(null)
const dirty = ref(false)
const saving = ref(false)

// ---------------- 运行变量（触发时写入工作流作用域） ----------------

/** 按工作流 id 记忆在这台机器的浏览器里：是「这台机器上的调试参数」，不是流程定义。 */
const VARS_KEY = (id: string) => `lucky.wf.vars.${id}`
const showVars = ref(false)
const varsText = ref('{}')
const varsErr = ref('')

function loadVars(id?: string) {
  varsErr.value = ''
  if (!id) {
    varsText.value = '{}'
    return
  }
  try {
    varsText.value = localStorage.getItem(VARS_KEY(id)) ?? '{}'
  } catch {
    varsText.value = '{}'
  }
}

/** 解析运行变量；返回 null 表示非法（错误写在 varsErr，由调用方提示）。 */
function parseVars(): Record<string, any> | null {
  const t = varsText.value.trim()
  if (!t) return {}
  try {
    const o = JSON.parse(t)
    if (o === null || typeof o !== 'object' || Array.isArray(o)) {
      varsErr.value = '必须是一个 JSON 对象，例如 {"content": "..."}'
      return null
    }
    varsErr.value = ''
    return o as Record<string, any>
  } catch (e: any) {
    varsErr.value = 'JSON 解析失败：' + (e?.message ?? e)
    return null
  }
}

const statusClass = (s?: WorkflowInstanceStatus) =>
  ({
    RUNNING: 'wf-badge--run',
    COMPLETED: 'wf-badge--ok',
    FAILED: 'wf-badge--err',
    SUSPENDED: 'wf-badge--warn',
  })[s ?? 'RUNNING'] ?? 'wf-badge--run'

const triggerLabel = (t?: WorkflowDef['trigger']) => {
  if (!t || !t.enabled) return '手动'
  if (t.type === 'INTERVAL') return `间隔 ${t.intervalMs ? Math.round(t.intervalMs / 1000) + 's' : '—'}`
  if (t.type === 'WEBHOOK') return 'Webhook'
  return '手动'
}

async function load() {
  loading.value = true
  try {
    const [wfs, insts] = await Promise.all([workflowApi.list(), workflowApi.instances()])
    workflows.value = wfs ?? []
    instances.value = (insts ?? []).slice(0, 20)
  } catch (e: any) {
    toast.error('加载工作流失败：' + (e?.message ?? e))
  } finally {
    loading.value = false
  }
}

/** 节点类型目录加载失败必须显式暴露：静默失败会让画布变成空面板，用户无从判断原因。 */
async function loadMetas() {
  try {
    metas.value = (await workflowApi.nodeTypes()) ?? []
    metasError.value = metas.value.length ? '' : '后端未返回任何节点类型'
  } catch (e: any) {
    metasError.value = '节点类型加载失败：' + (e?.message ?? e)
  }
}

async function toggle(wf: WorkflowDef) {
  try {
    const updated = await workflowApi.setEnabled(wf.id, !wf.enabled)
    wf.enabled = updated.enabled
    toast.success(updated.enabled ? '已启用' : '已停用')
  } catch (e: any) {
    toast.error('操作失败：' + (e?.message ?? e))
  }
}

async function remove(wf: WorkflowDef) {
  if (!confirm(`确认删除工作流「${wf.name}」？`)) return
  try {
    await workflowApi.remove(wf.id)
    workflows.value = workflows.value.filter((w) => w.id !== wf.id)
    toast.success('已删除')
  } catch (e: any) {
    toast.error('删除失败：' + (e?.message ?? e))
  }
}

/**
 * 列表页直接触发：提交后跳进编辑器并跟踪这次执行。
 *
 * <p>为什么不是「留在列表刷新一下」：列表页没有观测面，ASYNC 提交后唯一能看到的
 * 只有一个 RUNNING 徽标，等于把「不知道跑到哪了」的问题原样保留。跳进调试台
 * 才是这个按钮该有的语义。</p>
 */
async function trigger(wf: WorkflowDef) {
  triggeringId.value = wf.id
  try {
    const inst = await workflowApi.trigger(wf.id, {}, 'ASYNC')
    instances.value = [inst, ...instances.value].slice(0, 20)
    const def = workflows.value.find((w) => w.id === wf.id) ?? (await workflowApi.get(wf.id))
    openEditor(def)
    showRun.value = true
    await attachRun(inst.instanceId)
    toast.success(`已提交执行：${inst.instanceId.slice(0, 8)}`)
  } catch (e: any) {
    toast.error('触发失败：' + (e?.message ?? e))
  } finally {
    triggeringId.value = null
  }
}

/** 打开一次历史运行的只读回放（终态实例不会订阅事件流）。 */
async function openRunDetail(inst: WorkflowInstance) {
  try {
    const def = workflows.value.find((w) => w.id === inst.workflowId) ?? (await workflowApi.get(inst.workflowId))
    openEditor(def)
    showRun.value = true
    await attachRun(inst.instanceId)
  } catch (e: any) {
    toast.error('打开运行详情失败：' + (e?.message ?? e))
  }
}

async function createWorkflow() {
  const name = newName.value.trim()
  if (!name) {
    toast.error('请填写工作流名称')
    return
  }
  savingNew.value = true
  try {
    const id = 'wf-' + Math.random().toString(36).slice(2, 10)
    const def: Partial<WorkflowDef> = {
      id,
      name,
      description: newDesc.value.trim() || undefined,
      version: 1,
      enabled: true,
      trigger: { type: 'MANUAL', enabled: true },
      nodes: [
        { id: 'start', type: 'START', name: '开始' },
        { id: 'end', type: 'END', name: '结束' },
      ],
      edges: [{ source: 'start', target: 'end' }],
    }
    const saved = await workflowApi.create(def)
    workflows.value = [saved, ...workflows.value]
    creating.value = false
    newName.value = ''
    newDesc.value = ''
    toast.success('已创建，进入画布继续编排')
    openEditor(saved)
  } catch (e: any) {
    toast.error('创建失败：' + (e?.message ?? e))
  } finally {
    savingNew.value = false
  }
}

// ---------------- 定义 ↔ 画布 ----------------

const labelOfType = (t: WorkflowNodeType) => metas.value.find((m) => m.type === t)?.label ?? t

function applyDef(def: WorkflowDef) {
  editing.value = def
  const g = defToGraph(def, labelOfType)
  nodes.value = g.nodes
  edges.value = g.edges
}

/** 画布 → 后端定义（转换逻辑见 graph.ts，与 applyDef 互为逆运算）。 */
function toDef(): WorkflowDef {
  return graphToDef(editing.value!, nodes.value, edges.value)
}

const showRun = ref(false)

function openEditor(def: WorkflowDef) {
  applyDef(def)
  selectedId.value = null
  dirty.value = false
  showVars.value = false
  loadVars(def.id)
  resetRun()
  showRun.value = false
  view.value = 'edit'
}

function backToList() {
  if (dirty.value && !confirm('有未保存的改动，确认返回？改动将丢失。')) return
  view.value = 'list'
  editing.value = null
  nodes.value = []
  edges.value = []
  selectedId.value = null
  showVars.value = false
  resetRun()
  showRun.value = false
  load()
}

// ---------------- 画布操作 ----------------

function defaultConfig(meta: WorkflowNodeTypeMeta): Record<string, any> {
  const c: Record<string, any> = {}
  for (const f of meta.fields) {
    if (f.defaultValue !== undefined && f.defaultValue !== null) c[f.key] = f.defaultValue
  }
  return c
}

function nextId(type: WorkflowNodeType) {
  const base = type.toLowerCase()
  let i = 1
  while (nodes.value.some((n) => n.id === `${base}-${i}`)) i++
  return `${base}-${i}`
}

function addNode({ type, position }: { type: WorkflowNodeType; position?: { x: number; y: number } }) {
  const meta = metas.value.find((m) => m.type === type)
  if (!meta) return
  const id = nextId(type)
  const pos = position ?? { x: 80 + nodes.value.length * 28, y: 300 }
  nodes.value = [
    ...nodes.value,
    {
      id,
      type,
      position: { x: Math.round(pos.x), y: Math.round(pos.y) },
      data: { label: meta.label, config: defaultConfig(meta) },
    },
  ]
  selectedId.value = id
  dirty.value = true
}

function removeNode(id: string) {
  const meta = selectedMeta.value
  if (meta?.required) {
    toast.error('START / END 为流程必需节点，不可删除')
    return
  }
  if (!confirm(`确认删除节点「${id}」及其连线？`)) return
  nodes.value = nodes.value.filter((n) => n.id !== id)
  edges.value = edges.value.filter((e) => e.source !== id && e.target !== id)
  if (selectedId.value === id) selectedId.value = null
  dirty.value = true
}

const selectedNode = computed(() => nodes.value.find((n) => n.id === selectedId.value) ?? null)
const selectedMeta = computed(
  () => metas.value.find((m) => m.type === selectedNode.value?.type) ?? null,
)
const selectedDef = computed(() => {
  const n = selectedNode.value
  if (!n) return null
  const d = (n.data ?? {}) as any
  return {
    id: n.id,
    type: n.type as WorkflowNodeType,
    name: d.label as string,
    config: (d.config ?? {}) as Record<string, any>,
  }
})
const canRemoveSelected = computed(() => !!selectedMeta.value && !selectedMeta.value.required)

const nodeLabel = (id: string) => {
  const n = nodes.value.find((x) => x.id === id)
  return n ? ((n.data as any)?.label || id) : id
}

/**
 * nodeId → 节点定义 config，供执行面板在「节点未声明输入映射」时兜底展示。
 * 用 computed 而非函数 prop：面板只需在节点变化时重算，不必每次渲染都遍历 nodes。
 */
const nodeConfigs = computed<Record<string, Record<string, unknown>>>(() => {
  const out: Record<string, Record<string, unknown>> = {}
  for (const n of nodes.value) {
    const cfg = (n.data as any)?.config
    if (cfg && typeof cfg === 'object') out[n.id] = cfg as Record<string, unknown>
  }
  return out
})

const outEdges = computed(() => {
  if (!selectedId.value) return []
  return edges.value
    .filter((e) => e.source === selectedId.value)
    .map((e) => ({
      id: e.id,
      targetLabel: nodeLabel(e.target),
      condition: ((e.data as any)?.condition as string) ?? '',
      label: ((e.data as any)?.label as string) ?? '',
    }))
})

function patchSelected(p: { name?: string }) {
  if (!selectedId.value || p.name === undefined) return
  const id = selectedId.value
  nodes.value = nodes.value.map((n) =>
    n.id === id ? { ...n, data: { ...(n.data ?? {}), label: p.name } } : n,
  )
  dirty.value = true
}

function setConfig(p: { key: string; value: any }) {
  if (!selectedId.value) return
  const id = selectedId.value
  nodes.value = nodes.value.map((n) => {
    if (n.id !== id) return n
    const d = { ...(n.data ?? {}) }
    d.config = { ...(d.config ?? {}), [p.key]: p.value }
    return { ...n, data: d }
  })
  dirty.value = true
}

function setEdge(p: { id: string; patch: { condition?: string; label?: string } }) {
  edges.value = edges.value.map((e) => {
    if (e.id !== p.id) return e
    const d = { ...(e.data ?? {}), ...p.patch }
    // 展示文本与持久化解耦（graphToDef 只读 data）：标签缺失时用条件顶上，
    // 否则 CONDITION 的分支在画布上会「有边无字」。
    return { ...e, data: d, label: d.label || d.condition || undefined }
  })
  dirty.value = true
}

/** 自动布局：坐标计算走 graph.ts 的纯函数，画布负责重新适应视口。 */
function autoArrange() {
  const pos = autoLayout({ nodes: nodes.value, edges: edges.value })
  nodes.value = nodes.value.map((n) => ({ ...n, position: pos[n.id] ?? n.position }))
  dirty.value = true
}

// ---------------- 运行态 ----------------

const {
  triggering: runTriggering,
  running: wfRunning,
  watching: wfWatching,
  instance: runInstance,
  track: runTrack,
  marks: runMarks,
  counts: runCounts,
  elapsedMs: runElapsed,
  workflowMessage: runMessage,
  mode: runMode,
  firstFailedNodeId,
  start: startRun,
  attach: attachRun,
  stop: stopWatch,
  reset: resetRun,
} = useWorkflowRun({
  workflowId: () => editing.value?.id,
  nameOf: (id) => nodeLabel(id),
  orderOf: (id) => {
    const i = nodes.value.findIndex((n) => n.id === id)
    return i < 0 ? Number.MAX_SAFE_INTEGER : i
  },
  // 轨迹分母取「定义里的节点总数」，使运行中途的 x/y 与画布上的节点数始终一致
  defNodeIds: () => nodes.value.map((n) => n.id),
})

/** 失败时自动定位到首个失败节点，省掉用户自己找错在哪一步。 */
watch(firstFailedNodeId, (id) => {
  if (id && !selectedId.value) selectedId.value = id
})

/** 列表里的运行记录随执行推进同步刷新（否则回到列表看到的还是提交那一刻的状态）。 */
watch(runInstance, (inst) => {
  if (!inst) return
  const i = instances.value.findIndex((x) => x.instanceId === inst.instanceId)
  if (i >= 0) instances.value[i] = inst
  else instances.value = [inst, ...instances.value].slice(0, 20)
})

// ---------------- 保存 / 校验 / 运行 ----------------

async function save(): Promise<boolean> {
  if (!editing.value) return false
  saving.value = true
  try {
    const saved = await workflowApi.save(toDef())
    applyDef(saved)
    const i = workflows.value.findIndex((w) => w.id === saved.id)
    if (i >= 0) workflows.value[i] = saved
    else workflows.value = [saved, ...workflows.value]
    // applyDef 会重建数组，选中态需保留在同一节点上
    const keep = selectedId.value
    selectedId.value = keep && nodes.value.some((n) => n.id === keep) ? keep : null
    dirty.value = false
    return true
  } catch (e: any) {
    toast.error('保存失败：' + (e?.message ?? e))
    return false
  } finally {
    saving.value = false
  }
}

async function saveCurrent() {
  if (await save()) toast.success('已保存')
}

async function validateCurrent() {
  try {
    const r = await workflowApi.validate(toDef())
    if (r?.valid) {
      toast.success(`结构合法：${r.nodeCount} 个节点，拓扑序 ${(r.topologicalOrder ?? []).join(' → ')}`)
    } else {
      toast.error('结构非法（后端未返回细节，请检查是否仅 1 个 START、至少 1 个 END、无断边）')
    }
  } catch (e: any) {
    toast.error('校验失败：' + (e?.message ?? e))
  }
}

async function runCurrent() {
  if (!editing.value) return
  const vars = parseVars()
  if (vars === null) {
    showVars.value = true
    toast.error('运行变量不是合法 JSON，已展开输入框')
    return
  }
  // 后端执行的是落盘定义 —— 先保存再跑，否则「改完点运行」跑的是旧图
  if (dirty.value && !(await save())) return
  showRun.value = true
  try {
    localStorage.setItem(VARS_KEY(editing.value.id), varsText.value)
  } catch {
    // 无痕模式等场景写入失败不影响运行
  }
  try {
    await startRun(vars)
    toast.success('已提交执行，正在跟踪运行')
  } catch (e: any) {
    toast.error('运行失败：' + (e?.message ?? e))
  }
}

function closeRunPanel() {
  showRun.value = false
  resetRun()
  selectedId.value = null
}

// ---------------- 列表渲染辅助 ----------------

const fmtTime = (ts?: number) => (ts ? new Date(ts).toLocaleString() : '—')
/** 运行中实例的 endedAt 为 0（不是 null），必须显式判 >0，否则会算出负耗时显示 "—"。 */
const instDuration = (i: WorkflowInstance) => {
  if (!i.startedAt) return '—'
  const end = i.endedAt && i.endedAt > 0 ? i.endedAt : Date.now()
  return fmtMs(Math.max(0, end - i.startedAt))
}

onMounted(() => {
  load()
  loadMetas()
})
</script>

<template>
  <div class="wf">
    <header class="wf__head">
      <span class="mono wf__label">工作流</span>
      <template v-if="view === 'list'">
        <span class="wf__sub">本地编排（agent-workflow）· DAG 驱动的多节点执行</span>
        <button class="wf__new wf__new--primary" @click="creating = true">
          <Icon name="plus" :size="13" />
          新建工作流
        </button>
      </template>
      <template v-else>
        <span class="wf__sub">
          {{ editing?.name }}
          <span class="mono wf__sub-id">{{ editing?.id }}</span>
          <span v-if="dirty" class="wf__dirty">· 未保存</span>
        </span>
        <div class="wf__acts">
          <button class="wf__new" @click="backToList">
            <Icon name="chevronLeft" :size="13" />
            返回
          </button>
          <button class="wf__new" @click="validateCurrent"><Icon name="check" :size="13" />校验</button>
          <button class="wf__new" :disabled="saving" @click="saveCurrent">
            <Icon name="download" :size="13" />{{ saving ? '保存中…' : '保存' }}
          </button>

          <!-- 运行 + 运行变量（变量按工作流 id 记忆在本机浏览器） -->
          <div class="wf__run-wrap">
            <button
              class="wf__new wf__new--primary"
              :disabled="runTriggering || wfWatching"
              @click="runCurrent"
            >
              <Icon :name="runTriggering || wfWatching ? 'loader' : 'play'" :size="13" />
              {{ runTriggering ? '提交中' : wfWatching ? '运行中' : '运行' }}
            </button>
            <button
              class="wf__new wf__vars-btn"
              :class="{ 'wf__vars-btn--on': showVars }"
              title="运行变量（JSON，触发时写入工作流作用域）"
              @click="showVars = !showVars"
            >
              <Icon name="sliders" :size="12" />
            </button>
            <div v-if="showVars" class="wf__vars-pop">
              <div class="wf__vars-head">
                运行变量
                <button class="wf__vars-x" @click="showVars = false"><Icon name="x" :size="11" /></button>
              </div>
              <textarea
                v-model="varsText"
                class="wf__vars-area mono"
                rows="6"
                spellcheck="false"
                placeholder='{ "content": "要处理的内容" }'
              />
              <p class="wf__vars-hint">
                LLM 节点里的 <code>${content}</code> 这类占位会在这里取值；START 节点把整份对象透传进作用域。
                变量按工作流存在本机浏览器，不随定义落盘。
              </p>
              <p v-if="varsErr" class="wf__vars-err">{{ varsErr }}</p>
            </div>
          </div>
        </div>
      </template>
    </header>

    <!-- ===================== 列表模式 ===================== -->
    <div v-if="view === 'list' && loading" class="wf__loading">
      <Icon name="loader" :size="18" /> 加载中…
    </div>

    <div v-else-if="view === 'list'" class="wf__body">
      <section class="wf__col">
        <div class="wf__col-head">工作流定义（{{ workflows.length }}）</div>
        <div v-if="!workflows.length" class="wf__empty">
          <Icon name="gitBranch" :size="26" />
          <p>暂无工作流。点击右上角「新建工作流」创建一个 start→end 骨架。</p>
        </div>
        <ul class="wf__list">
          <li v-for="wf in workflows" :key="wf.id" class="wf__card">
            <div class="wf__card-main wf__card-main--click" @click="openEditor(wf)">
              <div class="wf__card-title">
                <span>{{ wf.name }}</span>
                <span class="wf-badge" :class="wf.enabled ? 'wf-badge--ok' : 'wf-badge--off'">
                  {{ wf.enabled ? '已启用' : '已停用' }}
                </span>
              </div>
              <div class="wf__card-meta mono">
                {{ wf.id }} · {{ wf.nodes?.length ?? 0 }} 节点 · {{ triggerLabel(wf.trigger) }}
              </div>
              <div class="wf__card-desc" v-if="wf.description">{{ wf.description }}</div>
            </div>
            <div class="wf__card-acts">
              <button class="wf__btn wf__btn--primary" @click="openEditor(wf)">
                <Icon name="sliders" :size="12" />
                编排
              </button>
              <button class="wf__btn" :disabled="triggeringId === wf.id" @click="trigger(wf)">
                <Icon :name="triggeringId === wf.id ? 'loader' : 'play'" :size="12" />
                {{ triggeringId === wf.id ? '提交中' : '运行' }}
              </button>
              <button class="wf__btn" @click="toggle(wf)">{{ wf.enabled ? '停用' : '启用' }}</button>
              <button class="wf__btn wf__btn--danger" @click="remove(wf)">
                <Icon name="trash" :size="12" />
              </button>
            </div>
          </li>
        </ul>
      </section>

      <section class="wf__col wf__col--side">
        <div class="wf__col-head">最近运行（{{ instances.length }}）· 点击查看详情</div>
        <div v-if="!instances.length" class="wf__empty wf__empty--sm">
          <p>尚无运行记录。</p>
        </div>
        <ul class="wf__runs">
          <li v-for="i in instances" :key="i.instanceId" class="wf__run wf__run--click" @click="openRunDetail(i)">
            <span class="wf-badge" :class="statusClass(i.status)">{{ i.status }}</span>
            <div class="wf__run-info mono">
              <div>{{ i.instanceId.slice(0, 12) }}</div>
              <div class="wf__run-sub">{{ fmtTime(i.startedAt) }} · {{ instDuration(i) }}</div>
            </div>
            <Icon name="chevronRight" :size="12" class="wf__run-go" />
          </li>
        </ul>
      </section>
    </div>

    <!-- ===================== 编辑模式 ===================== -->
    <div v-else class="wf__editor">
      <div v-if="metasError" class="wf__alert">
        <Icon name="warning" :size="14" />
        {{ metasError }}
        <button class="wf__alert-btn" @click="loadMetas">重试</button>
      </div>

      <div class="wf__editor-main">
        <WorkflowCanvas
          v-if="metas.length"
          v-model:nodes="nodes"
          v-model:edges="edges"
          :metas="metas"
          :marks="runMarks"
          :selected-id="selectedId"
          @select="selectedId = $event"
          @add-node="addNode"
          @changed="dirty = true"
          @auto-layout="autoArrange"
        />
        <div v-else class="wf__editor-wait">
          <Icon name="loader" :size="16" /> 正在获取节点类型…
        </div>

        <NodeInspector
          :node="selectedDef"
          :meta="selectedMeta"
          :out-edges="outEdges"
          :can-remove="canRemoveSelected"
          @patch="patchSelected"
          @set-config="setConfig"
          @set-edge="setEdge"
          @remove="removeNode"
        />
      </div>

      <WfRunPanel
        v-if="showRun"
        :instance="runInstance"
        :track="runTrack"
        :marks="runMarks"
        :counts="runCounts"
        :running="wfRunning"
        :watching="wfWatching"
        :mode="runMode"
        :elapsed-ms="runElapsed"
        :selected-id="selectedId"
        :workflow-message="runMessage"
        :configs="nodeConfigs"
        @select="selectedId = $event"
        @close="closeRunPanel"
        @rerun="runCurrent"
        @stop-watch="stopWatch"
      />
    </div>

    <!-- 新建弹窗 -->
    <Teleport to="body">
      <div v-if="creating" class="wf-modal" @click.self="creating = false">
        <div class="wf-modal__box">
          <div class="wf-modal__head">新建工作流</div>
          <label class="wf-field">
            <span>名称</span>
            <input v-model="newName" placeholder="例如：日报生成流程" class="wf-input" />
          </label>
          <label class="wf-field">
            <span>描述（可选）</span>
            <input v-model="newDesc" placeholder="一句话说明用途" class="wf-input" />
          </label>
          <div class="wf-modal__acts">
            <button class="wf__btn" :disabled="savingNew" @click="creating = false">取消</button>
            <button class="wf__btn wf__btn--primary" :disabled="savingNew" @click="createWorkflow">
              {{ savingNew ? '创建中…' : '创建并编排' }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.wf {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
}
.wf__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  min-height: 46px;
  flex-wrap: wrap;
}
.wf__label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.wf__sub {
  font-size: var(--fs-12);
  color: var(--text-3);
  display: flex;
  align-items: center;
  gap: 6px;
}
.wf__sub-id {
  font-size: var(--fs-11);
  color: var(--text-3);
  opacity: 0.75;
}
.wf__dirty {
  color: var(--warn);
}
.wf__acts {
  margin-left: auto;
  display: flex;
  gap: 6px;
  align-items: center;
}
.wf__new {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--border-strong);
  background: var(--bg-1);
  border-radius: var(--r-6);
  color: var(--text-1);
  font-size: var(--fs-13);
  cursor: pointer;
}
.wf__new:hover:not(:disabled) {
  border-color: var(--accent);
}
.wf__new--primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.wf__new:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ---------- 运行 + 变量输入 ---------- */
.wf__run-wrap {
  position: relative;
  display: flex;
  gap: 4px;
}
.wf__vars-btn {
  padding: 6px 8px;
}
.wf__vars-btn--on {
  border-color: var(--accent);
  color: var(--accent-text);
}
.wf__vars-pop {
  position: absolute;
  top: calc(100% + 6px);
  right: 0;
  width: 340px;
  padding: 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-8);
  background: var(--bg-0);
  box-shadow: var(--shadow-2);
  z-index: 20;
}
.wf__vars-head {
  display: flex;
  align-items: center;
  font-size: var(--fs-12);
  color: var(--text-1);
  margin-bottom: 8px;
}
.wf__vars-x {
  margin-left: auto;
  display: flex;
  border: 0;
  background: transparent;
  color: var(--text-3);
  cursor: pointer;
}
.wf__vars-area {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-1);
  color: var(--text-1);
  font-size: var(--fs-12);
  line-height: 1.6;
  resize: vertical;
}
.wf__vars-area:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: var(--glow-accent);
}
.wf__vars-hint {
  margin: 6px 0 0;
  font-size: var(--fs-10);
  color: var(--text-3);
  line-height: 1.6;
}
.wf__vars-hint code {
  font-family: var(--font-mono);
  background: var(--bg-2);
  padding: 0 3px;
  border-radius: var(--r-4);
}
.wf__vars-err {
  margin: 6px 0 0;
  font-size: var(--fs-11);
  color: var(--danger-text);
}

.wf__body {
  flex: 1;
  display: grid;
  grid-template-columns: 1fr 320px;
  gap: 12px;
  padding: 12px 16px;
  overflow: auto;
}
.wf__col {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.wf__col-head {
  font-size: var(--fs-12);
  color: var(--text-3);
  margin-bottom: 8px;
}
.wf__empty {
  margin: auto;
  text-align: center;
  color: var(--text-3);
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 30px;
}
.wf__empty p {
  margin: 0;
  font-size: var(--fs-13);
  line-height: 1.7;
}
.wf__empty--sm {
  padding: 16px;
}
.wf__list,
.wf__runs {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.wf__card {
  display: flex;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  background: var(--bg-1);
}
.wf__card-main {
  flex: 1;
  min-width: 0;
}
.wf__card-main--click {
  cursor: pointer;
}
.wf__card-main--click:hover .wf__card-title > span:first-child {
  color: var(--accent-text);
}
.wf__card-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  color: var(--text-1);
}
.wf__card-meta {
  font-size: var(--fs-11);
  color: var(--text-3);
  margin-top: 4px;
}
.wf__card-desc {
  font-size: var(--fs-12);
  color: var(--text-2);
  margin-top: 6px;
  line-height: 1.6;
}
.wf__card-acts {
  display: flex;
  flex-direction: column;
  gap: 6px;
  align-items: stretch;
  justify-content: center;
}
.wf__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  padding: 6px 10px;
  border: 1px solid var(--border-strong);
  background: var(--bg-2);
  color: var(--text-1);
  border-radius: var(--r-6);
  font-size: var(--fs-12);
  cursor: pointer;
  white-space: nowrap;
}
.wf__btn:hover:not(:disabled) {
  border-color: var(--accent);
}
.wf__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.wf__btn--primary {
  background: var(--accent);
  border-color: var(--accent);
  color: #fff;
}
.wf__btn--danger:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger-text);
}
.wf__runs {
  gap: 6px;
}
.wf__run {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  background: var(--bg-1);
}
.wf__run--click {
  cursor: pointer;
}
.wf__run--click:hover {
  border-color: var(--accent);
}
.wf__run-go {
  color: var(--text-3);
  flex-shrink: 0;
}
.wf__run-info {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-11);
  color: var(--text-2);
}
.wf__run-sub {
  color: var(--text-3);
}

/* ---------- 编辑模式 ---------- */
.wf__editor {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.wf__editor-main {
  flex: 1;
  min-height: 0;
  display: flex;
}
.wf__editor-wait {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
  font-size: var(--fs-13);
}
.wf__alert {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  background: var(--warn-dim);
  color: var(--warn);
  font-size: var(--fs-12);
}
.wf__alert-btn {
  margin-left: auto;
  padding: 3px 10px;
  border: 1px solid currentColor;
  border-radius: var(--r-6);
  background: transparent;
  color: inherit;
  font-size: var(--fs-11);
  cursor: pointer;
}

/* ---------- 徽标 ---------- */
.wf-badge {
  flex-shrink: 0;
  font-size: var(--fs-10);
  padding: 2px 7px;
  border-radius: 999px;
  border: 1px solid transparent;
}
.wf-badge--ok {
  color: #2ea043;
  border-color: #2ea04344;
  background: #2ea0431a;
}
.wf-badge--off {
  color: var(--text-3);
  border-color: var(--border-strong);
}
.wf-badge--run {
  color: var(--accent);
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.wf-badge--err {
  color: var(--danger);
  border-color: var(--danger-dim);
  background: var(--danger-dim);
}
.wf-badge--warn {
  color: var(--warn);
  border-color: var(--warn-dim);
  background: var(--warn-dim);
}
.wf__loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
}

/* ---------- 弹窗 ---------- */
.wf-modal {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}
.wf-modal__box {
  width: 380px;
  max-width: 92vw;
  background: var(--bg-1);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-10);
  padding: 18px;
}
.wf-modal__head {
  font-size: var(--fs-15);
  font-weight: 600;
  color: var(--text-1);
  margin-bottom: 14px;
}
.wf-field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  margin-bottom: 12px;
  font-size: var(--fs-12);
  color: var(--text-2);
}
.wf-input {
  padding: 8px 10px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-2);
  color: var(--text-1);
  font-size: var(--fs-13);
}
.wf-modal__acts {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 6px;
}
</style>
