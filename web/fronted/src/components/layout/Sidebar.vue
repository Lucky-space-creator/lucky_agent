<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import Icon from '@/components/common/Icon.vue'
import SettingsDrawer from './SettingsDrawer.vue'
import WorkspaceCreateModal from './WorkspaceCreateModal.vue'
import { useChatStore, type ChatSession } from '@/stores/chat'
import { useWorkspaceStore } from '@/stores/workspace'
import { useModelStore } from '@/stores/model'

const router = useRouter()
const chat = useChatStore()
const workspace = useWorkspaceStore()
const model = useModelStore()

const settingsOpen = ref(false)
/** 新建工作空间小窗（项目分组「＋」直接弹出，含目录选择）。 */
const wsCreateOpen = ref(false)
const collapsed = ref(localStorage.getItem('lucky-sidebar-collapsed') === '1')
const searchOpen = ref(false)
const searchQuery = ref('')
const openProjects = ref(true)
const openRecent = ref(true)
const helpOpen = ref(false)

const filteredSessions = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  if (!q) return chat.sessions
  return chat.sessions.filter((s) => s.title.toLowerCase().includes(q))
})

/* ---------- 历史会话按日期分组（今天 / 昨天 / 7天内 / 更早） ---------- */

interface SessionGroup {
  label: string
  items: ChatSession[]
}

const DAY = 86_400_000

/** 会话创建时间 → 分组标签。 */
function dateLabel(ts: number): string {
  const now = new Date()
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  if (ts >= startToday) return '今天'
  if (ts >= startToday - DAY) return '昨天'
  if (ts >= startToday - 7 * DAY) return '7天内'
  return '更早'
}

/** 按日期分组（保持原顺序：最新在前）。 */
const groupedSessions = computed<SessionGroup[]>(() => {
  const groups: SessionGroup[] = []
  const map = new Map<string, SessionGroup>()
  for (const s of filteredSessions.value) {
    const label = dateLabel(s.createdAt)
    let g = map.get(label)
    if (!g) {
      g = { label, items: [] }
      map.set(label, g)
      groups.push(g)
    }
    g.items.push(s)
  }
  return groups
})

/* ---------- 会话重命名（双击标题进入内联编辑） ---------- */

const renamingId = ref<string | null>(null)
const renameText = ref('')
const renameInputs = ref<HTMLInputElement[]>([])

async function startRename(s: ChatSession) {
  renamingId.value = s.id
  renameText.value = s.title
  await nextTick()
  renameInputs.value[0]?.focus()
  renameInputs.value[0]?.select()
}

async function commitRename() {
  if (renamingId.value) {
    await chat.renameSession(renamingId.value, renameText.value.trim())
  }
  renamingId.value = null
}

function cancelRename() {
  renamingId.value = null
}

/** 本机单人使用，无账号概念：底部展示固定的本机标识（与记忆/会话分片用的 userId 对齐）。 */
const displayName = '本地用户'
const initial = '本'
const modelSub = computed(() => model.primary?.modelName ?? '未配置模型')

function toggleCollapse() {
  collapsed.value = !collapsed.value
  localStorage.setItem('lucky-sidebar-collapsed', collapsed.value ? '1' : '0')
}

function newChat() {
  chat.newSession()
  router.push('/')
}

/* ---------- 项目分组内嵌会话（需求：在项目下新建/查看会话） ---------- */

/** 展开显示其会话子列表的工作空间（key=workspaceId）。 */
const expandedWs = ref<Record<string, boolean>>({})

/** 点击项目：展开/收起其会话子列表，并同步切换当前工作空间。 */
function toggleWs(workspaceId: string) {
  expandedWs.value[workspaceId] = !expandedWs.value[workspaceId]
  workspace.setCurrent(workspaceId)
}

/** 某工作空间下的会话（按会话绑定的 workspaceId 过滤）。 */
function wsSessions(workspaceId: string): ChatSession[] {
  return chat.sessions.filter((s) => s.workspaceId === workspaceId)
}

/** 在指定项目下新建会话：绑定该工作空间并进入对话。 */
async function newWsChat(w: { workspaceId: string; name: string }) {
  expandedWs.value[w.workspaceId] = true
  workspace.setCurrent(w.workspaceId)
  await chat.newSession(w.workspaceId)
  router.push('/')
}

