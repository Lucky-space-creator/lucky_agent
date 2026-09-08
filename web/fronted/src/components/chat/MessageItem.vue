<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatItem, ToolCallView } from '@/stores/chat'
import { renderMarkdown } from '@/utils/markdown'
import { useChatStore } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'
import PlanCard from './PlanCard.vue'
import ToolCallCard from './ToolCallCard.vue'
import AskCard from './AskCard.vue'
import CodeBlock from './CodeBlock.vue'
import Icon from '@/components/common/Icon.vue'

const props = defineProps<{ item: ChatItem }>()

const chat = useChatStore()
const ui = useUiStore()

const blocks = computed(() => renderMarkdown(props.item.content || ''))
const showThoughts = computed(() => props.item.thoughts.length > 0)
/** 思考中加载：运行中且正文尚未开始输出时展示「思考中…」指示。 */
const showThinkingLoading = computed(() => props.item.status === 'running' && !props.item.content)
const copied = ref(false)

/* ---------- 思考过程：默认收起，展开后灰色字体滚动查看全部 ---------- */

/** 思考区是否展开。 */
const thoughtsOpen = ref(false)

function toggleThoughts() {
  thoughtsOpen.value = !thoughtsOpen.value
}

/* ---------- 工具调用折叠窗口（Skill/MCP/命令等非文件工具） ---------- */

/** 工具调用区是否展开。 */
const toolsOpen = ref(false)
/** 执行中的工具调用数量（折叠时以脉冲点提示进度）。 */
const runningOtherCount = computed(() => otherCalls.value.filter((c) => c.status === 'running').length)


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

/* ---------- 消息级回溯：撤销该条消息修改过的文件 ---------- */

/** 该消息是否可回溯（有文件检查点且未执行过回溯）。 */
const canRollback = computed(() => !!props.item.checkpointIds?.length && !props.item.rolledBack)
const rollingBack = ref(false)
const rollbackDone = ref(false)

/** 撤销反馈文本（成功提示 / 失败提示 / 默认）。 */
const rollbackLabel = computed(() => {
  if (rollingBack.value) return '撤销中…'
  if (rollbackDone.value) return '已撤销'
  return '撤销文件修改'
})

async function onRollback() {
  if (!canRollback.value || rollingBack.value) return
  rollingBack.value = true
  try {
    const n = await chat.rollbackMessage(props.item)
    rollbackDone.value = n > 0
  } catch {
    rollbackDone.value = false
  } finally {
    rollingBack.value = false
    if (rollbackDone.value) {
      setTimeout(() => (rollbackDone.value = false), 1800)
    }
  }
}

function onConfirm(allow: boolean) {
  chat.confirmAsk(props.item, allow)
}

/* ---------- 文件操作：回复末尾「变动的文件」总览（列出指令，点击定位） ---------- */

/** 文件类工具（write/mkdir/rename/delete 等）：不再逐条展示卡片，收敛为搜索图标轮跳。 */
function isFileTool(c: ToolCallView): boolean {
  return (c.tool || '').startsWith('file.')
}

/** 从工具调用入参中解析目标文件路径（兼容 path 顶层 / args.path 嵌套两种结构）。 */
function filePathOf(c: ToolCallView): string {
  const args = (c.args ?? {}) as Record<string, any>
  return String(args?.path || args?.args?.path || '')
}

/** 本消息内的文件操作调用。 */
const fileCalls = computed(() => props.item.toolCalls.filter(isFileTool))
/** 其余工具调用（Skill/MCP/命令等），保持独立工具卡片展示。 */
const otherCalls = computed(() => props.item.toolCalls.filter((c) => !isFileTool(c)))

/** 工具名 → 中文操作指令（文件操作清单展示用）。 */
function opLabelOf(tool: string): string {
  return (
    {
      'file.write': '写入',
      'file.mkdir': '创建目录',
      'file.rename': '重命名',
      'file.delete': '删除',
      'file.read': '读取',
      'file.list': '列出',
      'file.stat': '状态',
    }[tool] ?? tool.replace(/^file\./, '') ?? '操作'
  )
}

