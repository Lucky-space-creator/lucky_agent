<script setup lang="ts">
/**
 * 画布节点渲染器（全部节点类型共用）。
 *
 * <p>vue-flow 通过 node.type 选择渲染器，因此 7 种节点类型统一注册到本组件，
 * 视觉差异由 type 决定，而不是各写一个组件 —— 避免 7 份近似模板各自漂移。</p>
 *
 * <p><b>运行态来自注入的标记表</b>（{@link WF_RUN_MARKS}），不写进 node.data：
 * 写 data 需要整体替换 nodes 数组，会打断拖拽且每次刷新重渲染整块画布。
 * 详见 graph.ts 中该注入键的说明。</p>
 */
import { computed, inject } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import Icon from '@/components/common/Icon.vue'
import { WF_RUN_MARKS } from './graph'
import { configSummary, fmtMs, nodeView } from './format'

const props = defineProps<NodeProps<{ label?: string; config?: Record<string, any> }>>()

const STATUS_TEXT: Record<string, string> = {
  PENDING: '待执行',
  WAITING: '等待',
  RUNNING: '执行中',
  COMPLETED: '完成',
  FAILED: '失败',
  SKIPPED: '跳过',
}

const view = computed(() => nodeView(props.type))
const label = computed(() => props.data?.label || view.value.label)
const isStart = computed(() => props.type === 'START')
const isEnd = computed(() => props.type === 'END')
const summary = computed(() => configSummary(props.type, props.data?.config))

const marks = inject(WF_RUN_MARKS, null)
const mark = computed(() => marks?.value?.[props.id] ?? null)
const runStatus = computed(() => mark.value?.status)
const statusText = computed(() => (runStatus.value ? (STATUS_TEXT[runStatus.value] ?? runStatus.value) : ''))
const runMs = computed(() => mark.value?.ms)
</script>

<template>
  <div
    class="wfn"
    :class="[`wfn--${view.tone}`, { 'wfn--sel': selected, [`wfn--run-${runStatus}`]: !!runStatus }]"
  >
    <!-- 入口节点不接上游，出口节点不接下游 -->
    <Handle v-if="!isStart" type="target" :position="Position.Left" />
    <Handle v-if="!isEnd" type="source" :position="Position.Right" />

    <div class="wfn__top">
      <span class="wfn__icon"><Icon :name="view.icon" :size="13" /></span>
      <span class="wfn__label">{{ label }}</span>
      <span v-if="runStatus" class="wfn__chip" :class="`wfn__chip--${runStatus}`">{{ statusText }}</span>
    </div>

    <div class="wfn__meta">
      <span class="wfn__type mono">{{ type }}</span>
      <span v-if="runMs !== undefined" class="wfn__ms mono">{{ fmtMs(runMs) }}</span>
    </div>

    <div v-if="summary" class="wfn__sum" :title="summary">{{ summary }}</div>
  </div>
</template>

<style scoped>
.wfn {
  --tone: var(--text-2);
  width: 186px;
  padding: 7px 9px;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-left: 3px solid var(--tone);
  border-radius: var(--r-8);
  box-shadow: var(--shadow-1);
  cursor: grab;
  transition: box-shadow var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.wfn:hover {
  box-shadow: var(--shadow-2);
}
.wfn--sel {
  border-color: var(--accent);
  border-left-color: var(--accent);
  box-shadow: var(--glow-accent);
}
.wfn--ok {
  --tone: #2ea043;
}
.wfn--accent {
  --tone: var(--accent);
}
.wfn--teal {
  --tone: var(--teal);
}
.wfn--warn {
  --tone: var(--warn);
}
.wfn--violet {
  --tone: #8b5cf6;
}
.wfn--muted {
  --tone: var(--text-3);
}
.wfn__top {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.wfn__icon {
  display: flex;
  color: var(--tone);
  flex-shrink: 0;
}
.wfn__label {
  font-size: var(--fs-13);
  color: var(--text-0);
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wfn__chip {
  margin-left: auto;
  flex-shrink: 0;
  font-size: 9px;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  border: 1px solid var(--border-strong);
  color: var(--text-3);
  background: var(--bg-1);
}
.wfn__chip--RUNNING {
  color: var(--accent-text);
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.wfn__chip--COMPLETED {
  color: #2ea043;
  border-color: #2ea04344;
  background: #2ea0431a;
}
.wfn__chip--FAILED {
  color: var(--danger-text);
  border-color: var(--danger-dim);
  background: var(--danger-dim);
}
.wfn__meta {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 3px;
}
.wfn__type {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--text-3);
}
.wfn__ms {
  margin-left: auto;
  font-size: 9px;
  color: var(--text-3);
}
.wfn__sum {
  margin-top: 4px;
  padding-top: 4px;
  border-top: 1px dashed var(--border);
  font-size: var(--fs-10);
  font-family: var(--font-mono);
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wfn--run-RUNNING {
  border-color: var(--accent);
  animation: wfn-pulse 1.4s ease-in-out infinite;
}
.wfn--run-COMPLETED {
  border-color: #2ea043;
}
.wfn--run-SKIPPED {
  opacity: 0.55;
}
.wfn--run-FAILED {
  border-color: var(--danger);
}
@keyframes wfn-pulse {
  50% {
    box-shadow: var(--glow-accent);
  }
}
</style>
