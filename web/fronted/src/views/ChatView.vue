<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useChatStore } from '@/stores/chat'
import { useWorkspaceStore } from '@/stores/workspace'
import { useToastStore } from '@/stores/toast'
import { useUiStore } from '@/stores/ui'
import { fileApi } from '@/api'
import type { ExecResult } from '@/api/types'
import MessageItem from '@/components/chat/MessageItem.vue'
import Composer from '@/components/chat/Composer.vue'
import TransparencyPanel from '@/components/chat/TransparencyPanel.vue'
import ProjectExplorer from '@/components/chat/ProjectExplorer.vue'
import ModelPicker from '@/components/chat/ModelPicker.vue'
import DepthPicker from '@/components/chat/DepthPicker.vue'
import Icon from '@/components/common/Icon.vue'

const chat = useChatStore()
const ui = useUiStore()
const workspace = useWorkspaceStore()
const toast = useToastStore()

/** 右侧工具类型。 */
type ToolKey = 'metrics' | 'files'

/** 当前激活的右侧工具（null 表示收起）。 */
const activeTool = ref<ToolKey | null>(null)

/** 项目文件树是否展开（由面板头部按钮控制）。 */
const fileTreeOpen = ref(true)

/** 右侧面板当前宽度（px，可拖拽调整；默认占较宽，内容充分利用面板宽度）。 */
const panelWidth = ref(480)
/** 是否正在拖拽分割线。 */
const resizing = ref(false)
/** 执行面板是否展开（悬浮面板默认展开，运行中点击头部可收缩）。 */
const runPanelOpen = ref(true)

/** 切换右侧工具面板（IDEA 风格：再次点击收起）。 */
function toggleTool(tool: ToolKey) {
  activeTool.value = activeTool.value === tool ? null : tool
}

/** 最近一次待定位的文件请求（相对路径 + 自增序号），交给 ProjectExplorer 定位展示。 */
const fileOpenSeq = ref(0)
const fileOpenPath = ref('')

// 消息内「查看文件」链接触发：打开项目文件面板并把文件请求转发给 ProjectExplorer
watch(
  () => ui.filesPanelRequest,
  (v) => {
    if (!v) return
    activeTool.value = 'files'
    fileOpenPath.value = ui.pendingFilePath
    fileOpenSeq.value = ui.filesPanelRequest
    // 确保右侧工具栏展开、面板可见
    ui.openRightRail()
  },
)

/** 收起面板。 */
function closeTool() {
  activeTool.value = null
}

/** 回滚确认弹窗定位样式：依据触发按钮的视口坐标固定定位，并夹取在视口内。 */
const rbStyle = computed(() => {
  const a = chat.rollbackAnchor
  if (!a) return {}
  const w = 248
  const h = 132
  let left = a.x - w
  let top = a.y + 8
  left = Math.max(8, Math.min(left, window.innerWidth - w - 8))
  top = Math.max(8, Math.min(top, window.innerHeight - h - 8))
  return { left: `${left}px`, top: `${top}px` }
})

/** 任务状态中文标签（执行面板任务列表展示用）。 */
function taskStatusLabel(s: string): string {
  return (
    { pending: '待执行', running: '执行中', done: '已完成', failed: '失败', ask: '待确认' } as Record<
      string,
      string
    >
  )[s] ?? s
}

/** 已完成任务数（执行面板头部进度展示）。 */
const doneTaskCount = computed(() => (chat.runPanel.plan ?? []).filter((t) => t.status === 'done').length)

// 收起右侧工具栏时：同时收起工具面板并隐藏工具栏窄条，让对话占满整行
watch(
  () => ui.rightRailOpen,
  (open) => {
    if (!open) {
      activeTool.value = null
    }
  },
)

/** 拖拽中：根据鼠标相对对话布局右侧的距离计算面板宽度。 */
function onResizeMove(e: MouseEvent) {
  if (!resizing.value) return
  const layout = document.querySelector('.chat__layout')
  const rail = document.querySelector('.chat__rail')
  if (!layout || !rail) return
  const rect = layout.getBoundingClientRect()
  const railWidth = rail.getBoundingClientRect().width
  // 面板在工具栏左侧：右边缘固定贴工具栏左边缘，宽度 = 工具栏左边缘到鼠标（分割线）的距离
  const width = Math.max(280, Math.min(900, rect.right - railWidth - e.clientX))
  panelWidth.value = width
}

