<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { mcpApi, skillApi, systemApi } from '@/api'
import type { McpServerView, SkillDef } from '@/api/types'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'
import { useToastStore } from '@/stores/toast'

const toast = useToastStore()
import Modal from '@/components/common/Modal.vue'

type TabKey = 'mcp' | 'skill'

const tab = ref<TabKey>('mcp')

/* ---------- MCP Server ---------- */
const mcpServers = ref<McpServerView[]>([])
const mcpErr = ref('')
const mcpBusy = ref('') // 正在操作中的 server id（防止连点）

interface McpForm {
  id?: string
  name: string
  description: string
  type: 'STDIO' | 'HTTP'
  command: string
  args: string
  endpointUrl: string
  enabled: boolean
}
const mcpEditorOpen = ref(false)
const mcpIsNew = ref(true)
const mcpForm = ref<McpForm>({
  name: '',
  description: '',
  type: 'STDIO',
  command: '',
  args: '',
  endpointUrl: '',
  enabled: true,
})

async function loadMcp() {
  try {
    mcpServers.value = await mcpApi.list()
    mcpErr.value = ''
  } catch (e) {
    mcpErr.value = (e as Error).message
  }
}

function openMcpNew() {
  mcpIsNew.value = true
  mcpForm.value = { name: '', description: '', type: 'STDIO', command: '', args: '', endpointUrl: '', enabled: true }
  mcpEditorOpen.value = true
}

function openMcpEdit(srv: McpServerView) {
  mcpIsNew.value = false
  mcpForm.value = {
    id: srv.id,
    name: srv.name,
    description: srv.description ?? '',
    type: srv.type === 'HTTP' ? 'HTTP' : 'STDIO',
    command: srv.command ?? '',
    args: (srv.args ?? []).join('\n'),
    endpointUrl: srv.endpointUrl ?? '',
    enabled: srv.enabled,
  }
  mcpEditorOpen.value = true
}

function splitList(s: string): string[] {
  return s
    .split(/[\n,，]/)
    .map((x) => x.trim())
    .filter(Boolean)
}

