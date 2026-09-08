<script setup lang="ts">
import { computed } from 'vue'
import type { SessionMetrics } from '@/api/types'
import Icon from '@/components/common/Icon.vue'
import { useModelStore } from '@/stores/model'

const props = defineProps<{ metrics: SessionMetrics | null; running: boolean }>()

const model = useModelStore()

/** 模型名：无会话指标时回退当前主端点，保证未开对话也有内容。 */
const modelName = computed(() => props.metrics?.lastModel ?? model.primary?.modelName ?? '')

const tokenText = computed(() => {
  const m = props.metrics
  if (!m || m.tokenUsed <= 0) return '—'
  return m.tokenUsed.toLocaleString('en-US')
})

/** 时长格式化：<1min 显示秒，否则 mm:ss 或 h:mm:ss。 */
const elapsedText = computed(() => {
  const m = props.metrics
  const ms = m?.elapsedMs ?? 0
  if (!ms) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  const mm = Math.floor(s / 60)
  const ss = s % 60
  if (mm < 60) return `${mm}m ${ss}s`
  const h = Math.floor(mm / 60)
  return `${h}h ${mm % 60}m`
})

const windowText = computed(() => {
  const m = props.metrics
  if (!m || !m.contextWindow) return '—'
  const pct = Math.min(100, Math.round((m.windowUsage ?? 0) * 100))
  return `${pct}%`
})

const cacheText = computed(() => {
  const m = props.metrics
  if (!m) return '—'
  const pct = Math.round((m.cacheHitRate ?? 0) * 100)
  return `${pct}%`
})

/** 工具成功率（0~100%），无工具样本返回 —。 */
const toolSuccessText = computed(() => {
  const m = props.metrics
  if (!m) return '—'
  const ok = m.toolOk ?? 0
  const fail = m.toolFail ?? 0
  const sum = ok + fail
  if (!sum) return '—'
  return `${Math.round((ok / sum) * 100)}%`
})

const toolEntries = computed(() => {
  const m = props.metrics
  if (!m || !m.toolCounts) return []
  return Object.entries(m.toolCounts)
    .sort((a, b) => (b[1] as number) - (a[1] as number))
})

const subAgentEntries = computed(() => {
  const m = props.metrics
  if (!m || !m.subAgents) return []
  return Object.entries(m.subAgents).map(([id, v]) => ({
    id,
    done: v.done ?? 0,
    total: v.total ?? 0,
    status: v.status ?? 'pending',
    pct: v.total > 0 ? Math.round((v.done / v.total) * 100) : 0,
  }))
})
</script>