/** 拖拽结束：移除监听与光标。 */
function onResizeEnd() {
  if (!resizing.value) return
  resizing.value = false
  window.removeEventListener('mousemove', onResizeMove)
  window.removeEventListener('mouseup', onResizeEnd)
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
}

/** 分割线按下：注册 window 监听并开始拖拽。 */
function onResizeStart(e: MouseEvent) {
  if (e.button !== 0) return
  resizing.value = true
  window.addEventListener('mousemove', onResizeMove)
  window.addEventListener('mouseup', onResizeEnd)
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
}

const listEl = ref<HTMLElement | null>(null)
const welcomeText = ref('')
const welcomeTa = ref<HTMLTextAreaElement | null>(null)

const hasMessages = computed(() => chat.messages.length > 0)

const cards = [
  { icon: 'search', title: '探索并理解代码', desc: '梳理项目结构、入口与关键模块', prompt: '探索并理解这个项目的代码，梳理整体架构、入口和核心模块。' },
  { icon: 'spark', title: '构建新功能', desc: '在现有代码库中实现新需求', prompt: '帮我构建一个新功能，请先给出实现方案。' },
  { icon: 'eye', title: '审查代码并提出修改建议', desc: '检查代码质量与潜在风险', prompt: '审查代码并提出具体的修改建议。' },
  { icon: 'zap', title: '修复问题和失败', desc: '定位报错根源并给出修复方案', prompt: '修复项目中存在的问题和失败，请定位根源。' },
]

async function scrollToBottom() {
  await nextTick()
  if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
}

/** 是否已贴近底部（用户未上翻阅读时跟随流式输出）。 */
function isNearBottom(): boolean {
  const el = listEl.value
  if (!el) return true
  return el.scrollHeight - el.scrollTop - el.clientHeight < 100
}

watch(
  () => chat.messages.length,
  scrollToBottom,
)

// 切换会话（含进入历史会话）后定位到最新消息
watch(
  () => chat.currentSessionId,
  async () => {
    await nextTick()
    await scrollToBottom()
  },
)

watch(
  () => chat.messages.map((m) => m.content + m.toolCalls.length + m.thoughts.length).join('|'),
  async () => {
    if (isNearBottom()) await scrollToBottom()
  },
)

async function useCard(c: (typeof cards)[number]) {
  if (chat.running) return
  await chat.submit(c.prompt)
}

async function welcomeSend() {
  const content = welcomeText.value.trim()
  if (!content || chat.running) return
  welcomeText.value = ''
  await chat.submit(content)
}

function welcomeKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    welcomeSend()
  }
}

/* ---------- 欢迎页输入框：工作空间选择 + 文件上传（与对话态 Composer 一致） ---------- */

/** 当前会话绑定的工作空间（未绑定返回 null）。 */
const sessionWs = computed(() => {
  const wid = chat.currentSession?.workspaceId
  if (!wid) return null
  return workspace.workspaces.find((w) => w.workspaceId === wid) ?? null
})
const wsLabel = computed(() => sessionWs.value?.name ?? '选择工作空间')
const wsOpen = ref(false)
const wsRoot = ref<HTMLElement | null>(null)

/** 点击选择器外部时关闭工作空间下拉列表。 */
function onWsDoc(e: MouseEvent) {
  if (wsRoot.value && !wsRoot.value.contains(e.target as Node)) wsOpen.value = false
}
onMounted(() => document.addEventListener('mousedown', onWsDoc))
onUnmounted(() => document.removeEventListener('mousedown', onWsDoc))

async function pickWs(workspaceId: string) {
  wsOpen.value = false
  if (workspaceId === sessionWs.value?.workspaceId) return
  const ok = await chat.setSessionWorkspace(workspaceId)
  if (!ok) toast.info('该会话已有对话记录，不能切换工作空间')
}

