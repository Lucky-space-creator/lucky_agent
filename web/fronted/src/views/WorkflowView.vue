<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Icon from '@/components/common/Icon.vue'
import { workflowApi } from '@/api'
import type { WorkflowDef, WorkflowInstance, WorkflowInstanceStatus } from '@/api/types'
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
    const saved = await workflowApi.save(def)
    workflows.value = [saved, ...workflows.value]
    creating.value = false
    newName.value = ''
    newDesc.value = ''
    toast.success('已创建（已含 start→end 骨架，可在后端 API 补充节点）')
  } catch (e: any) {
    toast.error('创建失败：' + (e?.message ?? e))
  } finally {
    savingNew.value = false
  }
}

const fmtTime = (ts?: number) => (ts ? new Date(ts).toLocaleString() : '—')
const duration = (i: WorkflowInstance) => {
  const d = (i.endedAt ?? Date.now()) - (i.startedAt ?? Date.now())
  return d > 0 ? Math.round(d / 1000) + 's' : '—'
}

onMounted(load)
</script>

<template>
  <div class="wf">
    <header class="wf__head">
      <span class="mono wf__label">工作流</span>
      <span class="wf__sub">本地编排（agent-workflow）· DAG 驱动的多节点执行</span>
      <button class="wf__new" @click="creating = true">
        <Icon name="plus" :size="13" />
        新建工作流
      </button>
    </header>

    <div class="wf__body" v-if="!loading">
      <!-- 左侧：工作流定义 -->
      <section class="wf__col">
        <div class="wf__col-head">工作流定义（{{ workflows.length }}）</div>
        <div v-if="!workflows.length" class="wf__empty">
          <Icon name="gitBranch" :size="26" />
          <p>暂无工作流。点击右上角「新建工作流」创建一个 start→end 骨架。</p>
        </div>
        <ul class="wf__list">
          <li v-for="wf in workflows" :key="wf.id" class="wf__card">
            <div class="wf__card-main">
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
              <button class="wf__btn wf__btn--primary" :disabled="runningId === wf.id" @click="trigger(wf)">
                <Icon :name="runningId === wf.id ? 'loader' : 'play'" :size="12" />
                {{ runningId === wf.id ? '运行中' : '运行' }}
              </button>
              <button class="wf__btn" @click="toggle(wf)">
                {{ wf.enabled ? '停用' : '启用' }}
              </button>
              <button class="wf__btn wf__btn--danger" @click="remove(wf)">
                <Icon name="trash" :size="12" />
              </button>
            </div>
          </li>
        </ul>
      </section>

      <!-- 右侧：运行实例 -->
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

    <div v-else class="wf__loading"><Icon name="loader" :size="18" /> 加载中…</div>

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
              {{ savingNew ? '创建中…' : '创建' }}
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
  align-items: baseline;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
}
.wf__label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.wf__sub {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.wf__new {
  margin-left: auto;
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
.wf__new:hover {
  border-color: var(--accent, #4f8cff);
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
.wf__btn:hover {
  border-color: var(--accent, #4f8cff);
}
.wf__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.wf__btn--primary {
  background: var(--accent, #4f8cff);
  border-color: var(--accent, #4f8cff);
  color: #fff;
}
.wf__btn--danger:hover {
  border-color: #e5484d;
  color: #e5484d;
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
  color: #e5484d;
}
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
  color: #4f8cff;
  border-color: #4f8cff44;
  background: #4f8cff1a;
}
.wf-badge--err {
  color: #e5484d;
  border-color: #e5484d44;
  background: #e5484d1a;
}
.wf-badge--warn {
  color: #d29922;
  border-color: #d2992244;
  background: #d299221a;
}
.wf__loading {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
}
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
