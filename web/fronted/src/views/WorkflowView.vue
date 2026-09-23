<script setup lang="ts">
/**
 * 工作流页：列表（定义 + 运行记录）与画布编排两种模式。
 *
 * <p><b>数据流约定：</b>编辑期间画布上的 nodes/edges 是唯一事实源，
 * {@link toDef} 在保存时把它们转换为后端 {@code WorkflowDef}；反向由
 * {@link applyDef} 转换。编辑器中不再保留第二份 WorkflowDef 副本，
 * 避免「改了 A 忘了同步 B」这类隐性漂移。两个转换是 graph.ts 中的纯函数，
 * 可脱离画布单测。</p>
 */
import { computed, onMounted, ref } from 'vue'
import Icon from '@/components/common/Icon.vue'
import WorkflowCanvas from '@/components/workflow/WorkflowCanvas.vue'
import NodeInspector from '@/components/workflow/NodeInspector.vue'
import { defToGraph, graphToDef } from '@/components/workflow/graph'
import type { WfGraphEdge, WfGraphNode } from '@/components/workflow/graph'
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
const runningId = ref<string | null>(null)
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
const running = ref(false)
const runResult = ref<WorkflowInstance | null>(null)
const showRuns = ref(false)

const statusClass = (s?: WorkflowInstanceStatus) =>
  ({
    RUNNING: 'wf-badge--run',
    COMPLETED: 'wf-badge--ok',
    FAILED: 'wf-badge--err',
    SUSPENDED: 'wf-badge--warn',
  })[s ?? 'RUNNING'] ?? 'wf-badge--run'

const NODE_STATUS_CLASS: Record<string, string> = {
  COMPLETED: 'wf-badge--ok',
  FAILED: 'wf-badge--err',
  RUNNING: 'wf-badge--run',
  SKIPPED: 'wf-badge--off',
  PENDING: 'wf-badge--off',
  WAITING: 'wf-badge--warn',
}

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

async function trigger(wf: WorkflowDef) {
  runningId.value = wf.id
  try {
    const inst = await workflowApi.trigger(wf.id)
    instances.value = [inst, ...instances.value].slice(0, 20)
    toast.success(`已触发：${inst.instanceId.slice(0, 8)} · ${inst.status}`)
  } catch (e: any) {
    toast.error('触发失败：' + (e?.message ?? e))
  } finally {
    runningId.value = null
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

function openEditor(def: WorkflowDef) {
  applyDef(def)
  selectedId.value = null
  dirty.value = false
  runResult.value = null
  showRuns.value = false
  view.value = 'edit'
}

function backToList() {
  if (dirty.value && !confirm('有未保存的改动，确认返回？改动将丢失。')) return
  view.value = 'list'
  editing.value = null
  nodes.value = []
  edges.value = []
  selectedId.value = null
  runResult.value = null
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
    return { ...e, data: d, label: d.label || undefined }
  })
  dirty.value = true
}

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

function applyRun(inst: WorkflowInstance) {
  const ni = inst.nodeInstances ?? {}
  nodes.value = nodes.value.map((n) => ({
    ...n,
    data: { ...(n.data ?? {}), runStatus: ni[n.id]?.status },
  }))
}

async function runCurrent() {
  if (!editing.value) return
  running.value = true
  try {
    // 后端执行的是落盘定义 —— 先保存再跑，否则「改完点运行」跑的是旧图
    if (dirty.value && !(await save())) return
    const inst = await workflowApi.trigger(editing.value.id, {}, 'SYNC')
    runResult.value = inst
    applyRun(inst)
    showRuns.value = true
    instances.value = [inst, ...instances.value].slice(0, 20)
    if (inst.status === 'COMPLETED') toast.success('运行完成')
    else toast.error(`运行 ${inst.status}${inst.error ? '：' + inst.error : ''}`)
  } catch (e: any) {
    toast.error('运行失败：' + (e?.message ?? e))
  } finally {
    running.value = false
  }
}

function clearRunMark() {
  runResult.value = null
  nodes.value = nodes.value.map((n) => {
    const d = { ...(n.data ?? {}) }
    delete d.runStatus
    return { ...n, data: d }
  })
}

/** 运行结果里点节点 → 画布选中同一节点，便于定位失败点。 */
function focusNode(id: string) {
  selectedId.value = id
}

const runNodes = computed(() => {
  const ni = runResult.value?.nodeInstances ?? {}
  return Object.values(ni).map((n) => ({
    id: n.nodeId,
    name: n.nodeName || nodeLabel(n.nodeId),
    status: n.status ?? 'PENDING',
    error: n.error,
    duration:
      n.startedAt && n.endedAt ? Math.round((n.endedAt - n.startedAt) / 100) / 10 + 's' : '—',
  }))
})