/** 上传文件到当前工作空间（成功后把附件清单注入待发送内容）。 */
const fileInput = ref<HTMLInputElement | null>(null)
const uploading = ref(false)

function readAsBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => {
      const result = r.result as string
      resolve(result.split(',')[1] ?? '')
    }
    r.onerror = () => reject(r.error)
    r.readAsDataURL(file)
  })
}

async function welcomeFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return
  if (!workspace.current) {
    toast.info('请先选择或创建工作空间，再上传文件')
    return
  }
  uploading.value = true
  const uploaded: string[] = []
  let fail = ''
  for (const f of files) {
    try {
      const b64 = await readAsBase64(f)
      const target = `.uploads/${f.name}`
      const res = (await fileApi.op({
        opType: 'WRITE',
        workspaceId: workspace.current.workspaceId,
        path: target,
        content: b64,
        args: { encoding: 'base64' },
      })) as ExecResult
      if (res.ok) uploaded.push(`- ${f.name}（${target}）`)
      else fail = res.error || '上传失败'
    } catch (err) {
      fail = (err as Error).message
    }
  }
  uploading.value = false
  if (uploaded.length) {
    const note = `【已上传附件，已写入工作区】\n${uploaded.join('\n')}`
    welcomeText.value = (welcomeText.value ? welcomeText.value + '\n\n' : '') + note
    toast.success(`已上传 ${uploaded.length} 个文件到工作区`)
  }
  if (fail) toast.error(fail)
}
</script>

