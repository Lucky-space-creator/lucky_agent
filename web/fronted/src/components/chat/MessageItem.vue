<script setup lang="ts">
import { computed, ref } from 'vue'
import type { ChatItem, ToolCallView, OptionItemView } from '@/stores/chat'
import { renderMarkdown } from '@/utils/markdown'
import { useChatStore } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'
import ToolCallCard from './ToolCallCard.vue'
import AskCard from './AskCard.vue'
import OptionsCard from './OptionsCard.vue'
import CodeBlock from './CodeBlock.vue'
import Icon from '@/components/common/Icon.vue'

const props = defineProps<{ item: ChatItem }>()

const chat = useChatStore()
const ui = useUiStore()

const blocks = computed(() => renderMarkdown(props.item.content || ''))
/** 思考中加载：运行中且正文尚未开始输出时展示「思考中…」指示。 */
const showThinkingLoading = computed(() => props.item.status === 'running' && !props.item.content)
const copied = ref(false)

/* ---------- 思考过程（默认折叠在回复模块内；执行面板只保留任务列表） ---------- */

/** 思考折叠区是否展开（默认收起）。 */
const thoughtsOpen = ref(false)
/** 本轮思考条数（一条 = 一次 LLM 思考，后端已按轮聚合）。 */
const thoughtCount = computed(() => props.item.thoughts?.length ?? 0)

/* ---------- 执行详情折叠区（文件总览 + 工具调用，默认折叠，正文只留总结） ---------- */

/** 执行详情折叠区是否展开（默认收起）。 */
const detailOpen = ref(false)

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

/** 条件选择：用户点选某方案 → 作为输入续跑。 */
function onPickOption(opt: OptionItemView) {
  chat.pickOption(props.item, opt)
}

/** 条件选择：用户自定义补充 → 作为输入续跑。 */
function onCustomOption(text: string) {
  chat.pickOption(props.item, { custom: text })
}

/* ---------- 回滚到消息节点：截断该消息之后的对话上下文 ---------- */

/** 该用户消息之后是否还有消息（无则回滚无意义，隐藏入口）。 */
const hasFollowing = computed(() => {
  const idx = chat.messages.findIndex((m) => m.id === props.item.id)
  return idx >= 0 && idx < chat.messages.length - 1
})

/**
 * 点击「回滚到此节点」：打开全局二次确认弹窗（状态在 chat store，保证同时只有一个弹窗）。
 * 传入触发按钮的视口坐标，供 ChatView 的 Teleport 弹窗定位，从而脱离消息子树、
 * 不被后续助手消息覆盖，按钮可正常点击。
 */
function askRollbackNode(e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  const r = el.getBoundingClientRect()
  chat.openRollbackConfirm(props.item.id, { x: r.right, y: r.bottom })
}

/* ---------- 文件操作：查看类（read/list/stat）与变动类（write/delete/mkdir/rename）分流 ---------- */

/** 查看类文件工具（读取/列出/状态）：不属变动，展示在回复开头的「查看的文件」。 */
const QUERY_TOOLS = new Set(['file.read', 'file.list', 'file.stat'])
/** 变动类文件工具（写入/删除/创建/重命名）：展示在回复末尾的「变动的文件」。 */
const WRITE_TOOLS = new Set(['file.write', 'file.delete', 'file.mkdir', 'file.rename'])

/** 文件类工具（file.* 前缀）。 */
function isFileTool(c: ToolCallView): boolean {
  return (c.tool || '').startsWith('file.')
}
/** 查看类工具。 */
function isQueryTool(c: ToolCallView): boolean {
  return QUERY_TOOLS.has(c.tool || '')
}
/** 变动类工具。 */
function isWriteTool(c: ToolCallView): boolean {
  return WRITE_TOOLS.has(c.tool || '')
}

/** 从工具调用入参中解析目标文件路径（兼容 path 顶层 / args.path 嵌套两种结构）。 */
function filePathOf(c: ToolCallView): string {
  const args = (c.args ?? {}) as Record<string, any>
  return String(args?.path || args?.args?.path || '')
}

/** 本消息内的查看类文件调用。 */
const queryCalls = computed(() => props.item.toolCalls.filter(isQueryTool))
/** 本消息内的变动类文件调用。 */
const fileCalls = computed(() => props.item.toolCalls.filter(isWriteTool))
/** 其余工具调用（Skill/MCP/命令等），保持独立工具卡片展示。 */
const otherCalls = computed(() => props.item.toolCalls.filter((c) => !isFileTool(c)))

