<script setup lang="ts">
/**
 * 画布节点渲染器（全部节点类型共用）。
 *
 * <p>vue-flow 通过 node.type 选择渲染器，因此 7 种节点类型统一注册到本组件，
 * 视觉差异由 type 决定，而不是各写一个组件 —— 避免 7 份近似模板各自漂移。</p>
 */
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import type { NodeProps } from '@vue-flow/core'
import Icon from '@/components/common/Icon.vue'

const props = defineProps<NodeProps<{ label?: string; subtitle?: string }>>()

/** type → 图标与色调；label 由后端元数据下发后写入 data.label，缺失时回落到此处。 */
const VIEW: Record<string, { icon: string; tone: string; label: string }> = {
  START: { icon: 'play', tone: 'ok', label: '开始' },
  END: { icon: 'check', tone: 'ok', label: '结束' },
  LLM: { icon: 'sparkles', tone: 'accent', label: 'LLM 生成' },
  TOOL: { icon: 'terminal', tone: 'teal', label: '工具调用' },
  CONDITION: { icon: 'gitBranch', tone: 'warn', label: '条件判断' },
  CODE: { icon: 'command', tone: 'violet', label: '命令执行' },
  SUBFLOW: { icon: 'layers', tone: 'accent', label: '子流程' },
}

const view = computed(() => VIEW[props.type] ?? { icon: 'box', tone: 'muted', label: props.type })
const label = computed(() => props.data?.label || view.value.label)
const isStart = computed(() => props.type === 'START')
const isEnd = computed(() => props.type === 'END')

/** 运行态标记：由运行实例回填（data.runStatus = RUNNING/SUCCESS/FAILED）。 */
const runStatus = computed(() => (props.data as any)?.runStatus as string | undefined)
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
      <span class="wfn__icon"><Icon :name="view.icon" :size="12" /></span>
      <span class="wfn__label">{{ label }}</span>
      <span v-if="runStatus" class="wfn__dot" />
    </div>
    <div class="wfn__type mono">{{ type }}</div>
  </div>
</template>

<style scoped>
.wfn {
  --tone: var(--text-2);
  min-width: 170px;
  max-width: 240px;
  padding: 8px 10px;
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
.wfn__dot {
  margin-left: auto;
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: var(--accent);
  flex-shrink: 0;
}
.wfn__type {
  margin-top: 3px;
  font-size: var(--fs-10);
  letter-spacing: 0.08em;
  color: var(--text-3);
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