<template>
  <div class="chat" :class="{ 'chat--welcome': !hasMessages }">
    <!-- 对话态（状态 B） -->
    <template v-if="hasMessages">
      <div class="chat__layout">
        <!-- 对话区：flex:1，随右侧工具栏/面板展开被推动 -->
        <div class="chat__main">
          <!-- 悬浮执行面板：只展示需要分步执行的任务列表，悬浮在输入框上方，可收缩（Issue 1） -->
          <div
            v-if="chat.running || (chat.runPanel.plan && chat.runPanel.plan.length > 0)"
            class="run-panel"
          >
            <div class="run-panel__head" @click="runPanelOpen = !runPanelOpen">
              <span class="run-panel__title">
                <Icon :name="runPanelOpen ? 'chevronDown' : 'chevronRight'" :size="11" />
                任务进度
              </span>
              <span class="run-panel__stat mono">
                已完成 {{ doneTaskCount }}/{{ chat.runPanel.plan?.length ?? 0 }}
              </span>
              <span v-if="!chat.running" class="run-panel__done">{{ chat.runPanel.phase || '完成' }}</span>
            </div>
            <div
              v-if="runPanelOpen && chat.runPanel.plan && chat.runPanel.plan.length"
              class="run-panel__body"
            >
              <!-- 任务列表：分步骤同步展示，跑完打标记 -->
              <div class="run-panel__sect">
                <div
                  v-for="t in chat.runPanel.plan"
                  :key="t.taskId"
                  class="rp-task"
                  :class="'rp-task--' + t.status"
                >
                  <span class="rp-task__dot" />
                  <span class="rp-task__title">{{ t.title }}</span>
                  <span class="rp-task__tag">{{ taskStatusLabel(t.status) }}</span>
                </div>
              </div>
            </div>
          </div>
          <div ref="listEl" class="chat__list">
            <MessageItem
              v-for="(item, i) in chat.messages"
              :key="item.id"
              :item="item"
              :style="{ animationDelay: Math.min(i, 12) * 28 + 'ms' }"
              class="msg-in"
            />
          </div>
          <Composer />
        </div>

        <!-- 右侧工具面板（展开在工具栏左侧，宽度驱动动画，占宽度推动对话区） -->
        <div class="chat__panel" :style="{ width: activeTool ? panelWidth + 'px' : 0 }">
          <div class="chat__panel-resize" title="拖动调整宽度" @mousedown="onResizeStart" />
          <header v-show="activeTool" class="chat__panel-head">
            <span v-if="activeTool === 'metrics'" class="chat__panel-title">
              <Icon name="gauge" :size="13" /> 监控指标
            </span>
            <span v-else class="chat__panel-title">
              <Icon name="folder" :size="13" /> 项目文件
            </span>
            <button
              v-if="activeTool === 'files'"
              class="chat__panel-close"
              :title="fileTreeOpen ? '收缩文件树' : '展开文件树'"
              @click="fileTreeOpen = !fileTreeOpen"
            >
              <Icon :name="fileTreeOpen ? 'chevronLeft' : 'chevronRight'" :size="14" />
            </button>
            <button v-else class="chat__panel-close" title="收起" @click="closeTool">
              <Icon name="x" :size="14" />
            </button>
          </header>
          <div v-show="activeTool" class="chat__panel-body">
            <TransparencyPanel
              v-if="activeTool === 'metrics' && chat.currentSessionId && (chat.running || chat.metrics)"
              :metrics="chat.metrics"
              :running="chat.running"
            />
            <ProjectExplorer
              v-else-if="activeTool === 'files'"
              v-model:tree-open="fileTreeOpen"
              :open-seq="fileOpenSeq"
              :open-path="fileOpenPath"
            />
          </div>
        </div>

        <!-- 右侧工具栏（窄条，参与布局，占宽度；仅展开时渲染，收起时对话占满整行） -->
        <aside v-if="ui.rightRailOpen" class="chat__rail">
          <button
            class="chat__rail-item"
            :class="{ 'chat__rail-item--on': activeTool === 'files' }"
            :title="activeTool === 'files' ? '收起项目文件' : '项目文件'"
            @click="toggleTool('files')"
          >
            <Icon name="folder" :size="14" />
          </button>
          <button
            class="chat__rail-item"
            :class="{ 'chat__rail-item--on': activeTool === 'metrics' }"
            :title="activeTool === 'metrics' ? '收起监控指标' : '监控指标'"
            @click="toggleTool('metrics')"
          >
            <Icon name="gauge" :size="14" />
          </button>
        </aside>
      </div>
    </template>

    <!-- 空态欢迎页（状态 A） -->
    <div v-else class="welcome">
      <div class="welcome__content">
        <div class="welcome__mark rise">
          <img src="/favicon.svg" alt="" class="welcome__logo" />
          <span class="welcome__halo" />
        </div>

        <h1 class="welcome__title rise" style="animation-delay: 40ms">
          我们应该在 lucky_agent 中做些什么？
        </h1>
        <p class="welcome__sub text-2 rise" style="animation-delay: 80ms">
          从了解项目开始，到构建、审查与修复 —— Agent 全程在本地执行。
        </p>

        <div class="welcome__cards rise" style="animation-delay: 120ms">
          <button v-for="c in cards" :key="c.title" class="card press" @click="useCard(c)">
            <span class="card__icon"><Icon :name="c.icon" :size="17" /></span>
            <span class="card__title">{{ c.title }}</span>
            <span class="card__desc text-2">{{ c.desc }}</span>
            <Icon name="arrowUp" :size="13" class="card__go" />
          </button>
        </div>
      </div>

      <div class="welcome__composer rise" style="animation-delay: 180ms">
        <div class="glass" :class="{ 'glass--live': chat.running }">
          <textarea
            ref="welcomeTa"
            v-model="welcomeText"
            rows="1"
            class="glass__input"
            placeholder="随心输入"
            :disabled="chat.running"
            @keydown="welcomeKeydown"
          />
          <div class="glass__foot">
            <!-- 工作空间选择（绑定当前会话；未绑定显示占位） -->
            <div ref="wsRoot" class="glass__ws">
              <button
                class="glass__custom"
                :class="{ 'glass__custom--unbound': !sessionWs, 'glass__custom--open': wsOpen }"
                :title="sessionWs ? '切换工作空间（绑定到当前会话）' : '当前会话未绑定工作空间，点击选择'"
                @click="wsOpen = !wsOpen"
              >
                <Icon name="folder" :size="13" />
                <span class="ellipsis glass__ws-name">{{ wsLabel }}</span>
                <Icon name="chevronDown" :size="11" />
              </button>
              <Transition name="drop">
                <div v-if="wsOpen" class="glass__ws-menu">
                  <button
                    v-for="w in workspace.visible"
                    :key="w.workspaceId"
                    class="glass__ws-opt"
                    :class="{ 'glass__ws-opt--on': w.workspaceId === sessionWs?.workspaceId }"
                    @click="pickWs(w.workspaceId)"
                  >
                    <Icon name="folder" :size="11" />
                    <span class="ellipsis">{{ w.name }}</span>
                    <Icon v-if="w.workspaceId === sessionWs?.workspaceId" name="check" :size="10" />
                  </button>
                  <button v-if="!workspace.visible.length" class="glass__ws-opt" disabled>
                    暂无工作空间，请先在设置中新建
                  </button>
                </div>
              </Transition>
            </div>
            <!-- 上传文件到当前工作空间 -->
            <button class="glass__custom" title="上传文件到当前工作空间" :disabled="uploading" @click="fileInput?.click()">
              <Icon :name="uploading ? 'refresh' : 'paperclip'" :size="13" :class="{ spin: uploading }" />
              <span>{{ uploading ? '上传中' : '上传文件' }}</span>
            </button>
            <input ref="fileInput" type="file" multiple class="glass__file" @change="welcomeFiles" />
            <span class="glass__spacer" />
            <DepthPicker />
            <ModelPicker />
            <button class="glass__send" :disabled="chat.running || !welcomeText.trim()" @click="welcomeSend">
              <Icon name="send" :size="14" />
            </button>
          </div>
        </div>
        <p class="glass__hint mono text-3">Enter 发送 · Shift+Enter 换行 · Agent 拥有完整工具链</p>
      </div>
    </div>
  </div>

  <!-- 回滚到节点二次确认：全局唯一弹窗，Teleport 到 body 脱离消息子树，规避被后续消息覆盖、按钮点不到；
       透明遮罩点击即关闭，弹窗本体 stop 阻止冒泡 -->
  <Teleport to="body">
    <div v-if="chat.confirmingRollbackId" class="rb-backdrop" @click="chat.cancelRollbackNode">
      <div class="rb-confirm" :style="rbStyle" @click.stop>
        <p class="rb-confirm__text">回滚到此节点将删除本条之后的全部消息，确认？</p>
        <div class="rb-confirm__actions">
          <button
            class="rb-confirm__btn rb-confirm__btn--primary"
            :disabled="chat.rollingBackNode"
            @click="chat.confirmRollbackNode"
          >
            确认
          </button>
          <button class="rb-confirm__btn" @click="chat.cancelRollbackNode">取消</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.chat {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  width: 100%;
}
.chat__layout {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  padding: 0 0 0 24px;
}
.chat__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  transition: width var(--dur-med) var(--ease);
}
.chat__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 8px 4px 0;
}