/** 打开项目下某个会话：同步切换工作空间上下文再加载历史。 */
function pickWsSession(s: ChatSession) {
  workspace.setCurrent(s.workspaceId)
  router.push('/')
  chat.selectSession(s.id)
}

/** 点击会话：先切路由再加载历史，保证在 /files 等非对话页也能跳回对话界面。 */
function pickSession(id: string) {
  router.push('/')
  chat.selectSession(id)
}

const mq = window.matchMedia('(max-width: 920px)')
function onMq(e: MediaQueryListEvent | MediaQueryList) {
  if (e.matches) collapsed.value = true
}
onMounted(() => {
  onMq(mq)
  mq.addEventListener('change', onMq)
})
onUnmounted(() => mq.removeEventListener('change', onMq))
</script>

<template>
  <aside class="sidebar" :class="{ 'sidebar--collapsed': collapsed }">
    <!-- 品牌区 -->
    <div class="brand">
      <img src="/favicon.svg" alt="" class="brand__mark" />
      <span class="brand__word hide-collapsed">lucky_agent</span>
      <div class="brand__acts hide-collapsed">
        <button class="brand__act" :class="{ 'brand__act--on': searchOpen }" title="搜索会话" @click="searchOpen = !searchOpen">
          <Icon name="search" :size="15" />
        </button>
        <button class="brand__act" title="通知">
          <Icon name="bell" :size="15" />
          <span class="brand__dot" />
        </button>
      </div>
    </div>

    <!-- 顶部折叠按钮 -->
    <button
      class="collapse-top"
      :class="{ 'collapse-top--closed': collapsed }"
      :title="collapsed ? '展开侧栏' : '收起侧栏'"
      @click="toggleCollapse"
    >
      <Icon :name="collapsed ? 'chevronsRight' : 'chevronsLeft'" :size="15" />
    </button>

    <!-- 新对话 -->
    <button class="newchat" title="新对话" @click="newChat">
      <Icon name="plus" :size="15" />
      <span class="hide-collapsed">新对话</span>
    </button>

    <!-- 快捷入口 -->
    <nav class="quick">
      <button class="quick__item" title="定时任务" @click="router.push('/tasks')">
        <Icon name="calendar" :size="14" />
        <span class="hide-collapsed">定时任务</span>
      </button>
      <button class="quick__item" title="插件" @click="router.push('/plugins')">
        <Icon name="puzzle" :size="14" />
        <span class="hide-collapsed">插件</span>
      </button>
    </nav>

    <!-- 搜索框 -->
    <div v-if="searchOpen" class="searchbox hide-collapsed">
      <Icon name="search" :size="13" />
      <input v-model="searchQuery" class="searchbox__input" placeholder="搜索会话…" />
    </div>

    <!-- 树 -->
    <div class="tree hide-collapsed">
      <div class="tree-group">
        <div class="tree-head">
          <button class="tree-head__main" @click="openProjects = !openProjects">
            <Icon name="folder" :size="14" />
            <span>项目</span>
            <Icon name="chevronDown" :size="13" class="tree-head__chev" :class="{ 'tree-head__chev--open': openProjects }" />
          </button>
          <div class="tree-head__acts">
            <button class="tree-head__act" title="浏览文件" @click="router.push('/files')"><Icon name="file" :size="13" /></button>
            <button class="tree-head__act" title="新建工作空间" @click="wsCreateOpen = true"><Icon name="plus" :size="13" /></button>
          </div>
        </div>
        <div v-show="openProjects" class="tree-list">
          <!-- 每个项目（工作空间）节点：可展开显示其会话子列表，项内提供「新建会话」 -->
          <div
            v-for="w in workspace.visible"
            :key="w.workspaceId"
            class="ws-node"
            :class="{ 'ws-node--active': w.workspaceId === workspace.currentId }"
          >
            <div class="ws-node__head">
              <button class="tree-item ws-node__main" :title="w.name" @click="toggleWs(w.workspaceId)">
                <Icon
                  name="chevronRight"
                  :size="12"
                  class="ws-node__chev"
                  :class="{ 'ws-node__chev--open': expandedWs[w.workspaceId] }"
                />
                <Icon name="folder" :size="13" />
                <span class="ellipsis">{{ w.name }}</span>
                <span class="ws-node__count mono">{{ wsSessions(w.workspaceId).length }}</span>
              </button>
              <div class="ws-node__acts">
                <button class="tree-head__act" :title="`在「${w.name}」下新建会话`" @click="newWsChat(w)">
                  <Icon name="plus" :size="13" />
                </button>
              </div>
            </div>
            <div v-show="expandedWs[w.workspaceId]" class="ws-node__sessions">
              <button
                v-for="s in wsSessions(w.workspaceId)"
                :key="s.id"
                class="tree-item ws-node__session"
                :class="{ 'tree-item--active': s.id === chat.currentSessionId }"
                @click="renamingId !== s.id && pickWsSession(s)"
                @dblclick="startRename(s)"
              >
                <Icon name="chat" :size="12" />
                <input
                  v-if="renamingId === s.id"
                  v-model="renameText"
                  ref="renameInputs"
                  class="tree-item__input"
                  :placeholder="s.title"
                  @click.stop
                  @keydown.enter.prevent="commitRename"
                  @keydown.esc.prevent="cancelRename"
                  @blur="commitRename"
                />
                <span v-else class="ellipsis tree-item__title" :title="'双击重命名'">{{ s.title }}</span>
                <span class="tree-item__del" title="删除会话" @click.stop="chat.removeSession(s.id)"><Icon name="x" :size="11" /></span>
              </button>
              <div v-if="wsSessions(w.workspaceId).length === 0" class="ws-node__empty text-3">
                暂无会话，点击右侧「＋」新建
              </div>
            </div>
          </div>
          <div v-if="workspace.visible.length === 0" class="tree-empty">
            尚无自建工作空间，<span class="tree-empty__link" @click="settingsOpen = true">去设置新建</span>
          </div>
        </div>
      </div>

      <div class="tree-group">
        <div class="tree-head">
          <button class="tree-head__main" @click="openRecent = !openRecent">
            <Icon name="history" :size="14" />
            <span>最近</span>
            <Icon name="chevronDown" :size="13" class="tree-head__chev" :class="{ 'tree-head__chev--open': openRecent }" />
          </button>
          <button class="tree-head__act" title="新建会话" @click="newChat"><Icon name="plus" :size="13" /></button>
        </div>
        <div v-show="openRecent" class="tree-list">
          <template v-for="g in groupedSessions" :key="g.label">
            <div v-if="g.items.length" class="tree-date">
              <span class="tree-date__text">{{ g.label }}</span>
              <span class="tree-date__count">{{ g.items.length }}</span>
            </div>
            <button
              v-for="s in g.items"
              :key="s.id"
              class="tree-item"
              :class="{ 'tree-item--active': s.id === chat.currentSessionId }"
              @click="renamingId !== s.id && pickSession(s.id)"
              @dblclick="startRename(s)"
            >
              <Icon name="chat" :size="13" />
              <input
                v-if="renamingId === s.id"
                v-model="renameText"
                ref="renameInputs"
                class="tree-item__input"
                :placeholder="s.title"
                @click.stop
                @keydown.enter.prevent="commitRename"
                @keydown.esc.prevent="cancelRename"
                @blur="commitRename"
              />
              <span v-else class="ellipsis tree-item__title" :title="'双击重命名'">{{ s.title }}</span>
              <span class="tree-item__del" title="删除会话" @click.stop="chat.removeSession(s.id)"><Icon name="x" :size="11" /></span>
            </button>
          </template>
          <div v-if="filteredSessions.length === 0" class="tree-empty">暂无会话</div>
        </div>
      </div>
    </div>

    <!-- 底部 -->
    <div class="foot">
      <button class="user" title="设置" @click="settingsOpen = true">
        <span class="user__avatar">{{ initial }}</span>
        <span class="user__meta hide-collapsed">
          <span class="user__name">{{ displayName }}</span>
          <span class="user__sub ellipsis">{{ modelSub }}</span>
        </span>
        <Icon name="settings" :size="14" class="user__gear hide-collapsed" />
      </button>
      <button class="foot__btn" title="帮助" @click="helpOpen = !helpOpen">
        <Icon name="help" :size="14" />
        <span class="hide-collapsed">帮助</span>
      </button>
    </div>

    <!-- 帮助浮层 -->
    <Transition name="pop">
      <div v-if="helpOpen" class="pop">
        <div class="pop__row"><Icon name="spark" :size="13" /><span>Lucky Agent · 本地智能体工作台</span></div>
        <div class="pop__row"><Icon name="sparkles" :size="13" /><span>{{ modelSub }}</span></div>
        <div class="pop__row"><Icon name="history" :size="13" /><span>{{ chat.sessions.length }} 个会话</span></div>
      </div>
    </Transition>
  </aside>

  <SettingsDrawer :open="settingsOpen" @close="settingsOpen = false" />
  <WorkspaceCreateModal v-if="wsCreateOpen" @close="wsCreateOpen = false" />
