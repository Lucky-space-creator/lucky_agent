<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatItem } from '@/stores/chat'
import { renderMarkdown } from '@/utils/markdown'
import { useChatStore } from '@/stores/chat'
import PlanCard from './PlanCard.vue'
import ToolCallCard from './ToolCallCard.vue'
import AskCard from './AskCard.vue'
import Icon from '@/components/common/Icon.vue'

const props = defineProps<{ item: ChatItem }>()

const chat = useChatStore()

const blocks = computed(() => renderMarkdown(props.item.content || ''))
const showThoughts = computed(() => props.item.thoughts.length > 0)
/** 思考中加载：运行中且正文尚未开始输出时展示「思考中…」指示。 */
const showThinkingLoading = computed(() => props.item.status === 'running' && !props.item.content)
const copied = ref(false)

/** 时间显示（本地时区 HH:MM）。 */
const timeText = computed(() => {
  if (!props.item.ts) return ''
  const d = new Date(props.item.ts)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`
})

/** 复制消息内容到剪贴板。 */
async function copyContent() {
  try {
    await navigator.clipboard.writeText(props.item.content || '')
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // 剪贴板不可用时静默
  }
}

function onConfirm(allow: boolean) {
  chat.confirmAsk(props.item, allow)
}
</script>

<template>
  <article class="msg" :class="`msg--${item.role}`">
    <div class="msg__body">
      <!-- 用户消息 -->
      <template v-if="item.role === 'user'">
        <p class="msg__user-text">{{ item.content }}</p>
        <div class="msg__user-meta">
          <button class="msg__copy" :title="copied ? '已复制' : '复制'" @click="copyContent">
            <Icon :name="copied ? 'check' : 'copy'" :size="11" />
          </button>
          <span v-if="timeText" class="msg__time mono">{{ timeText }}</span>
        </div>
      </template>

      <!-- 助手消息 -->
      <template v-else>
        <!-- 思考在前：思考过程默认展开、展示在正文之前 -->
        <details v-if="showThoughts" open class="msg__thoughts expand">
          <summary class="mono msg__thoughts-sum press">思考过程（{{ item.thoughts.length }}）</summary>
          <p v-for="(t, i) in item.thoughts" :key="i" class="msg__thought">{{ t }}</p>
        </details>

        <!-- 思考中加载：运行中、有思考但正文尚未开始输出时的加载指示 -->
        <div v-if="showThinkingLoading" class="msg__thinking-loading">
          <span class="msg__thinking-dot" />
          <span>思考中…</span>
        </div>

        <!-- 正文内容（流式增量实时渲染） -->
        <div class="msg__blocks">
          <template v-for="(b, i) in blocks" :key="i">
            <pre v-if="b.type === 'code'" class="msg__code">{{ b.content }}</pre>
            <div v-else class="msg__text" v-html="b.content" />
          </template>
        </div>

        <AskCard v-if="item.ask" :ask="item.ask" @confirm="onConfirm" />

        <PlanCard v-if="item.plan && item.plan.length > 0" :plan="item.plan" />

        <div v-if="item.toolCalls.length > 0" class="msg__tools">
          <ToolCallCard v-for="call in item.toolCalls" :key="call.id" :call="call" />
        </div>

        <div v-if="item.error" class="msg__error">
          <Icon name="x" :size="13" />
          <span>{{ item.error }}</span>
        </div>

        <div v-if="item.model || item.tokenUsed" class="msg__meta mono">
          <span v-if="item.model">{{ item.model }}</span>
          <span v-if="item.tokenUsed">· {{ item.tokenUsed }} tokens</span>
        </div>
      </template>
    </div>
  </article>
</template>

<style scoped>
.msg {
  padding: 12px 0;
}
.msg__body {
  min-width: 0;
}
.msg--user .msg__body {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.msg__user-text {
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  border-radius: 20px;
  padding: 11px 16px;
  font-size: var(--fs-14);
  color: var(--text-0);
  line-height: var(--lh-body);
  white-space: pre-wrap;
  word-break: break-word;
  display: inline-block;
  max-width: 88%;
}
.msg__user-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 5px;
  padding-right: 4px;
}
.msg__copy {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  background: none;
  border: none;
  color: var(--text-3);
  font-size: 11px;
  border-radius: var(--r-4);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.msg__copy:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.msg__time {
  font-size: 11px;
  color: var(--text-3);
}
.msg__blocks {
  font-size: var(--fs-14);
  line-height: var(--lh-body);
  color: var(--text-1);
}
.msg__text {
  margin: 4px 0;
  word-break: break-word;
  color: var(--text-1);
}
.msg__text :deep(h3),
.msg__text :deep(h4) {
  font-size: var(--fs-16);
  color: var(--text-0);
  margin: 12px 0 6px;
}
.msg__text :deep(h3):first-child,
.msg__text :deep(h4):first-child {
  margin-top: 0;
}
.msg__text :deep(ul),
.msg__text :deep(ol) {
  padding-left: 20px;
  margin: 6px 0;
}
.msg__text :deep(code) {
  font-family: var(--font-mono);
  font-size: 0.9em;
  background: var(--bg-2);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  padding: 1px 5px;
}
.msg__text :deep(a) {
  color: var(--accent-text);
}
.msg__code {
  margin: 8px 0;
  color: var(--text-0);
}
.msg__tools {
  margin-top: 6px;
}
.msg__thinking-loading {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 8px 0 4px;
  font-size: var(--fs-12);
  color: var(--text-3);
}
.msg__thinking-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--accent);
  animation: thinkingPulse 1.1s ease-in-out infinite;
}
@keyframes thinkingPulse {
  0%,
  100% {
    opacity: 0.25;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1.15);
  }
}
.msg__thoughts {
  margin-top: 8px;
  border-top: 1px dashed var(--border);
  padding-top: 6px;
}
.msg__thoughts-sum {
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--text-3);
  cursor: pointer;
  user-select: none;
}
.msg__thought {
  margin-top: 6px;
  font-size: var(--fs-13);
  color: var(--text-3);
  border-left: 2px solid var(--border);
  padding-left: 10px;
  white-space: pre-wrap;
}
.msg__error {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  background: var(--danger-dim);
  border: 1px solid rgba(224, 84, 84, 0.35);
  color: var(--danger-text);
  border-radius: var(--r-6);
  padding: 8px 12px;
  font-size: var(--fs-13);
}
.msg__meta {
  margin-top: 8px;
  font-size: 11px;
  color: var(--text-3);
  display: flex;
  gap: 6px;
}
</style>
