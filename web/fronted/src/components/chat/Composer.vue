<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import Icon from '@/components/common/Icon.vue'
import ModelPicker from '@/components/chat/ModelPicker.vue'
import DepthPicker from '@/components/chat/DepthPicker.vue'
import { useChatStore } from '@/stores/chat'
import { useWorkspaceStore } from '@/stores/workspace'
import { fileApi } from '@/api'
import type { ExecResult } from '@/api/types'

const chat = useChatStore()
const workspace = useWorkspaceStore()

const text = ref('')
const ta = ref<HTMLTextAreaElement | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
const attachments = ref<{ name: string; path: string; size: number }[]>([])
const uploading = ref(false)
const uploadMsg = ref('')

const permOpen = ref(false)
const permRoot = ref<HTMLElement | null>(null)
const autoRun = ref(true)

const permLabel = computed(() => {
  const level = workspace.current?.permissionLevel ?? 'MODIFY'
  return { READ_ONLY: '只读', MODIFY: '修改文件', FULL: 'Full access' }[level] ?? 'Full access'
})

// 状态条指标优先取会话级真实数据（chat.metrics），拿不到时才回退本地消息统计
const rounds = computed(() => chat.metrics?.modelCalls ?? chat.messages.filter((m) => m.role === 'user').length)
const steps = computed(() => {
  if (chat.metrics) {
    const tools = chat.metrics.toolCounts ?? {}
    const skill = chat.metrics.skillInvokes ?? 0
    const mcp = chat.metrics.mcpInvokes ?? 0
    const toolSteps = Object.values(tools).reduce((s, n) => s + (n ?? 0), 0)
    return toolSteps + skill + mcp
  }
  return chat.messages.reduce((s, m) => s + m.toolCalls.filter((t) => t.status === 'done').length, 0)
})
const llmText = computed(() => {
  if (chat.metrics?.lastModel) return chat.metrics.lastModel
  return chat.running ? '计算中…' : '—'
})
const tokenText = computed(() => {
  const n = chat.metrics?.tokenUsed ?? 0
  if (n <= 0) return '—'
  return n >= 1000 ? (n / 1000).toFixed(1) + 'k' : String(n)
})

function autoResize() {
  if (ta.value) {
    ta.value.style.height = 'auto'
    ta.value.style.height = Math.min(ta.value.scrollHeight, 160) + 'px'
  }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    send()
  }
}

function pickFile() {
  fileInput.value?.click()
}

function fmtSize(n: number): string {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}

async function onFiles(e: Event) {
  const input = e.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length || !workspace.current) return
  uploading.value = true
  uploadMsg.value = ''
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
      if (res.ok) {
        attachments.value.push({ name: f.name, path: target, size: f.size })
      } else {
        uploadMsg.value = res.error || '上传失败'
      }
    } catch (err) {
      uploadMsg.value = (err as Error).message
    }
  }
  uploading.value = false
}

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

function removeAttach(i: number) {
  attachments.value.splice(i, 1)
}

async function send() {
  const content = text.value.trim()
  if ((!content && attachments.value.length === 0) || chat.running) return
  const ats = attachments.value
  text.value = ''
  attachments.value = []
  if (ta.value) ta.value.style.height = 'auto'
  let payload = content
  if (ats.length) {
    const list = ats.map((a) => `- ${a.name}（${a.path}）`).join('\n')
    payload += (payload ? '\n\n' : '') + `【已上传附件，已写入工作区】\n${list}`
  }
  await chat.submit(payload, { auto: autoRun.value })
}

async function switchPerm(level: string) {
  permOpen.value = false
  if (!workspace.current || level === workspace.current.permissionLevel) return
  await workspace.updatePermission(workspace.current.workspaceId, level)
}

function onDoc(e: MouseEvent) {
  if (permRoot.value && !permRoot.value.contains(e.target as Node)) permOpen.value = false
}
onMounted(() => document.addEventListener('mousedown', onDoc))
onUnmounted(() => document.removeEventListener('mousedown', onDoc))
</script>