</template>

<style scoped>
.sidebar {
  width: var(--sidebar-w);
  flex-shrink: 0;
  background: var(--bg-1);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 10px;
  position: relative;
  transition: width var(--dur-med) var(--ease);
}
.sidebar--collapsed {
  width: var(--sidebar-w-collapsed);
  padding: 12px 8px;
}

/* 品牌区 */
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 4px 8px;
}
.sidebar--collapsed .brand {
  justify-content: center;
  padding: 2px 0 8px;
}
.brand__mark {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  flex-shrink: 0;
}
.brand__word {
  font-size: var(--fs-13);
  font-weight: 700;
  color: var(--text-0);
  letter-spacing: -0.01em;
  flex: 1;
  min-width: 0;
  white-space: nowrap;
}
.brand__acts {
  display: flex;
  align-items: center;
  gap: 2px;
}
.brand__act {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-3);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.brand__act:hover,
.brand__act--on {
  color: var(--text-1);
  background: var(--bg-2);
}
.brand__act--on {
  color: var(--accent-text);
}
.brand__dot {
  position: absolute;
  top: 4px;
  right: 4px;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
  border: 1.5px solid var(--bg-1);
}

/* 顶部折叠按钮 */
.collapse-top {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 26px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-3);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.collapse-top:hover {
  color: var(--text-1);
  background: var(--bg-2);
}

