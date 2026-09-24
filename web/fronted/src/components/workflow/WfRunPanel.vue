<script setup lang="ts">
/**
 * 执行调试台：底部可拖拽抽屉，替换原先 190px 的结果条。
 *
 * <p><b>为什么单独成组件而不是塞进 WorkflowView：</b>这里呈现的是「一次运行的完整观测面」——
 * 左轨是时间序（谁在跑、跑了多久、为什么跳过），右栏是数据序（选中节点的入参/产出快照、
 * 失败原因、工作流级变量）。这两块的状态全部来自 {@code useWorkflowRun}，
 * 视图层只负责选中态与开关。</p>
 *
 * <p>所有变量数据来自实例快照（{@code NodeInstance.input/output}），
 * 事件流本身不携带快照（见 {@code api/workflowSse} 的说明）。</p>
 */
import { computed, ref } from 'vue'
import Icon from '@/components/common/Icon.vue'
import type { VariableScope, WorkflowInstance, WorkflowNodeStatus } from '@/api/types'
import type { RunMark, RunTrackItem } from './useWorkflowRun'
import { fmtMs, fmtVar, isStructured } from './format'

const props = defineProps<{
  instance: WorkflowInstance | null
  track: RunTrackItem[]
  /** 与画布共用同一份运行标记，保证轨迹里的耗时与节点卡片完全一致。 */
  marks: Record<string, RunMark>
  /** 计数由 useWorkflowRun 统一给出，组件内不再二次统计。 */
  counts: { total: number; done: number; failed: number; skipped: number; running: number }
  running: boolean
  watching: boolean
  /** connecting=事件流建连中（本地实测约 0.8s），这一段既非实时也非轮询，不能混淆。 */
  mode: 'live' | 'connecting' | 'poll' | 'idle'
  elapsedMs: number
  selectedId: string | null
  /** 工作流级事件描述（WORKFLOW_FAILED 的兜底原因等）。 */
  workflowMessage?: string
  /**
   * 节点定义中的 config（nodeId → config）。用作「入参」的兜底展示。
   *
   * <p>为什么需要：{@code NodeInstance.input} 只包含 {@code mappingEvaluator} 解析出的
   * **输入映射**结果。画布上直接新建的 LLM 节点通常没有声明 inputs（提示词写在 config.prompt 里，
   * 由 {@code TemplateRenderer} 在节点内渲染），于是运行面板的「入参」永远是空的 ——
   * 而用户真正想看的就是「喂给模型的提示词到底是什么」。此处用 config 兜底补齐这一格。</p>
   */
  configs: Record<string, Record<string, unknown>>
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'close'): void
  (e: 'rerun'): void
  (e: 'stop-watch'): void
}>()

const STATUS_TEXT: Record<WorkflowNodeStatus, string> = {
  PENDING: '待执行',
  WAITING: '等待',
  RUNNING: '执行中',
  COMPLETED: '完成',
  FAILED: '失败',
  SKIPPED: '跳过',
}

const statusText = (s: WorkflowNodeStatus) => STATUS_TEXT[s] ?? s

/** 跟踪方式标签：连接中 ≠ 轮询，分开表述（否则会让人误以为事件流已经不可用）。 */
const MODE_TEXT: Record<string, string> = {
  live: '实时',
  connecting: '连接中',
  poll: '轮询',
  idle: '已停止跟踪',
}
const MODE_TITLE: Record<string, string> = {
  live: '已连接事件流，节点跃迁实时驱动',
  connecting: '正在建立事件流连接（本地实测约 0.8s）',
  poll: '事件流不可用，当前以 2s 间隔拉取实例快照推进',
  idle: '已停止跟踪（后台执行不受影响）',
}

const statusClass = (s?: WorkflowNodeStatus | string) =>
  ({
    RUNNING: 'wfr-badge--run',
    COMPLETED: 'wfr-badge--ok',
    FAILED: 'wfr-badge--err',
    SKIPPED: 'wfr-badge--off',
    PENDING: 'wfr-badge--off',
    WAITING: 'wfr-badge--warn',
    SUSPENDED: 'wfr-badge--warn',
  })[s ?? 'RUNNING'] ?? 'wfr-badge--run'