<template>
  <div class="composer-wrap">
    <!-- 上下文注入 -->
    <div v-if="workspace.current" class="ctx">
      <span class="ctx__label">上下文注入</span>
      <span class="ctx__chip"><Icon name="folder" :size="11" /> {{ workspace.current.name }}</span>
      <span class="ctx__chip"><Icon name="shield" :size="11" /> {{ permLabel }}</span>
      <span class="ctx__chip"><Icon name="chat" :size="11" /> {{ chat.currentSession?.title || '新会话' }}</span>
    </div>

    <!-- 控制台 -->
    <div class="console" :class="{ 'console--running': chat.running }">
      <textarea
        ref="ta"
        v-model="text"
        class="console__input"
        rows="1"
        :placeholder="workspace.current ? '给 Agent 下达指令…' : '请先在左侧配置工作空间'"
        :disabled="!workspace.current"
        @input="autoResize"
        @keydown="onKeydown"
      />

      <div class="console__foot">
        <button
          class="console__attach"
          title="上传文件到工作区"
          :disabled="!workspace.current || uploading"
          @click="pickFile"
        >
          <Icon :name="uploading ? 'refresh' : 'paperclip'" :size="14" :class="{ spin: uploading }" />
        </button>

        <!-- 权限选择器 -->
        <div ref="permRoot" class="perm">
          <button class="perm__btn" :class="{ 'perm__btn--open': permOpen }" :disabled="!workspace.current" @click="permOpen = !permOpen">
            <Icon name="shield" :size="12" />
            <span>{{ permLabel }}</span>
            <Icon name="chevronDown" :size="12" />
          </button>
          <Transition name="drop">
            <div v-if="permOpen" class="perm__menu">
              <button class="perm__opt" :class="{ 'perm__opt--on': (workspace.current?.permissionLevel ?? 'MODIFY') === 'READ_ONLY' }" @click="switchPerm('READ_ONLY')">只读</button>
              <button class="perm__opt" :class="{ 'perm__opt--on': (workspace.current?.permissionLevel ?? 'MODIFY') === 'MODIFY' }" @click="switchPerm('MODIFY')">修改文件</button>
              <button class="perm__opt" :class="{ 'perm__opt--on': (workspace.current?.permissionLevel ?? 'MODIFY') === 'FULL' }" @click="switchPerm('FULL')">全部权限</button>
            </div>
          </Transition>
        </div>

        <ModelPicker />
        <DepthPicker />

        <span class="console__spacer" />

        <!-- 自动执行开关 -->
        <div class="auto" title="自动执行">
          <span class="auto__icon"><Icon name="zap" :size="12" /></span>
          <span class="auto__text">自动执行</span>
          <button
            type="button"
            class="switch"
            :class="{ 'switch--on': autoRun }"
            role="switch"
            :aria-checked="autoRun"
            @click="autoRun = !autoRun"
          >
            <span class="switch__knob" />
          </button>
        </div>

        <!-- 发送 / 停止 -->
        <button
          class="console__send"
          :class="{ 'console__send--stop': chat.running }"
          :disabled="!workspace.current || (!text.trim() && !attachments.length && !chat.running)"
          @click="chat.running ? chat.cancel() : send()"
        >
          <Icon :name="chat.running ? 'stop' : 'send'" :size="14" />
        </button>
      </div>
    </div>

    <!-- 附件 -->
    <div v-if="attachments.length" class="attachments">
      <div v-for="(a, i) in attachments" :key="i" class="attach">
        <span class="attach__icon"><Icon name="file" :size="12" /></span>
        <span class="attach__name ellipsis">{{ a.name }}</span>
        <span class="attach__size mono">{{ fmtSize(a.size) }}</span>
        <button class="attach__x" title="移除" @click="removeAttach(i)"><Icon name="x" :size="11" /></button>
      </div>
    </div>

    <input ref="fileInput" type="file" multiple class="hidden-input" @change="onFiles" />

    <!-- 薄状态条 -->
    <div class="stats" :class="{ 'stats--live': chat.running }">
      <span class="stats__dot" />
      <span class="stats__item">{{ rounds }} 轮</span>
      <span class="stats__item">{{ steps }} 步</span>
      <span class="stats__sep">|</span>
      <span class="stats__item">LLM <span class="stats__val">{{ llmText }}</span></span>
      <span class="stats__sep">|</span>
      <span class="stats__item">Token 消耗 <span class="stats__val">{{ tokenText }}</span></span>
      <span v-if="uploadMsg" class="stats__err">{{ uploadMsg }}</span>
    </div>
  </div>
</template>

<style scoped>
.composer-wrap {
  padding: 6px 20px 14px;
  position: relative;
  z-index: 2;
}

/* 上下文注入 */
.ctx {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 0 2px 8px;
}
.ctx__label {
  font-size: 10px;
  letter-spacing: 0.08em;
  color: var(--text-3);
  margin-right: 2px;
}
.ctx__chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: var(--fs-11);
  color: var(--text-2);
}
.ctx__chip svg {
  color: var(--text-3);
}