/* 新对话药丸 */
.newchat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  width: 100%;
  height: 34px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-8);
  background: var(--bg-0);
  color: var(--text-1);
  font-size: var(--fs-13);
  font-weight: 600;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.newchat:hover {
  border-color: var(--accent-border);
  background: var(--accent-dim);
  color: var(--accent-text);
  transform: translateY(-1px);
}
.sidebar--collapsed .newchat {
  padding: 0;
}

/* 快捷入口 */
.quick {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 2px;
}
.quick__item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 10px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-13);
  text-align: left;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.quick__item:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.quick__item svg {
  color: var(--text-3);
  flex-shrink: 0;
}
.quick__item:hover svg {
  color: var(--accent-text);
}
.sidebar--collapsed .quick__item {
  justify-content: center;
  padding: 7px 0;
}

/* 搜索框 */
.searchbox {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 2px 4px;
  padding: 6px 9px;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  color: var(--text-3);
}
.searchbox:focus-within {
  border-color: var(--accent-border);
}
.searchbox__input {
  flex: 1;
  min-width: 0;
  background: none;
  border: none;
  outline: none;
  color: var(--text-1);
  font-size: var(--fs-13);
}
.searchbox__input::placeholder {
  color: var(--text-3);
}

/* 树 */
.tree {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 4px;
  padding-right: 2px;
}
.tree-group {
  display: flex;
  flex-direction: column;
}
.tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 4px 8px;
}
.tree-head__main {
  display: flex;
  align-items: center;
  gap: 7px;
  border: none;
  background: none;
  color: var(--text-3);
  font-size: var(--fs-11);
  font-weight: 600;
  letter-spacing: 0.06em;
  padding: 2px 4px;
  border-radius: var(--r-4);
}
.tree-head__main svg {
  color: var(--text-3);
}
.tree-head__main:hover {
  color: var(--text-1);
}
.tree-head__chev {
  transition: transform var(--dur-med) var(--ease);
}
.tree-head__chev--open {
  transform: rotate(180deg);
}
.tree-head__acts {
  display: flex;
  align-items: center;
  gap: 1px;
}
.tree-head__act {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  background: none;
  color: var(--text-3);
  border-radius: var(--r-4);
}
.tree-head__act:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.tree-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 2px 0 4px;
}