/* ---- 悬浮执行面板（在输入框上方，可收缩展示思考/进度/工具调用） ---- */
.run-panel {
  flex-shrink: 0;
  max-height: 200px;
  margin: 0 8px 8px 0;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-10);
  background: var(--bg-1);
  box-shadow: var(--shadow-1);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.run-panel__head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 12px;
  cursor: pointer;
  background: var(--bg-2);
}
.run-panel__head:hover {
  background: var(--bg-3);
}
.run-panel__title {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--accent-text);
}
.run-panel__stat {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-3);
}
.run-panel__done {
  font-size: 10px;
  color: var(--teal);
}
.run-panel__body {
  overflow-y: auto;
  padding: 6px 12px 8px;
}
.run-panel__sect {
  margin-top: 4px;
}

/* 执行面板任务列表：分步骤同步展示，跑完打标记 */
.rp-task {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 3px 0;
  font-size: 12px;
  color: var(--text-2);
}
.rp-task__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-3);
  flex-shrink: 0;
}
.rp-task--running .rp-task__dot {
  background: var(--accent);
  animation: thinkingPulse 1.1s ease-in-out infinite;
}
.rp-task--done .rp-task__dot {
  background: var(--teal);
}
.rp-task--failed .rp-task__dot {
  background: var(--danger-text);
}
.rp-task--ask .rp-task__dot {
  background: var(--warn, #e0a84e);
}
.rp-task__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rp-task__tag {
  font-size: 10px;
  color: var(--text-3);
  flex-shrink: 0;
}
.rp-task--done .rp-task__title {
  color: var(--text-1);
}
.rp-task--done .rp-task__tag {
  color: var(--teal);
}
.rp-task--failed .rp-task__tag {
  color: var(--danger-text);
}

/* 回滚到节点：全局二次确认弹窗（Teleport 到 body，脱离消息子树，按钮可点击） */
.rb-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1000;
  background: transparent;
}
.rb-confirm {
  position: fixed;
  width: 248px;
  padding: 12px 14px;
  background: var(--bg-1);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-8);
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.18);
  text-align: left;
}
.rb-confirm__text {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--text-1);
}
.rb-confirm__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.rb-confirm__btn {
  display: inline-flex;
  align-items: center;
  padding: 4px 12px;
  background: none;
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: 12px;
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease);
}
.rb-confirm__btn:hover {
  color: var(--accent-text);
  background: var(--accent-dim);
  border-color: var(--accent-border);
}
.rb-confirm__btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.rb-confirm__btn--primary {
  color: #fff;
  background: var(--accent);
  border-color: var(--accent);
}
.rb-confirm__btn--primary:hover:not(:disabled) {
  filter: brightness(1.05);
}