const fmtTime = (ts?: number) => (ts ? new Date(ts).toLocaleString() : '—')
const duration = (i: WorkflowInstance) => {
  const d = (i.endedAt ?? Date.now()) - (i.startedAt ?? Date.now())
  return d > 0 ? Math.round(d / 1000) + 's' : '—'
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
        <button class="wf__new" @click="creating = true">
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
          <button class="wf__new wf__new--primary" :disabled="running" @click="runCurrent">
            <Icon :name="running ? 'loader' : 'play'" :size="13" />{{ running ? '运行中' : '运行' }}
          </button>
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
              <button class="wf__btn" :disabled="runningId === wf.id" @click="trigger(wf)">
                <Icon :name="runningId === wf.id ? 'loader' : 'play'" :size="12" />
                {{ runningId === wf.id ? '运行中' : '运行' }}
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
        <div class="wf__col-head">最近运行（{{ instances.length }}）</div>
        <div v-if="!instances.length" class="wf__empty wf__empty--sm">
          <p>尚无运行记录。</p>
        </div>
        <ul class="wf__runs">
          <li v-for="i in instances" :key="i.instanceId" class="wf__run">
            <span class="wf-badge" :class="statusClass(i.status)">{{ i.status }}</span>
            <div class="wf__run-info mono">
              <div>{{ i.instanceId.slice(0, 12) }}</div>
              <div class="wf__run-sub">{{ fmtTime(i.startedAt) }} · {{ duration(i) }}</div>
            </div>
            <span v-if="i.error" class="wf__run-err" :title="i.error">⚠</span>
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
          @select="selectedId = $event"
          @add-node="addNode"
          @changed="dirty = true"
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

      <!-- 运行结果 -->
      <div v-if="runResult" class="wf__result">
        <div class="wf__result-head">
          <span class="wf-badge" :class="statusClass(runResult.status)">{{ runResult.status }}</span>
          <span class="mono wf__result-id">{{ runResult.instanceId.slice(0, 12) }} · {{ duration(runResult) }}</span>
          <span v-if="runResult.error" class="wf__result-err">{{ runResult.error }}</span>
          <button class="wf__result-toggle" @click="showRuns = !showRuns">
            <Icon :name="showRuns ? 'chevronDown' : 'chevronUp'" :size="12" />
            节点明细
          </button>
          <button class="wf__result-toggle" @click="clearRunMark">
            <Icon name="x" :size="12" />
            清除标记
          </button>
        </div>
        <ul v-if="showRuns" class="wf__result-list">
          <li v-for="n in runNodes" :key="n.id" class="wf__result-item" @click="focusNode(n.id)">
            <span class="wf-badge" :class="NODE_STATUS_CLASS[n.status] ?? 'wf-badge--off'">{{ n.status }}</span>
            <span class="wf__result-name">{{ n.name }}</span>
            <span class="mono wf__result-dim">{{ n.duration }}</span>
            <span v-if="n.error" class="wf__result-err" :title="n.error">{{ n.error }}</span>
          </li>
        </ul>
      </div>
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
.wf__run-info {
  flex: 1;
  min-width: 0;
  font-size: var(--fs-11);
  color: var(--text-2);
}
.wf__run-sub {
  color: var(--text-3);
}
.wf__run-err {
  color: var(--danger);
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
.wf__result {
  border-top: 1px solid var(--border);
  background: var(--bg-1);
  max-height: 190px;
  overflow-y: auto;
}
.wf__result-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 16px;
}
.wf__result-id {
  font-size: var(--fs-11);
  color: var(--text-3);
}
.wf__result-err {
  font-size: var(--fs-11);
  color: var(--danger-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 380px;
}
.wf__result-toggle {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-0);
  color: var(--text-2);
  font-size: var(--fs-11);
  cursor: pointer;
}
.wf__result-toggle + .wf__result-toggle {
  margin-left: 0;
}
.wf__result-list {
  list-style: none;
  margin: 0;
  padding: 0 16px 10px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.wf__result-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: var(--r-6);
  cursor: pointer;
}
.wf__result-item:hover {
  background: var(--bg-2);
}
.wf__result-name {
  font-size: var(--fs-12);
  color: var(--text-1);
}
.wf__result-dim {
  margin-left: auto;
  font-size: var(--fs-11);
  color: var(--text-3);
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