/* 项目分组内嵌会话：工作空间节点（可展开其会话子列表） */
.ws-node {
  display: flex;
  flex-direction: column;
}
.ws-node__head {
  display: flex;
  align-items: center;
  gap: 1px;
}
.ws-node__main {
  flex: 1;
  min-width: 0;
}
.ws-node__main .ws-node__chev {
  color: var(--text-3);
  transition: transform var(--dur-med) var(--ease);
  flex-shrink: 0;
}
.ws-node__main .ws-node__chev--open {
  transform: rotate(90deg);
}
.ws-node__count {
  font-size: 10px;
  color: var(--text-3);
  background: var(--bg-2);
  border-radius: var(--r-pill);
  padding: 0 6px;
  flex-shrink: 0;
}
.ws-node__acts {
  display: flex;
  align-items: center;
  gap: 1px;
}
.ws-node__sessions {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-left: 12px;
  padding-left: 8px;
  border-left: 1px solid var(--border-faint);
}
.ws-node__session {
  padding-left: 8px;
}
.ws-node__empty {
  font-size: 11px;
  padding: 4px 8px 6px;
}
.tree-date {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  padding: 8px 9px 3px;
  color: var(--text-3);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.08em;
}
.tree-date__text {
  text-transform: uppercase;
}
.tree-date__count {
  font-size: 9px;
  font-weight: 600;
  color: var(--text-4);
  background: var(--bg-2);
  border-radius: var(--r-6);
  padding: 1px 5px;
}
.tree-item__input {
  flex: 1;
  min-width: 0;
  height: 20px;
  padding: 0 4px;
  background: var(--bg-0);
  border: 1px solid var(--accent-border);
  border-radius: var(--r-4);
  color: var(--text-0);
  font-size: var(--fs-12);
  outline: none;
}
.tree-item__input:focus {
  border-color: var(--accent);
}
.tree-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 9px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-13);
  text-align: left;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.tree-item svg {
  color: var(--text-3);
  flex-shrink: 0;
}
.tree-item__title {
  flex: 1;
  min-width: 0;
}
.tree-item__del {
  display: none;
  color: var(--text-3);
  padding: 2px;
  border-radius: var(--r-4);
  flex-shrink: 0;
}
.tree-item:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.tree-item:hover .tree-item__del {
  display: flex;
}
.tree-item__del:hover {
  color: var(--danger-text);
}
.tree-item--active {
  background: var(--accent-dim);
  color: var(--accent-strong);
  font-weight: 500;
}
.tree-item--active svg {
  color: var(--accent);
}
.tree-empty {
  font-size: var(--fs-12);
  color: var(--text-3);
  padding: 6px 10px 8px;
}
.tree-empty__link {
  color: var(--accent-text);
  cursor: pointer;
}
.tree-empty__link:hover {
  text-decoration: underline;
}

/* 底部 */
.foot {
  border-top: 1px solid var(--border);
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.user {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 7px 8px;
  border: none;
  background: none;
  border-radius: var(--r-8);
  text-align: left;
  transition: background var(--dur-fast) var(--ease);
}
.user:hover {
  background: var(--bg-2);
}
.user__avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--accent), var(--accent-strong));
  color: #fff;
  font-size: var(--fs-13);
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.user__meta {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.user__name {
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-0);
}
.user__sub {
  font-size: 10px;
  color: var(--text-3);
}
.user__gear {
  color: var(--text-3);
  flex-shrink: 0;
}
.user:hover .user__gear {
  color: var(--text-1);
}
.sidebar--collapsed .user {
  justify-content: center;
  padding: 7px 0;
}
.foot__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 6px 9px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-3);
  font-size: var(--fs-12);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.foot__btn:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.sidebar--collapsed .foot__btn {
  padding: 6px 0;
  width: 100%;
}

/* 帮助浮层 */
.pop {
  position: absolute;
  left: 14px;
  bottom: 84px;
  z-index: 60;
  width: 232px;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-10);
  box-shadow: var(--shadow-2);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sidebar--collapsed .pop {
  left: 8px;
}
.pop__row {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 6px 8px;
  border-radius: var(--r-6);
  font-size: var(--fs-12);
  color: var(--text-2);
}
.pop__row svg {
  color: var(--accent-text);
}
.pop__row:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.pop-enter-active,
.pop-leave-active {
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.pop-enter-from,
.pop-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

.hide-collapsed {
  transition: opacity var(--dur-fast) var(--ease);
}
.sidebar--collapsed .hide-collapsed {
  display: none;
}
</style>