<template>
  <div class="tp" :class="{ 'tp--live': running }">
    <div class="tp__head">
      <span class="tp__dot" />
      <span class="tp__title">监控指标</span>
      <span v-if="running" class="tp__live mono">实时</span>
    </div>

    <!-- 会话级核心指标（未开对话时显示 0 / — 基础框架，不空屏） -->
    <div class="tp__grid">
      <div class="tp__cell">
        <span class="tp__num mono">{{ tokenText }}</span>
        <span class="tp__lbl mono">TOKENS 总量</span>
      </div>
      <div class="tp__cell">
        <span class="tp__num mono">{{ metrics?.modelCalls ?? 0 }}</span>
        <span class="tp__lbl mono">推理轮次</span>
      </div>
      <div class="tp__cell">
        <span class="tp__num mono">{{ elapsedText }}</span>
        <span class="tp__lbl mono">运行时长</span>
      </div>
      <div class="tp__cell">
        <span class="tp__num mono">{{ cacheText }}</span>
        <span class="tp__lbl mono">缓存命中</span>
      </div>
    </div>

    <!-- 输入 / 输出 token 与上下文占用 -->
    <div class="tp__mini-grid">
      <div class="tp__mini">
        <span class="tp__mini-num mono">{{ metrics?.inputTokens?.toLocaleString('en-US') ?? 0 }}</span>
        <span class="tp__lbl mono">输入</span>
      </div>
      <div class="tp__mini">
        <span class="tp__mini-num mono">{{ metrics?.outputTokens?.toLocaleString('en-US') ?? 0 }}</span>
        <span class="tp__lbl mono">输出</span>
      </div>
      <div class="tp__mini">
        <span class="tp__mini-num mono">{{ windowText }}</span>
        <span class="tp__lbl mono">上下文</span>
      </div>
      <div class="tp__mini" :class="{ 'tp__mini--warn': (metrics?.errors ?? 0) > 0 }">
        <span class="tp__mini-num mono">{{ metrics?.errors ?? 0 }}</span>
        <span class="tp__lbl mono">错误</span>
      </div>
    </div>

    <!-- 模型名：无会话指标时显示当前主端点 -->
    <div v-if="modelName" class="tp__model mono">
      <Icon name="sparkles" :size="11" /> {{ modelName }}
    </div>

    <!-- 工具 / Skill / MCP / 成功率 -->
    <div class="tp__row">
      <span class="tp__tag mono">工具 {{ toolEntries.reduce((s, [, n]) => s + (n as number), 0) }}</span>
      <span class="tp__tag mono">Skill {{ metrics?.skillInvokes ?? 0 }}</span>
      <span class="tp__tag mono">MCP {{ metrics?.mcpInvokes ?? 0 }}</span>
      <span class="tp__tag mono">成功率 {{ toolSuccessText }}</span>
    </div>

    <!-- 工具明细 -->
    <div v-if="toolEntries.length" class="tp__tools">
      <div v-for="[tool, count] in toolEntries" :key="tool" class="tp__tool">
        <span class="tp__tool-name mono">{{ tool }}</span>
        <span class="tp__tool-count mono">{{ count }}</span>
      </div>
    </div>

    <!-- 子代理进度 -->
    <div v-if="subAgentEntries.length" class="tp__subs">
      <div v-for="s in subAgentEntries" :key="s.id" class="tp__sub">
        <div class="tp__sub-top">
          <span class="tp__sub-id mono">{{ s.id }}</span>
          <span class="tp__sub-pct mono">{{ s.done }}/{{ s.total }}</span>
        </div>
        <div class="tp__bar">
          <div class="tp__bar-fill" :style="{ width: s.pct + '%' }" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tp {
  border: 1px solid var(--border);
  border-radius: var(--r-10);
  background: var(--bg-1);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.tp__head {
  display: flex;
  align-items: center;
  gap: 7px;
}
.tp__dot {
  width: 6px;
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--text-3);
}
.tp--live .tp__dot {
  background: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-dim);
  animation: tp-pulse 1.6s var(--ease) infinite;
}
@keyframes tp-pulse {
  0%, 100% { opacity: 1; }
}
.tp__title {
  font-size: var(--fs-12);
  color: var(--text-1);
  font-weight: 600;
  font-family: var(--font-mono);
  letter-spacing: 0.04em;
}
.tp__live {
  margin-left: auto;
  font-size: 10px;
  letter-spacing: 0.1em;
  color: var(--accent-text);
}
.tp__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.tp__mini-grid {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1fr;
  gap: 6px;
}
.tp__mini {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--bg-2);
  border: 1px solid var(--border-faint);
  border-radius: var(--r-6);
  padding: 6px 8px;
}
.tp__mini--warn .tp__mini-num {
  color: var(--danger-text);
}
.tp__mini-num {
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-1);
  line-height: 1.1;
}
.tp__cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  background: var(--bg-2);
  border: 1px solid var(--border-faint);
  border-radius: var(--r-6);
  padding: 8px 10px;
}
.tp__num {
  font-size: var(--fs-18);
  font-weight: 600;
  color: var(--text-0);
  line-height: 1.1;
}
.tp__lbl {
  font-size: 9px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.tp__model {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-12);
  color: var(--teal);
  background: var(--teal-dim);
  border-radius: var(--r-6);
  padding: 5px 9px;
}
.tp__model svg {
  color: var(--teal);
}
.tp__row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tp__tag {
  font-size: 10px;
  color: var(--text-2);
  background: var(--bg-2);
  border: 1px solid var(--border-faint);
  border-radius: var(--r-pill);
  padding: 3px 8px;
}
.tp__tools {
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.tp__tool {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: var(--fs-12);
  padding: 3px 8px;
  border-radius: var(--r-4);
  background: var(--bg-2);
}
.tp__tool-name {
  color: var( --text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tp__tool-count {
  color: var(--text-0);
  font-weight: 600;
}
.tp__subs {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.tp__sub-top {
  display: flex;
  justify-content: space-between;
  font-size: var(--fs-11);
  color: var(--text-2);
  margin-bottom: 3px;
}
.tp__sub-id {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tp__bar {
  height: 4px;
  border-radius: var(--r-pill);
  background: var(--bg-3);
  overflow: hidden;
}
.tp__bar-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--r-pill);
  transition: width var(--dur-med) var(--ease);
}
</style>