/** 变动的文件总览：按路径聚合本消息的文件操作，列出指令与状态。 */
const fileSummary = computed(() => {
  const map = new Map<string, { ops: string[]; hasFail: boolean }>()
  for (const c of fileCalls.value) {
    const p = filePathOf(c)
    if (!p) continue
    const e = map.get(p) ?? { ops: [], hasFail: false }
    e.ops.push(opLabelOf(c.tool || ''))
    if (c.status === 'done' && !c.ok) e.hasFail = true
    map.set(p, e)
  }
  return [...map.entries()].map(([path, v]) => ({
    path,
    opLabel: [...new Set(v.ops)].join('、'),
    hasFail: v.hasFail,
  }))
})

/** 点击总览项：在右侧「项目文件」面板定位该文件。 */
function openFile(path: string) {
  if (path) ui.openFilePanel(path)
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
        <!-- 思考在前：默认收起仅显示摘要行，展开后灰色字体滚动查看全部 -->
        <div v-if="showThoughts" class="msg__thoughts">
          <button class="msg__thoughts-sum press" @click="toggleThoughts">
            <span class="mono">思考过程（{{ item.thoughts.length }}）</span>
            <Icon :name="thoughtsOpen ? 'chevronDown' : 'chevronRight'" :size="11" />
          </button>
          <div v-if="thoughtsOpen" class="msg__thoughts-box">
            <p v-for="(t, i) in item.thoughts" :key="i" class="msg__thought">{{ t }}</p>
          </div>
        </div>

        <!-- 思考中加载：运行中、有思考但正文尚未开始输出时的加载指示 -->
        <div v-if="showThinkingLoading" class="msg__thinking-loading">
          <span class="msg__thinking-dot" />
          <span>思考中…</span>
        </div>

        <!-- 正文内容（流式增量实时渲染） -->
        <div class="msg__blocks">
          <template v-for="(b, i) in blocks" :key="i">
            <CodeBlock v-if="b.type === 'code'" :code="b.content" :lang="b.lang" />
            <div v-else class="msg__text" v-html="b.content" />
          </template>
        </div>

        <AskCard v-if="item.ask" :ask="item.ask" @confirm="onConfirm" />

        <PlanCard v-if="item.plan && item.plan.length > 0" :plan="item.plan" />

        <div v-if="item.toolCalls.length > 0" class="msg__tools">
          <!-- 工具调用（Skill/MCP/命令等）：折叠窗口，点击展开/收起 -->
          <button
            v-if="otherCalls.length"
            class="msg__toolops"
            :class="{ 'msg__toolops--open': toolsOpen }"
            @click="toolsOpen = !toolsOpen"
          >
            <Icon name="terminal" :size="13" />
            <span class="msg__toolops-label mono">工具调用</span>
            <span v-if="runningOtherCount" class="msg__toolops-run" :title="`${runningOtherCount} 个执行中`" />
            <span class="msg__toolops-pos mono">{{ otherCalls.length }}</span>
            <Icon :name="toolsOpen ? 'chevronDown' : 'chevronRight'" :size="11" />
          </button>
          <!-- 工具卡片列表：折叠时隐藏 -->
          <div v-if="toolsOpen" class="msg__toolops-list">
            <ToolCallCard v-for="call in otherCalls" :key="call.id" :call="call" />
          </div>
        </div>

        <!-- 回复末尾：变动的文件总览（列出操作指令，点击在右侧文件面板定位） -->
        <div v-if="fileSummary.length" class="msg__files">
          <div class="msg__files-head">
            <Icon name="file" :size="12" />
            <span class="msg__files-title mono">变动的文件（{{ fileSummary.length }}）</span>
            <span class="msg__files-sub text-3">点击定位</span>
          </div>
          <button
            v-for="f in fileSummary"
            :key="f.path"
            class="msg__file"
            :class="{ 'msg__file--err': f.hasFail }"
            :title="f.hasFail ? '含失败操作' : '在文件面板中定位'"
            @click="openFile(f.path)"
          >
            <span class="msg__file-ops mono">{{ f.opLabel }}</span>
            <span class="msg__file-path mono ellipsis">{{ f.path }}</span>
            <span class="msg__file-go"><Icon name="external" :size="10" /></span>
          </button>
        </div>

        <div v-if="item.error" class="msg__error">
          <Icon name="x" :size="13" />
          <span>{{ item.error }}</span>
        </div>

        <div class="msg__meta mono">
          <!-- 复制按钮放在回复下方（与撤销文件修改并列），不再放左侧工具条 -->
          <button class="msg__copy" :title="copied ? '已复制' : '复制回复'" @click="copyContent">
            <Icon :name="copied ? 'check' : 'copy'" :size="11" />
            <span>{{ copied ? '已复制' : '复制' }}</span>
          </button>
          <button
            v-if="canRollback"
            class="msg__copy"
            :class="{ 'msg__copy--warn': rollbackDone }"
            :disabled="rollingBack"
            :title="rollbackLabel"
            @click="onRollback"
          >
            <Icon name="undo" :size="11" />
            <span>{{ rollbackLabel }}</span>
          </button>
          <span v-if="item.model" class="msg__meta-item">{{ item.model }}</span>
          <span v-if="item.tokenUsed" class="msg__meta-item">· {{ item.tokenUsed }} tokens</span>
          <span class="msg__meta-spacer" />
          <span v-if="timeText" class="msg__time mono">{{ timeText }}</span>
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
  gap: 5px;
  padding: 3px 10px;
  background: none;
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-3);
  font-size: 11px;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease);
}
.msg__copy:hover {
  color: var(--accent-text);
  background: var(--accent-dim);
  border-color: var(--accent-border);
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
.msg__text :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--accent-border);
  background: var(--bg-1);
  border-radius: 0 var(--r-6) var(--r-6) 0;
  color: var(--text-2);
}
.msg__text :deep(hr) {
  border: none;
  border-top: 1px solid var(--border-strong);
  margin: 14px 0;
}
.msg__code {
  margin: 8px 0;
  color: var(--text-0);
}
.msg__tools {
  margin-top: 6px;
}
/* 回复末尾「变动的文件」总览 */
.msg__files {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.msg__files-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--text-3);
}
.msg__files-head svg {
  color: var(--accent-text);
}
.msg__files-title {
  letter-spacing: 0.04em;
}
.msg__files-sub {
  font-size: 10px;
  margin-left: auto;
}
.msg__file {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  color: var(--text-1);
  font-size: var(--fs-12);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.msg__file:hover {
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.msg__file--err {
  border-color: rgba(224, 84, 84, 0.4);
}
.msg__file-ops {
  font-size: 10px;
  color: var(--accent-text);
  background: var(--accent-dim);
  border-radius: var(--r-pill);
  padding: 1px 7px;
  flex-shrink: 0;
  letter-spacing: 0.04em;
}
.msg__file--err .msg__file-ops {
  color: var(--danger-text);
  background: var(--danger-dim);
}
.msg__file-path {
  flex: 1;
  min-width: 0;
  color: var(--text-2);
}
.msg__file:hover .msg__file-path {
  color: var(--accent-text);
}
.msg__file-go {
  display: inline-flex;
  color: var(--text-3);
  flex-shrink: 0;
}
.msg__file:hover .msg__file-go {
  color: var(--accent-text);
}
.msg__toolops {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin: 6px 4px 2px 0;
  padding: 6px 12px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  color: var(--teal);
  font-size: var(--fs-12);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.msg__toolops:hover {
  background: var(--bg-2);
}
.msg__toolops--open {
  background: var(--bg-2);
  border-color: var(--border-strong);
}
.msg__toolops-label {
  font-size: var(--fs-12);
  color: inherit;
  letter-spacing: 0.04em;
}
.msg__toolops-pos {
  font-size: 11px;
  color: var(--text-3);
  background: var(--bg-2);
  border-radius: var(--r-pill);
  padding: 1px 7px;
}
.msg__toolops-run {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--accent);
  animation: thinkingPulse 1.1s ease-in-out infinite;
}
.msg__toolops-list {
  margin-top: 2px;
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
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
  background: none;
  border: none;
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--text-3);
  cursor: pointer;
  user-select: none;
}
.msg__thoughts-sum:hover {
  color: var(--text-1);
}
.msg__thoughts-box {
  margin-top: 6px;
  max-height: 220px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: var(--border-strong) transparent;
}
.msg__thoughts-box::-webkit-scrollbar {
  width: 6px;
}
.msg__thoughts-box::-webkit-scrollbar-thumb {
  background: var(--border-strong);
  border-radius: var(--r-pill);
}
.msg__thought {
  margin-top: 6px;
  font-size: var(--fs-13);
  color: var(--text-3);
  border-left: 2px solid var(--border);
  padding-left: 10px;
  white-space: pre-wrap;
  word-break: break-word;
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
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.msg__meta-item {
  white-space: nowrap;
}
.msg__meta-spacer {
  flex: 1;
}
.msg__copy:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.msg__copy--warn {
  color: var(--teal);
}
</style>