/* ---- 右侧工具栏（参与 flex 布局，占宽度；可整体收缩） ---- */
.chat__rail {
  flex-shrink: 0;
  width: var(--rail-w);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 0;
  border-left: 1px solid var(--border);
  background: var(--bg-0);
  transition: width var(--dur-med) var(--ease);
}
.chat__rail--collapsed {
  width: var(--rail-w-collapsed);
  padding: 6px 0;
}
.chat__rail-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 30px;
  border: none;
  background: none;
  color: var(--text-3);
  border-radius: var(--r-6);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.chat__rail-item:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.chat__rail-item--on {
  color: var(--accent-text);
  background: var(--accent-dim);
}

/* ---- 右侧工具面板（参与 flex 布局，展开时占宽度推动对话区；宽度可拖拽） ---- */
.chat__panel {
  position: relative;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-0);
  border-left: 1px solid var(--border-strong);
  box-shadow: -16px 0 40px -20px rgba(0, 0, 0, 0.35);
  transition: width var(--dur-med) var(--ease);
}
.chat__panel-resize {
  position: absolute;
  left: -4px;
  top: 0;
  bottom: 0;
  width: 8px;
  cursor: col-resize;
  z-index: 30;
}
.chat__panel-resize:hover {
  background: var(--accent-dim);
}
.chat__panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.chat__panel-title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-0);
}
.chat__panel-title svg {
  color: var(--accent-text);
}
.chat__panel-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border: none;
  background: none;
  color: var(--text-3);
  border-radius: var(--r-4);
  cursor: pointer;
}
.chat__panel-close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.chat__panel-body {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  padding: 14px;
}
.chat__panel-body > .tp {
  height: 100%;
}
.chat__panel-body > .pe {
  height: 100%;
}