function slugify(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

async function saveMcp() {
  const f = mcpForm.value
  if (!f.name.trim()) return
  mcpBusy.value = '__form__'
  try {
    await mcpApi.save({
      id: f.id || slugify(f.name) || `mcp-${Date.now()}`,
      name: f.name.trim(),
      description: f.description.trim(),
      type: f.type,
      command: f.type === 'STDIO' ? f.command.trim() : '',
      args: f.type === 'STDIO' ? splitList(f.args) : [],
      endpointUrl: f.type === 'HTTP' ? f.endpointUrl.trim() : '',
      enabled: f.enabled,
    })
    mcpEditorOpen.value = false
    await loadMcp()
  } catch (e) {
    mcpErr.value = (e as Error).message
  } finally {
    mcpBusy.value = ''
  }
}

async function toggleMcp(srv: McpServerView) {
  mcpBusy.value = srv.id
  try {
    await mcpApi.setEnabled(srv.id, !srv.enabled)
    await loadMcp()
  } catch (e) {
    mcpErr.value = (e as Error).message
  } finally {
    mcpBusy.value = ''
  }
}

async function toggleAuth(srv: McpServerView) {
  mcpBusy.value = srv.id
  try {
    if (srv.authorized) await mcpApi.revoke(srv.id)
    else await mcpApi.authorize(srv.id)
    await loadMcp()
  } catch (e) {
    mcpErr.value = (e as Error).message
  } finally {
    mcpBusy.value = ''
  }
}

async function reconnectMcp(srv: McpServerView) {
  mcpBusy.value = srv.id
  try {
    await mcpApi.reconnect(srv.id)
    await loadMcp()
  } catch (e) {
    mcpErr.value = (e as Error).message
  } finally {
    mcpBusy.value = ''
  }
}

async function removeMcp(srv: McpServerView) {
  if (!confirm(`确认删除 MCP Server「${srv.name}」？`)) return
  mcpBusy.value = srv.id
  try {
    const r = await mcpApi.remove(srv.id)
    if (!r.removed) mcpErr.value = '删除失败（平台预置不可删除）'
    await loadMcp()
  } catch (e) {
    mcpErr.value = (e as Error).message
  } finally {
    mcpBusy.value = ''
  }
}

/* ---------- Skill ---------- */
const skills = ref<SkillDef[]>([])
const skillErr = ref('')
const skillBusy = ref('')

interface SkillForm {
  id?: string
  name: string
  description: string
  type: 'TOOL' | 'SUBAGENT'
  triggers: string
  entry: string
  sandbox: boolean
  enabled: boolean
  /** Level 2 正文（SKILL.md，保存时落盘）。 */
  skillMd: string
}
const skillEditorOpen = ref(false)
const skillIsNew = ref(true)
const skillForm = ref<SkillForm>({
  name: '',
  description: '',
  type: 'TOOL',
  triggers: '',
  entry: '',
  sandbox: false,
  enabled: true,
  skillMd: '',
})

async function loadSkills() {
  try {
    skills.value = await skillApi.list()
    skillErr.value = ''
  } catch (e) {
    skillErr.value = (e as Error).message
  }
}

function openSkillNew() {
  skillIsNew.value = true
  skillForm.value = { name: '', description: '', type: 'TOOL', triggers: '', entry: '', sandbox: false, enabled: true, skillMd: '' }
  skillEditorOpen.value = true
}

function openSkillEdit(s: SkillDef) {
  skillIsNew.value = false
  skillForm.value = {
    id: s.id,
    name: s.name,
    description: s.description ?? '',
    type: s.type === 'SUBAGENT' ? 'SUBAGENT' : 'TOOL',
    triggers: (s.triggers ?? []).join('，'),
    entry: s.entry ?? '',
    sandbox: s.sandbox,
    enabled: s.enabled,
    skillMd: s.skillMd ?? '',
  }
  skillEditorOpen.value = true
}

async function saveSkill() {
  const f = skillForm.value
  if (!f.name.trim()) return
  skillBusy.value = '__form__'
  try {
    await skillApi.save({
      id: f.id || slugify(f.name) || `skill-${Date.now()}`,
      name: f.name.trim(),
      description: f.description.trim(),
      type: f.type,
      triggers: splitList(f.triggers),
      sandbox: f.sandbox,
      enabled: f.enabled,
      entry: f.entry.trim(),
      deps: [],
      skillMd: f.skillMd,
    })
    skillEditorOpen.value = false
    await loadSkills()
  } catch (e) {
    skillErr.value = (e as Error).message
  } finally {
    skillBusy.value = ''
  }
}

async function toggleSkill(s: SkillDef) {
  skillBusy.value = s.id
  try {
    await skillApi.setEnabled(s.id, !s.enabled)
    await loadSkills()
  } catch (e) {
    skillErr.value = (e as Error).message
  } finally {
    skillBusy.value = ''
  }
}

async function reloadSkills() {
  skillBusy.value = '__reload__'
  try {
    await skillApi.reload()
    await loadSkills()
  } catch (e) {
    skillErr.value = (e as Error).message
  } finally {
    skillBusy.value = ''
  }
}

async function removeSkill(s: SkillDef) {
  if (!confirm(`确认删除 Skill「${s.name}」？`)) return
  skillBusy.value = s.id
  try {
    const r = await skillApi.remove(s.id)
    if (!r.removed) skillErr.value = '删除失败（平台预置不可删除）'
    await loadSkills()
  } catch (e) {
    skillErr.value = (e as Error).message
  } finally {
    skillBusy.value = ''
  }
}

/* ---------- 导入：统一入口（选文件夹 / 选文件） ---------- */
const uploadInput = ref<HTMLInputElement | null>(null)
/** 统一导入选择弹窗。 */
const importOpen = ref(false)

/** 点「导入」弹出统一选择界面（Skill 可选文件夹/文件；MCP 直接选文件）。 */
function openImport() {
  if (tab.value === 'mcp') {
    uploadInput.value?.click()
    return
  }
  importOpen.value = true
}

/** 选择「文件夹」：关闭选择弹窗，唤起本机原生目录选择框（后端代选）。 */
async function chooseDir() {
  importOpen.value = false
  skillBusy.value = '__importdir__'
  skillErr.value = ''
  try {
    const r = await systemApi.pickDirectory(undefined, '选择 Skill 目录（含 meta.json 或 SKILL.md）')
    if (r.cancelled || !r.path) return
    await onDirSelected(r.path)
  } catch (e) {
    skillErr.value = (e as Error).message
  } finally {
    skillBusy.value = ''
  }
}

/** 选择「文件」：关闭选择弹窗，唤起文件选择器。 */
function chooseFile() {
  importOpen.value = false
  uploadInput.value?.click()
}

/** 从本机目录导入 Skill：目录名即 id，须含 meta.json 或 SKILL.md。 */
async function onDirSelected(path: string) {
  if (!path) return
  skillBusy.value = '__importdir__'
  skillErr.value = ''
  try {
    const def = await skillApi.importDir(path)
    toast.success(`已从目录导入 Skill「${def.name || def.id}」`)
    await loadSkills()
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    skillBusy.value = ''
  }
}

async function onUploadPick(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  try {
    if (tab.value === 'mcp') {
      const def = await mcpApi.upload(file)
      toast.success(`已导入 MCP Server「${def.name || def.id}」`)
      await loadMcp()
    } else {
      const res = await skillApi.upload(file)
      toast.success(`已导入 ${res.count} 个 Skill${res.imported?.length ? '：' + res.imported.join('、') : ''}`)
      await loadSkills()
    }
  } catch (err) {
    toast.error((err as Error).message)
  } finally {
    input.value = ''
  }
}

function switchTab(k: TabKey) {
  tab.value = k
  if (k === 'mcp' && mcpServers.value.length === 0 && !mcpErr.value) loadMcp()
  if (k === 'skill' && skills.value.length === 0 && !skillErr.value) loadSkills()
}

function statusTone(status: string): 'teal' | 'warn' | 'danger' | 'neutral' {
  if (status === 'connected' || status === 'running' || status === 'online') return 'teal'
  if (status === 'connecting' || status === 'starting') return 'warn'
  if (status === 'disconnected' || status === 'failed' || status === 'error') return 'danger'
  return 'neutral'
}

onMounted(() => {
  loadMcp()
  loadSkills()
})
</script>

<template>
  <div class="plugins">
    <header class="plugins__head">
      <div class="plugins__tabs">
        <button class="plugins__tab" :class="{ 'plugins__tab--on': tab === 'mcp' }" @click="switchTab('mcp')">
          MCP Server <span v-if="mcpServers.length" class="plugins__count">{{ mcpServers.length }}</span>
        </button>
        <button class="plugins__tab" :class="{ 'plugins__tab--on': tab === 'skill' }" @click="switchTab('skill')">
          Skill <span v-if="skills.length" class="plugins__count">{{ skills.length }}</span>
        </button>
      </div>
      <div class="plugins__acts">
        <button class="plugins__icon" title="导入（选择文件夹或文件）" @click="openImport">
          <Icon name="download" :size="14" />
        </button>
        <button v-if="tab === 'skill'" class="plugins__icon" title="重载 Skill" @click="reloadSkills">
          <Icon name="refresh" :size="14" />
        </button>
        <button class="plugins__add" @click="tab === 'mcp' ? openMcpNew() : openSkillNew()">
          <Icon name="plus" :size="14" />
          <span>添加</span>
        </button>
      </div>
    </header>
    <input ref="uploadInput" type="file" class="hidden-file" accept=".zip,.json,.md" @change="onUploadPick" />

    <p v-if="tab === 'mcp' && mcpErr" class="plugins__err">{{ mcpErr }}</p>
    <p v-if="tab === 'skill' && skillErr" class="plugins__err">{{ skillErr }}</p>

    <!-- MCP 列表 -->
    <div v-if="tab === 'mcp'" class="plugins__list">
      <div v-if="mcpServers.length === 0 && !mcpErr" class="plugins__empty">
        <Icon name="box" :size="26" />
        <p>暂无 MCP Server，点击右上角「添加」新建。</p>
      </div>
      <div v-for="srv in mcpServers" :key="srv.id" class="card" :class="{ 'card--busy': mcpBusy === srv.id }">
        <div class="card__main" @click="openMcpEdit(srv)">
          <div class="card__row">
            <span class="card__name mono">{{ srv.name }}</span>
            <Badge>{{ srv.type }}</Badge>
            <Badge :dot="true" :tone="statusTone(srv.status)">{{ srv.status || '未知' }}</Badge>
            <Badge v-if="srv.authorized" tone="teal">已授权</Badge>
            <Badge v-else>未授权</Badge>
          </div>
          <p v-if="srv.description" class="card__desc">{{ srv.description }}</p>
          <p v-if="srv.type === 'STDIO'" class="card__code mono">{{ srv.command }} {{ (srv.args ?? []).join(' ') }}</p>
          <p v-else class="card__code mono">{{ srv.endpointUrl }}</p>
        </div>
        <div class="card__side">
          <button
            class="card__switch"
            :class="{ 'card__switch--on': srv.enabled }"
            :title="srv.enabled ? '禁用' : '启用'"
            @click="toggleMcp(srv)"
          >
            <span class="card__knob" />
          </button>
          <div class="card__acts">
            <button class="card__act" :title="srv.authorized ? '撤销授权' : '授权'" @click="toggleAuth(srv)">
              <Icon :name="srv.authorized ? 'eye' : 'key'" :size="13" />
            </button>
            <button class="card__act" title="重连" @click="reconnectMcp(srv)">
              <Icon name="refresh" :size="13" />
            </button>
            <button class="card__act card__act--danger" title="删除" @click="removeMcp(srv)">
              <Icon name="trash" :size="13" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Skill 列表 -->
    <div v-else class="plugins__list">
      <div v-if="skills.length === 0 && !skillErr" class="plugins__empty">
        <Icon name="spark" :size="26" />
        <p>暂无 Skill，点击右上角「添加」新建，或用「从本机目录导入 / 上传」。</p>
      </div>
      <div v-for="s in skills" :key="s.id" class="card" :class="{ 'card--busy': skillBusy === s.id }">
        <div class="card__main" @click="openSkillEdit(s)">
          <div class="card__row">
            <span class="card__name mono">{{ s.name }}</span>
            <Badge>{{ s.type }}</Badge>
            <Badge :tone="s.source === 'USER' ? 'accent' : 'neutral'">{{ s.source === 'USER' ? '用户' : '平台' }}</Badge>
          </div>
          <p v-if="s.description" class="card__desc">{{ s.description }}</p>
          <div v-if="s.triggers && s.triggers.length" class="card__tags">
            <span v-for="t in s.triggers" :key="t" class="tag">{{ t }}</span>
          </div>
          <p v-if="s.entry" class="card__code mono">{{ s.entry }}</p>
          <!-- E10：依赖缺失警告 -->
          <p v-if="s.missingDeps && s.missingDeps.length" class="card__warn">
            <Icon name="warning" :size="12" />
            依赖缺失：{{ s.missingDeps.join('、') }}（无法注入/启用）
          </p>
        </div>
        <div class="card__side">
          <button
            class="card__switch"
            :class="{ 'card__switch--on': s.enabled }"
            :title="s.missingDeps && s.missingDeps.length ? '依赖缺失，无法启用' : s.enabled ? '禁用' : '启用'"
            :disabled="!!(s.missingDeps && s.missingDeps.length) && !s.enabled"
            @click="toggleSkill(s)"
          >
            <span class="card__knob" />
          </button>
          <div class="card__acts">
            <button v-if="s.source !== 'PLATFORM'" class="card__act card__act--danger" title="删除" @click="removeSkill(s)">
              <Icon name="trash" :size="13" />
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- MCP 编辑弹窗 -->
    <Modal :open="mcpEditorOpen" :title="mcpIsNew ? '新增 MCP Server' : '编辑 MCP Server'" @close="mcpEditorOpen = false">
      <div class="form">
        <label class="field">
          <span class="field__label">名称 <i class="field__req">*</i></span>
          <input v-model="mcpForm.name" class="field__input" placeholder="如 fetch / 本机浏览器" />
        </label>
        <label class="field">
          <span class="field__label">描述</span>
          <textarea v-model="mcpForm.description" class="field__input" rows="2" placeholder="供 LLM 理解的语义描述" />
        </label>
        <div class="field">
          <span class="field__label">传输类型</span>
          <div class="field__seg">
            <button
              class="field__seg-item"
              :class="{ 'field__seg-item--on': mcpForm.type === 'STDIO' }"
              @click="mcpForm.type = 'STDIO'"
            >
              STDIO 本地进程
            </button>
            <button
              class="field__seg-item"
              :class="{ 'field__seg-item--on': mcpForm.type === 'HTTP' }"
              @click="mcpForm.type = 'HTTP'"
            >
              HTTP 远端
            </button>
          </div>
        </div>
        <template v-if="mcpForm.type === 'STDIO'">
          <label class="field">
            <span class="field__label">启动命令 <i class="field__req">*</i></span>
            <input v-model="mcpForm.command" class="field__input mono" placeholder="如 npx" />
          </label>
          <label class="field">
            <span class="field__label">参数（每行一个）</span>
            <textarea v-model="mcpForm.args" class="field__input mono" rows="3" placeholder="-y&#10;@modelcontextprotocol/server-fetch" />
          </label>
        </template>
        <template v-else>
          <label class="field">
            <span class="field__label">端点 URL <i class="field__req">*</i></span>
            <input v-model="mcpForm.endpointUrl" class="field__input mono" placeholder="https://host/sse" />
          </label>
        </template>
        <label class="field field--row">
          <input v-model="mcpForm.enabled" type="checkbox" />
          <span>创建后立即启用</span>
        </label>
        <div class="form__foot">
          <button class="btn btn--ghost" @click="mcpEditorOpen = false">取消</button>
          <button class="btn btn--primary" :disabled="mcpBusy === '__form__' || !mcpForm.name.trim()" @click="saveMcp">
            {{ mcpBusy === '__form__' ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </Modal>

    <!-- Skill 编辑弹窗 -->
    <Modal :open="skillEditorOpen" :title="skillIsNew ? '新增 Skill' : '编辑 Skill'" @close="skillEditorOpen = false">
      <div class="form">
        <label class="field">
          <span class="field__label">名称 <i class="field__req">*</i></span>
          <input v-model="skillForm.name" class="field__input" placeholder="如 PDF 处理" />
        </label>
        <label class="field">
          <span class="field__label">描述</span>
          <textarea v-model="skillForm.description" class="field__input" rows="2" placeholder="供语义匹配与模型理解的描述" />
        </label>
        <div class="field">
          <span class="field__label">类型</span>
          <div class="field__seg">
            <button
              class="field__seg-item"
              :class="{ 'field__seg-item--on': skillForm.type === 'TOOL' }"
              @click="skillForm.type = 'TOOL'"
            >
              TOOL 工具
            </button>
            <button
              class="field__seg-item"
              :class="{ 'field__seg-item--on': skillForm.type === 'SUBAGENT' }"
              @click="skillForm.type = 'SUBAGENT'"
            >
              SUBAGENT 子代理
            </button>
          </div>
        </div>
        <label class="field">
          <span class="field__label">触发词（逗号或换行分隔）</span>
          <textarea v-model="skillForm.triggers" class="field__input mono" rows="2" placeholder="pdf, 转换, 提取" />
        </label>
        <label class="field">
          <span class="field__label">入口命令模板（可选）</span>
          <input v-model="skillForm.entry" class="field__input mono" placeholder="如 python skill.py {args}" />
        </label>
        <label class="field">
          <span class="field__label">正文（SKILL.md，Level 2 指令，可选）</span>
          <textarea
            v-model="skillForm.skillMd"
            class="field__input mono"
            rows="6"
            placeholder="---&#10;name: ...&#10;description: ...&#10;---&#10;执行该技能的步骤说明…"
          />
        </label>
        <div class="field__checks">
          <label class="field field--row">
            <input v-model="skillForm.sandbox" type="checkbox" />
            <span>沙箱执行（需全权限工作空间）</span>
          </label>
          <label class="field field--row">
            <input v-model="skillForm.enabled" type="checkbox" />
            <span>启用</span>
          </label>
        </div>
        <div class="form__foot">
          <button class="btn btn--ghost" @click="skillEditorOpen = false">取消</button>
          <button class="btn btn--primary" :disabled="skillBusy === '__form__' || !skillForm.name.trim()" @click="saveSkill">
            {{ skillBusy === '__form__' ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </Modal>

    <!-- 统一导入选择：选文件夹 / 选文件 -->
    <Modal :open="importOpen" title="导入" width="420px" @close="importOpen = false">
      <div class="import-options">
        <button class="import-opt" @click="chooseDir">
          <span class="import-opt__icon"><Icon name="folder" :size="20" /></span>
          <span class="import-opt__main">
            <span class="import-opt__title">选择文件夹</span>
            <span class="import-opt__desc">从本机目录导入（含 meta.json 或 SKILL.md）</span>
          </span>
          <Icon name="chevronRight" :size="14" class="import-opt__go" />
        </button>
        <button class="import-opt" @click="chooseFile">
          <span class="import-opt__icon"><Icon name="upload" :size="20" /></span>
          <span class="import-opt__main">
            <span class="import-opt__title">选择文件</span>
            <span class="import-opt__desc">上传 .zip / meta.json / SKILL.md</span>
          </span>
          <Icon name="chevronRight" :size="14" class="import-opt__go" />
        </button>
      </div>
    </Modal>
  </div>
</template>

<style scoped>
/* 统一导入选择 */
.import-options {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.import-opt {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 14px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  color: var(--text-1);
  text-align: left;
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.import-opt:hover {
  border-color: var(--accent-border);
  background: var(--bg-0);
}
.import-opt__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--r-8);
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  color: var(--accent);
  flex-shrink: 0;
}
.import-opt__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.import-opt__title {
  font-size: var(--fs-14);
  font-weight: 600;
  color: var(--text-0);
}
.import-opt__desc {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.import-opt__go {
  color: var(--text-3);
  flex-shrink: 0;
}

.plugins {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
}
.plugins__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
  gap: 10px;
}
.plugins__tabs {
  display: flex;
  align-items: center;
  gap: 4px;
}
.plugins__tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: 1px solid transparent;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-3);
  font-size: var(--fs-13);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.plugins__tab:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.plugins__tab--on {
  color: var(--accent-text);
  background: var(--accent-dim);
  border-color: var(--accent-border);
}
.plugins__count {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 0 6px;
  border-radius: var(--r-pill);
  background: var(--bg-2);
}
.plugins__acts {
  display: flex;
  align-items: center;
  gap: 6px;
}
.plugins__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  background: none;
  border-radius: var(--r-6);
  color: var(--text-3);
}
.plugins__icon:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.plugins__add {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  border: 1px solid var(--accent-border);
  background: var(--accent-dim);
  color: var(--accent-text);
  border-radius: var(--r-6);
  font-size: var(--fs-13);
  font-weight: 500;
}
.plugins__add:hover {
  background: var(--accent);
  color: #fff;
}
.plugins__err {
  margin: 0;
  padding: 8px 16px;
  color: var(--danger-text);
  font-size: var(--fs-12);
  border-bottom: 1px solid var(--border);
}
.plugins__ok {
  margin: 0;
  padding: 8px 16px;
  color: var(--accent-text);
  font-size: var(--fs-12);
  border-bottom: 1px solid var(--border);
}
.hidden-file {
  display: none;
}
.plugins__list {
  flex: 1;
  overflow-y: auto;
  padding: 12px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.plugins__empty {
  margin: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  color: var(--text-3);
  font-size: var(--fs-13);
}
.plugins__empty svg {
  color: var(--text-3);
}

/* 卡片 */
.card {
  display: flex;
  align-items: stretch;
  gap: 10px;
  padding: 10px 12px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  transition: border-color var(--dur-fast) var(--ease);
}
.card--busy {
  opacity: 0.55;
  pointer-events: none;
}
.card:hover {
  border-color: var(--border-strong);
}
.card__main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.card__row {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.card__name {
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-0);
}
.card__desc {
  margin: 5px 0 0;
  font-size: var(--fs-12);
  color: var(--text-2);
}
.card__warn {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 6px 0 0;
  font-size: var(--fs-12);
  color: var(--danger-text);
}
.card__warn svg {
  flex-shrink: 0;
}
.card__code {
  margin: 5px 0 0;
  font-size: 11px;
  color: var(--text-3);
  word-break: break-all;
}
.card__tags {
  display: flex;
  align-items: center;
  gap: 5px;
  flex-wrap: wrap;
  margin-top: 6px;
}
.tag {
  font-size: 11px;
  color: var(--accent-text);
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  border-radius: var(--r-pill);
  padding: 1px 8px;
}
.card__side {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.card__switch {
  width: 34px;
  height: 18px;
  border-radius: 10px;
  border: 1px solid var(--border-strong);
  background: var(--bg-2);
  position: relative;
  padding: 0;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.card__switch--on {
  background: var(--accent);
  border-color: var(--accent);
}
.card__knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--text-2);
  transition: transform var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.card__switch--on .card__knob {
  transform: translateX(16px);
  background: #fff;
}
.card__acts {
  display: flex;
  align-items: center;
  gap: 2px;
}
.card__act {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  border-radius: var(--r-4);
  color: var(--text-3);
}
.card__act:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.card__act--danger:hover {
  color: var(--danger-text);
  background: var(--danger-dim);
}

/* 表单 */
.form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.field--row {
  flex-direction: row;
  align-items: center;
  gap: 8px;
  color: var(--text-2);
  font-size: var(--fs-13);
}
.field__label {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.field__req {
  color: var(--danger-text);
  font-style: normal;
}
.field__input {
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  padding: 7px 10px;
  font-size: var(--fs-13);
  color: var(--text-1);
  font-family: inherit;
  resize: vertical;
}
.field__input:focus {
  outline: none;
  border-color: var(--accent-border);
}
.mono.field__input {
  font-family: var(--font-mono);
  font-size: 12px;
}
.field__seg {
  display: flex;
  gap: 6px;
}
.field__seg-item {
  flex: 1;
  padding: 7px 0;
  border: 1px solid var(--border-strong);
  background: var(--bg-0);
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-12);
}
.field__seg-item--on {
  color: var(--accent-text);
  background: var(--accent-dim);
  border-color: var(--accent-border);
}
.field__checks {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 4px;
}
.btn {
  padding: 7px 16px;
  border-radius: var(--r-6);
  border: 1px solid transparent;
  font-size: var(--fs-13);
  cursor: pointer;
}
.btn--ghost {
  background: none;
  border-color: var(--border-strong);
  color: var(--text-2);
}
.btn--ghost:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.btn--primary {
  background: var(--accent);
  color: #fff;
}
.btn--primary:hover {
  background: var(--accent-strong);
}
.btn--primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