/* 控制台 */
.console {
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-12);
  box-shadow: 0 4px 20px -8px rgba(15, 23, 42, 0.08);
  transition: border-color var(--dur-fast) var(--ease), box-shadow var(--dur-fast) var(--ease),
    transform var(--dur-med) var(--ease);
}
.console:hover {
  border-color: var(--border-strong);
}
.console:focus-within {
  border-color: var(--accent-border);
  box-shadow: 0 4px 24px -8px var(--accent-dim), 0 0 0 3px var(--accent-dim);
  transform: translateY(-1px);
}
.console--running {
  border-color: var(--accent-border);
}
.console__input {
  width: 100%;
  background: none;
  border: none;
  resize: none;
  outline: none;
  color: var(--text-0);
  font-size: var(--fs-14);
  line-height: var(--lh-body);
  padding: 12px 14px 6px;
  max-height: 160px;
  font-family: inherit;
}
.console__input::placeholder {
  color: var(--text-3);
}
.console__foot {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 6px 10px 9px;
  flex-wrap: wrap;
}
.console__attach {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-6);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.console__attach:hover:not(:disabled) {
  color: var(--accent-text);
  background: var(--accent-dim);
}
.console__attach:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.console__spacer {
  flex: 1;
}

/* 权限选择器 */
.perm {
  position: relative;
}
.perm__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 9px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.perm__btn:hover:not(:disabled) {
  border-color: var(--accent-border);
  color: var(--text-1);
}
.perm__btn svg:first-child {
  color: var(--accent-text);
}
.perm__btn:disabled {
  opacity: 0.5;
}
.perm__menu {
  position: absolute;
  left: 0;
  bottom: calc(100% + 6px);
  z-index: 40;
  min-width: 140px;
  max-height: 260px;
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
.perm__opt {
  width: 100%;
  padding: 7px 9px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-12);
  text-align: left;
}
.perm__opt:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.perm__opt--on {
  color: var(--accent-text);
  font-weight: 500;
  background: var(--accent-dim);
}

/* 自动执行开关 */
.auto {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--text-2);
  font-size: var(--fs-12);
  user-select: none;
}
.auto__icon {
  display: flex;
  color: var(--warn);
}
.auto__text {
  white-space: nowrap;
}
.switch {
  position: relative;
  width: 30px;
  height: 17px;
  border-radius: var(--r-pill);
  background: var(--bg-3);
  border: 1px solid var(--border-strong);
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
  padding: 0;
  flex-shrink: 0;
}
.switch__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 11px;
  height: 11px;
  border-radius: 50%;
  background: #fff;
  box-shadow: var(--shadow-1);
  transition: transform var(--dur-med) var(--ease);
}
.switch--on {
  background: var(--accent);
  border-color: var(--accent);
}
.switch--on .switch__knob {
  transform: translateX(13px);
}

/* 发送 */
.console__send {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--r-8);
  background: var(--accent);
  color: #fff;
  border: none;
  flex-shrink: 0;
  transition: background var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease),
    box-shadow var(--dur-fast) var(--ease);
}
.console__send:hover:not(:disabled) {
  background: var(--accent-strong);
  box-shadow: 0 2px 10px -2px var(--accent);
}
.console__send:active:not(:disabled) {
  transform: scale(0.92);
}
.console__send:disabled {
  background: var(--bg-3);
  color: var(--text-3);
  cursor: not-allowed;
}
.console__send--stop {
  background: var(--danger);
}
.console__send--stop:hover:not(:disabled) {
  background: var(--danger-text);
  box-shadow: 0 2px 10px -2px var(--danger);
}

/* 附件 */
.attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
  padding: 0 2px;
}
.attach {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 5px 8px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  font-size: var(--fs-12);
}
.attach__icon {
  color: var(--accent-text);
  display: flex;
  flex-shrink: 0;
}
.attach__name {
  color: var(--text-1);
  flex: 1;
  min-width: 0;
}
.attach__size {
  color: var(--text-3);
  font-size: 10px;
  flex-shrink: 0;
}
.attach__x {
  background: none;
  border: none;
  color: var(--text-3);
  padding: 1px;
  border-radius: var(--r-4);
  display: flex;
  flex-shrink: 0;
}
.attach__x:hover {
  color: var(--danger-text);
  background: var(--danger-dim);
}
.hidden-input {
  display: none;
}

/* 状态条 */
.stats {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 9px;
  padding: 0 4px;
  font-size: 11px;
  color: var(--text-3);
  font-family: var(--font-mono);
}
.stats__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--text-3);
  flex-shrink: 0;
}
.stats--live .stats__dot {
  background: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-dim);
  animation: stat-pulse 1.4s var(--ease) infinite;
}
@keyframes stat-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}
.stats__item {
  white-space: nowrap;
}
.stats__val {
  color: var(--text-1);
}
.stats--live .stats__val {
  color: var(--accent-text);
}
.stats__sep {
  color: var(--border-strong);
}
.stats__err {
  color: var(--danger-text);
  margin-left: auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.drop-enter-active,
.drop-leave-active {
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