const dur = (it: RunTrackItem) => fmtMs(props.marks[it.nodeId]?.ms)

// ---------------- 选中节点的数据快照 ----------------

const selectedItem = computed(() => props.track.find((t) => t.nodeId === props.selectedId) ?? null)
const selectedNode = computed(() => {
  const id = props.selectedId
  if (!id) return null
  return props.instance?.nodeInstances?.[id] ?? null
})

const rows = (scope?: VariableScope) => Object.entries(scope?.data ?? {})
const inputs = computed(() => rows(selectedNode.value?.input))
const outputs = computed(() => rows(selectedNode.value?.output))
const globalVars = computed(() => rows(props.instance?.variables))
const showVars = ref(false)

/** 节点定义中的 config：仅在「入参」为空时作为兜底展示（见 props.configs 的说明）。 */
const configRows = computed(() => {
  const id = props.selectedId
  if (!id) return []
  const cfg = props.configs?.[id]
  if (!cfg) return []
  return Object.entries(cfg).filter(([, v]) => v !== null && v !== undefined && v !== '')
})

// ---------------- 抽屉高度 ----------------

const height = ref(252)
const dragging = ref(false)

function onDragStart(e: MouseEvent) {
  dragging.value = true
  const startY = e.clientY
  const startH = height.value
  const onMove = (ev: MouseEvent) => {
    const next = startH - (ev.clientY - startY)
    height.value = Math.min(Math.max(next, 132), Math.round(window.innerHeight * 0.66))
  }
  const onUp = () => {
    dragging.value = false
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}
</script>

<template>
  <section class="wfr" :class="{ 'wfr--dragging': dragging }" :style="{ height: height + 'px' }">
    <div class="wfr__grip" title="拖动调整高度" @mousedown.prevent="onDragStart" />

    <header class="wfr__head">
      <span class="wfr-badge" :class="statusClass(instance?.status)">
        {{ instance?.status ?? '提交中' }}
      </span>
      <span class="mono wfr__id">{{ instance?.instanceId?.slice(0, 12) ?? '—' }}</span>
      <span class="mono wfr__elapsed">{{ fmtMs(elapsedMs) }}</span>

      <!-- 只在「还在跑」时提示跟踪方式：终态实例谈事件流/轮询没有意义 -->
      <span
        v-if="running"
        class="wfr__mode"
        :class="{ 'wfr__mode--live': mode === 'live', 'wfr__mode--poll': mode === 'poll' }"
        :title="MODE_TITLE[mode]"
      >
        <Icon :name="mode === 'live' ? 'zap' : 'refresh'" :size="10" />
        {{ MODE_TEXT[mode] }}
      </span>

      <span class="wfr__stat mono">
        {{ counts.done }}/{{ counts.total }} 完成
        <template v-if="counts.failed"> · <em class="wfr__stat-err">{{ counts.failed }} 失败</em></template>
        <template v-if="counts.skipped"> · {{ counts.skipped }} 跳过</template>
      </span>

      <div class="wfr__acts">
        <button v-if="watching" class="wfr__btn" title="仅停止前端跟踪，后台执行仍在继续" @click="emit('stop-watch')">
          <Icon name="stop" :size="11" />停止监控
        </button>
        <button class="wfr__btn" :disabled="running" @click="emit('rerun')">
          <Icon name="play" :size="11" />重新运行
        </button>
        <button class="wfr__btn" title="关闭调试台" @click="emit('close')">
          <Icon name="x" :size="11" />
        </button>
      </div>
    </header>

    <p v-if="instance?.error || workflowMessage" class="wfr__fatal">
      <Icon name="warning" :size="12" />
      <span>{{ instance?.error || workflowMessage }}</span>
    </p>

    <div class="wfr__body">
      <!-- 左：执行轨迹 -->
      <div class="wfr__track-col">
        <div class="wfr__col-head">
          执行轨迹
          <span class="wfr__col-hint">点击查看该节点变量快照</span>
        </div>
        <!--
          轨迹自始就列出全部节点（含待执行），故「一个节点都还没动」不再表现为空列表。
          但这段窗口仍需明确提示：已提交、正在等后端进入第一个节点 —— 否则用户无法区分
          「在跑」和「卡住了」。注意它必须是独立分支，不能插进下面 ul/div 的 if-else 之间。
        -->
        <p v-if="track.length && running && !counts.done && !counts.running" class="wfr__pending">
          <Icon name="loader" :size="11" />
          已提交，等待第一个节点开始…（长时间无进展请查看后端日志）
        </p>
        <ul v-if="track.length" class="wfr__track">
          <li
            v-for="it in track"
            :key="it.nodeId"
            class="wfr__item"
            :class="[`wfr__item--${it.status}`, { 'wfr__item--sel': it.nodeId === selectedId }]"
            @click="emit('select', it.nodeId)"
          >
            <span class="wfr__dot" />
            <span class="wfr__name">{{ it.name }}</span>
            <span class="wfr__type mono">{{ it.type ?? '—' }}</span>
            <span class="wfr__s">{{ statusText(it.status) }}</span>
            <span class="mono wfr__dur">{{ dur(it) }}</span>
          </li>
        </ul>
        <div v-else class="wfr__empty">
          <Icon name="loader" :size="14" />
          <p>等待第一个节点开始…（若长时间无进展，请查看后端日志）</p>
        </div>
      </div>

      <!-- 右：选中节点详情 -->
      <div class="wfr__detail-col">
        <div class="wfr__col-head">
          节点详情
          <span v-if="selectedItem" class="mono wfr__col-id">{{ selectedItem.nodeId }}</span>
        </div>

        <div v-if="!selectedItem" class="wfr__empty">
          <Icon name="sliders" :size="16" />
          <p>从左侧轨迹或画布选中一个节点，查看它的入参与产出。</p>
        </div>

        <div v-else class="wfr__detail">
          <div class="wfr__detail-top">
            <span class="wfr__detail-name">{{ selectedItem.name }}</span>
            <span class="mono wfr__type">{{ selectedItem.type ?? '—' }}</span>
            <span class="wfr-badge" :class="statusClass(selectedItem.status)">
              {{ statusText(selectedItem.status) }}
            </span>
            <span class="mono wfr__dur">{{ dur(selectedItem) }}</span>
          </div>

          <p v-if="selectedItem.message" class="wfr__note">{{ selectedItem.message }}</p>
          <p v-if="selectedItem.error" class="wfr__err">
            <Icon name="warning" :size="12" />{{ selectedItem.error }}
          </p>

          <div class="wfr__section">
            <span class="wfr__section-label">入参</span>
            <p v-if="!inputs.length && !configRows.length" class="wfr__none">
              该节点没有解析到输入变量（未声明 inputs 时为空）。
            </p>
            <div v-else-if="inputs.length" class="wfr__kv">
              <div v-for="[k, v] in inputs" :key="k" class="wfr__kv-row">
                <span class="mono wfr__k">{{ k }}</span>
                <span class="wfr__v" :class="{ mono: isStructured(v) }">{{ fmtVar(v) }}</span>
              </div>
            </div>
            <template v-else>
              <p class="wfr__none wfr__none--hint">
                该节点未声明输入映射，以下为节点定义中的配置（提示词模板在此渲染）。
              </p>
              <div class="wfr__kv">
                <div v-for="[k, v] in configRows" :key="k" class="wfr__kv-row">
                  <span class="mono wfr__k">{{ k }}</span>
                  <span class="mono wfr__v">{{ fmtVar(v) }}</span>
                </div>
              </div>
            </template>
          </div>

          <div class="wfr__section">
            <span class="wfr__section-label">产出</span>
            <p v-if="!outputs.length" class="wfr__none">该节点未上报产出变量。</p>
            <div v-else class="wfr__kv">
              <div v-for="[k, v] in outputs" :key="k" class="wfr__kv-row">
                <span class="mono wfr__k">{{ k }}</span>
                <span class="wfr__v" :class="{ mono: isStructured(v) }">{{ fmtVar(v) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 工作流级变量：折叠在底部，避免与节点详情抢注意力 -->
    <div class="wfr__vars">
      <button class="wfr__vars-toggle" @click="showVars = !showVars">
        <Icon :name="showVars ? 'chevronDown' : 'chevronRight'" :size="11" />
        工作流变量作用域（{{ globalVars.length }}）
      </button>
      <div v-if="showVars" class="wfr__vars-body">
        <p v-if="!globalVars.length" class="wfr__none">作用域为空。</p>
        <div v-else class="wfr__kv wfr__kv--grid">
          <div v-for="[k, v] in globalVars" :key="k" class="wfr__kv-row">
            <span class="mono wfr__k">{{ k }}</span>
            <span class="wfr__v" :class="{ mono: isStructured(v) }">{{ fmtVar(v) }}</span>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.wfr {
  position: relative;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-top: 1px solid var(--border-strong);
  background: var(--bg-1);
  min-height: 132px;
}
.wfr--dragging {
  user-select: none;
}
.wfr__grip {
  position: absolute;
  top: -3px;
  left: 0;
  right: 0;
  height: 7px;
  cursor: row-resize;
  z-index: 2;
}
.wfr__grip:hover {
  background: var(--accent-dim);
}
.wfr__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--border);
  flex-wrap: wrap;
}
.wfr__id,
.wfr__elapsed {
  font-size: var(--fs-11);
  color: var(--text-3);
}
.wfr__elapsed {
  color: var(--text-1);
}
.wfr__mode {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: var(--fs-10);
  padding: 2px 7px;
  border-radius: var(--r-pill);
  border: 1px solid var(--border-strong);
  color: var(--text-3);
}
.wfr__mode--live {
  color: var(--accent-text);
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.wfr__mode--poll {
  color: var(--warn);
  border-color: var(--warn-dim);
  background: var(--warn-dim);
}
.wfr__stat {
  font-size: var(--fs-11);
  color: var(--text-2);
}
.wfr__stat-err {
  color: var(--danger-text);
  font-style: normal;
}
.wfr__acts {
  margin-left: auto;
  display: flex;
  gap: 6px;
}
.wfr__btn {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 9px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-0);
  color: var(--text-1);
  font-size: var(--fs-11);
  cursor: pointer;
}
.wfr__btn:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent-text);
}
.wfr__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.wfr__fatal {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0;
  padding: 6px 12px;
  background: var(--danger-dim);
  color: var(--danger-text);
  font-size: var(--fs-11);
  line-height: 1.6;
  flex-shrink: 0;
}
.wfr__body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 400px);
}
.wfr__track-col,
.wfr__detail-col {
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.wfr__detail-col {
  border-left: 1px solid var(--border);
}
.wfr__col-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  font-size: var(--fs-11);
  color: var(--text-3);
  flex-shrink: 0;
}
.wfr__col-hint,
.wfr__col-id {
  font-size: var(--fs-10);
  color: var(--text-3);
  opacity: 0.8;
}
.wfr__col-id {
  margin-left: auto;
}
.wfr__track {
  list-style: none;
  margin: 0;
  padding: 0 8px 10px;
  overflow-y: auto;
  flex: 1;
  min-height: 0;
}
.wfr__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 8px;
  border-radius: var(--r-6);
  cursor: pointer;
  border-left: 2px solid transparent;
}
.wfr__item:hover {
  background: var(--bg-2);
}
.wfr__item--sel {
  background: var(--accent-dim);
  border-left-color: var(--accent);
}
.wfr__dot {
  width: 7px;
  height: 7px;
  border-radius: 999px;
  background: var(--text-3);
  flex-shrink: 0;
}
.wfr__item--RUNNING .wfr__dot {
  background: var(--accent);
  animation: wfr-pulse 1.2s ease-in-out infinite;
}
.wfr__item--COMPLETED .wfr__dot {
  background: #2ea043;
}
.wfr__item--FAILED .wfr__dot {
  background: var(--danger);
}
.wfr__item--SKIPPED .wfr__dot {
  background: var(--text-3);
}
@keyframes wfr-pulse {
  50% {
    opacity: 0.3;
  }
}
.wfr__name {
  font-size: var(--fs-12);
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wfr__item--FAILED .wfr__name {
  color: var(--danger-text);
}
.wfr__type {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--text-3);
  padding: 1px 5px;
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  flex-shrink: 0;
}
.wfr__s {
  margin-left: auto;
  font-size: var(--fs-10);
  color: var(--text-3);
  flex-shrink: 0;
}
.wfr__dur {
  font-size: var(--fs-11);
  color: var(--text-2);
  min-width: 46px;
  text-align: right;
  flex-shrink: 0;
}
.wfr__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--text-3);
  padding: 14px;
  text-align: center;
}
.wfr__empty p {
  margin: 0;
  font-size: var(--fs-11);
  line-height: 1.7;
}
.wfr__detail {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 12px 10px;
}
.wfr__detail-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.wfr__detail-name {
  font-size: var(--fs-13);
  color: var(--text-0);
  font-weight: 500;
}
.wfr__note {
  margin: 0 0 8px;
  font-size: var(--fs-11);
  color: var(--text-2);
  line-height: 1.6;
}
.wfr__err {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 0 0 8px;
  padding: 6px 8px;
  border-radius: var(--r-6);
  background: var(--danger-dim);
  color: var(--danger-text);
  font-size: var(--fs-11);
  line-height: 1.6;
  word-break: break-word;
}
.wfr__section {
  margin-bottom: 10px;
}
.wfr__section-label {
  display: block;
  font-size: var(--fs-10);
  letter-spacing: 0.1em;
  color: var(--text-3);
  margin-bottom: 4px;
}
.wfr__none {
  margin: 0;
  font-size: var(--fs-11);
  color: var(--text-3);
  line-height: 1.6;
}
.wfr__none--hint {
  margin-bottom: 4px;
  opacity: 0.85;
}
/* 已提交但尚无节点进入的窗口提示：独立于轨迹列表的存在与否 */
.wfr__pending {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: 0;
  padding: 4px 12px 0;
  font-size: var(--fs-10);
  color: var(--text-3);
  flex-shrink: 0;
}
.wfr__kv-row {
  display: grid;
  grid-template-columns: minmax(80px, 130px) minmax(0, 1fr);
  gap: 8px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--border);
}
.wfr__k {
  font-size: var(--fs-11);
  color: var(--accent-text);
  word-break: break-all;
}
.wfr__v {
  font-size: var(--fs-11);
  color: var(--text-1);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 132px;
  overflow: auto;
}
.wfr__vars {
  border-top: 1px solid var(--border);
  flex-shrink: 0;
  max-height: 40%;
  overflow: auto;
}
.wfr__vars-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
  padding: 6px 12px;
  border: 0;
  background: transparent;
  color: var(--text-2);
  font-size: var(--fs-11);
  cursor: pointer;
  text-align: left;
}
.wfr__vars-toggle:hover {
  color: var(--accent-text);
}
.wfr__vars-body {
  padding: 0 12px 10px;
}
.wfr__kv--grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 0 16px;
}

/* ---------- 徽标（与 WorkflowView 内的 .wf-badge 视觉一致，组件内自持避免跨组件耦合） ---------- */
.wfr-badge {
  flex-shrink: 0;
  font-size: var(--fs-10);
  padding: 2px 7px;
  border-radius: 999px;
  border: 1px solid transparent;
}
.wfr-badge--ok {
  color: #2ea043;
  border-color: #2ea04344;
  background: #2ea0431a;
}
.wfr-badge--off {
  color: var(--text-3);
  border-color: var(--border-strong);
}
.wfr-badge--run {
  color: var(--accent);
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.wfr-badge--err {
  color: var(--danger);
  border-color: var(--danger-dim);
  background: var(--danger-dim);
}
.wfr-badge--warn {
  color: var(--warn);
  border-color: var(--warn-dim);
  background: var(--warn-dim);
}
</style>