/** 按路径聚合文件调用为「路径 + 是否含失败」列表。 */
function summarize(calls: ToolCallView[]): { path: string; hasFail: boolean }[] {
  const map = new Map<string, boolean>()
  for (const c of calls) {
    const p = filePathOf(c)
    if (!p) continue
    if (c.status === 'done' && !c.ok) map.set(p, true)
    else map.set(p, map.get(p) ?? false)
  }
  return [...map.entries()].map(([path, hasFail]) => ({ path, hasFail }))
}

/** 查看的文件（回复开头）。 */
const querySummary = computed(() => summarize(queryCalls.value))
/** 变动的文件（回复末尾，仅修改/删除类）。 */
const fileSummary = computed(() => summarize(fileCalls.value))

/** 点击总览项：在右侧「项目文件」面板定位该文件。 */
function openFile(path: string) {
  if (path) ui.openFilePanel(path)
}

/* ---------- 文件总览下拉展示（默认前 8 个，超出的收进「展开全部」） ---------- */

/** 默认直接展示的胶囊数量。 */
const FILE_PREVIEW_LIMIT = 8
/** 文件总览下拉是否展开（查看/变动两类共用同一展开状态）。 */
const filesExpanded = ref(false)
/** 直接展示的「查看的文件」（未展开时截断）。 */
const visibleQueries = computed(() =>
  filesExpanded.value ? querySummary.value : querySummary.value.slice(0, FILE_PREVIEW_LIMIT),
)
/** 被收起的「查看的文件」数量。 */
const hiddenQueryCount = computed(() =>
  filesExpanded.value ? 0 : Math.max(0, querySummary.value.length - FILE_PREVIEW_LIMIT),
)
/** 直接展示的「变动的文件」（未展开时截断）。 */
const visibleFiles = computed(() =>
  filesExpanded.value ? fileSummary.value : fileSummary.value.slice(0, FILE_PREVIEW_LIMIT),
)
/** 被收起的「变动的文件」数量。 */
const hiddenCount = computed(() =>
  filesExpanded.value ? 0 : Math.max(0, fileSummary.value.length - FILE_PREVIEW_LIMIT),
)
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
          <button
            v-if="hasFollowing"
            class="msg__copy"
            title="回滚到此节点"
            @click.stop="askRollbackNode"
          >
            <Icon name="undo" :size="11" />
          </button>
          <span v-if="timeText" class="msg__time mono">{{ timeText }}</span>
        </div>
      </template>

      <!-- 助手消息 -->
      <template v-else>
        <!-- 思考中加载：运行中、有思考但正文尚未开始输出时的加载指示 -->
        <div v-if="showThinkingLoading" class="msg__thinking-loading">
          <span class="msg__thinking-dot" />
          <span>思考中…</span>
        </div>

        <!-- 正文内容：用户只需看到总结回复（流式增量实时渲染） -->
        <div class="msg__blocks">
          <template v-for="(b, i) in blocks" :key="i">
            <CodeBlock v-if="b.type === 'code'" :code="b.content" :lang="b.lang" />
            <div v-else class="msg__text" v-html="b.content" />
          </template>
        </div>

        <!-- 交互卡片：危险操作确认（属交互而非过程噪声，保留在正文区） -->
        <AskCard v-if="item.ask" :ask="item.ask" @confirm="onConfirm" />

        <!-- 交互卡片：条件选择（LLM 给出若干方案让用户拍板） -->
        <OptionsCard
          v-if="item.options"
          :options="item.options"
          @pick="onPickOption"
          @custom="onCustomOption"
        />

        <!-- 思考过程：默认折叠，置于正文下方 -->
        <div v-if="thoughtCount" class="msg__fold">
          <button class="msg__fold-head" @click="thoughtsOpen = !thoughtsOpen">
            <Icon :name="thoughtsOpen ? 'chevronDown' : 'chevronRight'" :size="11" />
            <Icon name="sparkles" :size="11" />
            <span class="mono">已深度思考（{{ thoughtCount }} 次）</span>
          </button>
          <div v-if="thoughtsOpen" class="msg__fold-body">
            <div v-for="(t, i) in item.thoughts" :key="i" class="msg__thought">{{ t }}</div>
          </div>
        </div>

        <!-- 执行详情：文件总览 + 工具调用，默认折叠（正文只留总结） -->
        <div v-if="querySummary.length || fileSummary.length || otherCalls.length" class="msg__fold">
          <button class="msg__fold-head" @click="detailOpen = !detailOpen">
            <Icon :name="detailOpen ? 'chevronDown' : 'chevronRight'" :size="11" />
            <Icon name="terminal" :size="11" />
            <span class="mono">执行详情</span>
            <span class="msg__fold-badge mono">{{ querySummary.length + fileSummary.length }} 文件 · {{ otherCalls.length }} 工具</span>
          </button>
          <div v-if="detailOpen" class="msg__fold-body">
            <!-- 查看的文件（read/list/stat，点击在右侧文件面板定位） -->
            <div v-if="querySummary.length" class="msg__files">
              <div class="msg__files-head">
                <Icon name="search" :size="12" />
                <span class="msg__files-title mono">查看的文件（{{ querySummary.length }}）</span>
                <span class="msg__files-sub text-3">点击定位</span>
              </div>
              <div class="msg__files-list">
                <button
                  v-for="f in visibleQueries"
                  :key="f.path"
                  class="msg__file"
                  :class="{ 'msg__file--err': f.hasFail }"
                  :title="f.hasFail ? '含失败操作' : '在文件面板中定位'"
                  @click="openFile(f.path)"
                >
                  <Icon name="search" :size="11" />
                  <span class="msg__file-path mono ellipsis">{{ f.path }}</span>
                </button>
              </div>
              <button v-if="hiddenQueryCount > 0" class="msg__files-more" @click="filesExpanded = true">
                <Icon name="chevronDown" :size="11" />
                <span class="mono">展开全部（{{ hiddenQueryCount }}）</span>
              </button>
              <button v-else-if="filesExpanded" class="msg__files-more" @click="filesExpanded = false">
                <Icon name="chevronUp" :size="11" />
                <span class="mono">收起</span>
              </button>
            </div>

            <!-- 变动的文件（修改/删除类） -->
            <div v-if="fileSummary.length" class="msg__files">
              <div class="msg__files-head">
                <Icon name="file" :size="12" />
                <span class="msg__files-title mono">变动的文件（{{ fileSummary.length }}）</span>
                <span class="msg__files-sub text-3">点击定位</span>
              </div>
              <div class="msg__files-list">
                <button
                  v-for="f in visibleFiles"
                  :key="f.path"
                  class="msg__file"
                  :class="{ 'msg__file--err': f.hasFail }"
                  :title="f.hasFail ? '含失败操作' : '在文件面板中定位'"
                  @click="openFile(f.path)"
                >
                  <Icon name="search" :size="11" />
                  <span class="msg__file-path mono ellipsis">{{ f.path }}</span>
                </button>
              </div>
              <button v-if="hiddenCount > 0" class="msg__files-more" @click="filesExpanded = true">
                <Icon name="chevronDown" :size="11" />
                <span class="mono">展开全部（{{ hiddenCount }}）</span>
              </button>
              <button v-else-if="filesExpanded" class="msg__files-more" @click="filesExpanded = false">
                <Icon name="chevronUp" :size="11" />
                <span class="mono">收起</span>
              </button>
            </div>

            <!-- 工具调用（Skill/MCP/命令等） -->
            <div v-if="otherCalls.length" class="msg__tools">
              <button
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
              <div v-if="toolsOpen" class="msg__toolops-list">
                <ToolCallCard v-for="call in otherCalls" :key="call.id" :call="call" />
              </div>
            </div>
          </div>
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
  position: relative;
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
/* 回复末尾「变动的文件」总览：横向收缩胶囊 */
.msg__files {
  margin-top: 8px;
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
.msg__files-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 6px;
}
.msg__file {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 260px;
  padding: 3px 10px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: var(--fs-12);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease);
}
.msg__file:hover {
  border-color: var(--accent-border);
  background: var(--accent-dim);
  color: var(--accent-text);
}
.msg__file--err {
  border-color: rgba(224, 84, 84, 0.4);
}
.msg__file--err:hover {
  color: var(--danger-text);
}
.msg__file-path {
  min-width: 0;
  color: inherit;
}
.msg__files-more {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 3px 10px;
  background: none;
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-pill);
  color: var(--text-3);
  font-size: var(--fs-12);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.msg__files-more:hover {
  color: var(--accent-text);
  border-color: var(--accent-border);
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
/* 折叠块（思考过程 / 执行详情）：默认收起，正文只留总结 */
.msg__fold {
  margin-top: 8px;
  border-top: 1px dashed var(--border);
  padding-top: 6px;
}
.msg__fold-head {
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
.msg__fold-head:hover {
  color: var(--text-1);
}
.msg__fold-badge {
  font-size: 10px;
  letter-spacing: 0.04em;
  color: var(--text-3);
  background: var(--bg-2);
  border-radius: var(--r-pill);
  padding: 1px 7px;
}
.msg__fold-body {
  margin-top: 6px;
  max-height: 320px;
  overflow-y: auto;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: var(--border-strong) transparent;
}
.msg__fold-body::-webkit-scrollbar {
  width: 6px;
}
.msg__fold-body::-webkit-scrollbar-thumb {
  background: var(--border-strong);
  border-radius: var(--r-pill);
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