/* ---- 空态欢迎页 ---- */
.chat--welcome {
  max-width: none;
}
.welcome {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6vh 32px 26px;
  overflow-y: auto;
}
.welcome__content {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 14px;
}
.welcome__mark {
  position: relative;
  width: 84px;
  height: 84px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 6px;
}
.welcome__logo {
  width: 64px;
  height: 64px;
  border-radius: 18px;
  box-shadow: var(--shadow-2);
  position: relative;
  z-index: 1;
}
.welcome__halo {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle, var(--accent-dim) 0%, transparent 70%);
  filter: blur(6px);
}
.welcome__title {
  font-size: 24px;
  font-weight: 700;
  color: var(--text-0);
  letter-spacing: -0.02em;
}
.welcome__sub {
  font-size: var(--fs-14);
  max-width: 460px;
}
.welcome__cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-top: 22px;
  max-width: 560px;
  width: 100%;
}
.card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 16px 16px 14px;
  background: var(--bg-0);
  border: 1px solid var(--border);
  border-radius: var(--r-12);
  text-align: left;
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.card:hover {
  border-color: var(--accent-border);
  background: var(--bg-1);
  box-shadow: var(--shadow-2);
  transform: translateY(-2px);
}
.card__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--r-8);
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  color: var(--accent);
  margin-bottom: 4px;
}
.card__title {
  font-size: var(--fs-14);
  font-weight: 600;
  color: var(--text-0);
}
.card__desc {
  font-size: var(--fs-12);
  line-height: 1.5;
}
.card__go {
  position: absolute;
  top: 14px;
  right: 14px;
  color: var(--text-3);
  transition: color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.card:hover .card__go {
  color: var(--accent);
  transform: translate(1px, -1px);
}

/* ---- 玻璃输入 ---- */
.welcome__composer {
  width: 100%;
  max-width: 640px;
  margin-top: auto;
  padding-top: 28px;
}
.glass {
  background: var(--glass-bg);
  -webkit-backdrop-filter: var(--glass-blur);
  backdrop-filter: var(--glass-blur);
  border: 1px solid var(--glass-border);
  border-radius: var(--r-12);
  box-shadow: 0 8px 40px -12px rgba(15, 23, 42, 0.16), 0 0 0 1px rgba(255, 255, 255, 0.5);
  padding: 8px 10px 6px;
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease);
}
.glass:focus-within {
  border-color: var(--accent-border);
  box-shadow: 0 8px 44px -10px var(--accent-dim), 0 0 0 3px var(--accent-dim);
}
.glass--live {
  border-color: var(--accent-border);
}
.glass__input {
  width: 100%;
  background: none;
  border: none;
  resize: none;
  outline: none;
  color: var(--text-0);
  font-size: var(--fs-15);
  line-height: 1.6;
  padding: 8px 10px;
  font-family: inherit;
}
.glass__input::placeholder {
  color: var(--text-3);
}
.glass__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 6px 2px;
}
.glass__custom {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  border: none;
  background: none;
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.glass__custom:hover:not(:disabled) {
  color: var(--accent-text);
  background: var(--accent-dim);
}
.glass__custom:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
/* 工作空间选择器（欢迎页输入框内，绑定当前会话） */
.glass__ws {
  position: relative;
}
.glass__custom--unbound {
  border: 1px dashed var(--accent-border);
  color: var(--accent-text);
}
.glass__custom--open {
  color: var(--accent-text);
  background: var(--accent-dim);
}
.glass__ws-name {
  max-width: 120px;
  min-width: 0;
}
.glass__ws-menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 80;
  min-width: 190px;
  max-width: 260px;
  max-height: 280px;
  overflow-y: auto;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-10);
  box-shadow: var(--shadow-2);
  padding: 5px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.glass__ws-opt {
  display: flex;
  align-items: center;
  gap: 7px;
  width: 100%;
  min-width: 0;
  padding: 7px 9px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-12);
  text-align: left;
  cursor: pointer;
}
.glass__ws-opt svg:first-child {
  color: var(--text-3);
  flex-shrink: 0;
}
.glass__ws-opt:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.glass__ws-opt--on {
  color: var(--accent-text);
  font-weight: 500;
  background: var(--accent-dim);
}
.glass__ws-opt:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.glass__file {
  display: none;
}
.drop-enter-active,
.drop-leave-active {
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(4px);
}
.glass__spacer {
  flex: 1;
}
.glass__send {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--r-8);
  background: var(--bg-3);
  border: 1px solid var(--border-strong);
  color: var(--text-2);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.glass__send:hover:not(:disabled) {
  border-color: var(--accent-border);
  background: var(--accent);
  color: #fff;
}
.glass__send:active:not(:disabled) {
  transform: scale(0.92);
}
.glass__send:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.glass__hint {
  font-size: 11px;
  text-align: center;
  margin-top: 10px;
}
</style>
