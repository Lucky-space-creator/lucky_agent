<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'
import { modelApi, permissionApi, workspaceApi, systemApi } from '@/api'
import type { ModelConfig, PermissionRule, ProbeResult } from '@/api/types'
import { useModelStore } from '@/stores/model'
import { useWorkspaceStore } from '@/stores/workspace'
import { useThemeStore } from '@/stores/theme'
import { useToastStore } from '@/stores/toast'
import type { ThemeMode } from '@/stores/theme'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: [] }>()

const modelStore = useModelStore()
const workspace = useWorkspaceStore()
const theme = useThemeStore()
const toast = useToastStore()

const tabs = [
  { key: 'general', label: '通用设置', icon: 'sliders' },
  { key: 'model', label: '模型', icon: 'sparkles' },
  { key: 'preset', label: 'Agent 预设', icon: 'command' },
] as const
const tab = ref<(typeof tabs)[number]['key']>('general')

const themeOptions: { mode: ThemeMode; label: string }[] = [
  { mode: 'light', label: '浅色' },
  { mode: 'dark', label: '深色' },
  { mode: 'system', label: '跟随系统' },
]

const levelLabel = (level?: string) =>
  ({ READ_ONLY: '只读', MODIFY: '修改文件', FULL: '全部权限' }[level ?? 'MODIFY'] ?? '修改文件')

// ---- 工作空间 ----
const createOpen = ref(false)
const wsName = ref('')
const wsPath = ref('')
const wsLevel = ref<'READ_ONLY' | 'MODIFY' | 'FULL'>('MODIFY')
const creating = ref(false)
const wsError = ref('')

function openCreate() {
  wsName.value = ''
  wsPath.value = ''
  wsLevel.value = 'MODIFY'
  wsError.value = ''
  createOpen.value = true
}

/** 唤起本机原生目录选择框：路径一律由系统对话框选取，避免手输路径出错。 */
async function pickDirectory() {
  try {
    const r = await systemApi.pickDirectory(wsPath.value || undefined, '选择工作空间目录')
    if (r.cancelled || !r.path) return
    wsPath.value = r.path
    // 名称未填时用目录名回填，减少一次手工输入
    if (!wsName.value.trim()) {
      wsName.value = r.path.split(/[\\/]/).filter(Boolean).pop() ?? ''
    }
  } catch (e) {
    wsError.value = (e as Error).message
  }
}

async function doCreate() {
  if (!wsName.value || !wsPath.value) {
    toast.error('请填写名称与路径')
    return
  }
  creating.value = true
  try {
    await workspaceApi.create({ name: wsName.value, path: wsPath.value, permissionLevel: wsLevel.value })
    await workspace.load()
    createOpen.value = false
    toast.success('工作空间已创建')
  } catch (e) {
    wsError.value = (e as Error).message
  } finally {
    creating.value = false
  }
}

async function selectWorkspace(id: string) {
  workspace.setCurrent(id)
  await workspace.load()
}

async function removeWorkspace(id: string, name: string) {
  if (!confirm(`确认删除工作空间「${name}」的注册？（不会删除该文件夹内的文件）`)) return
  await workspaceApi.remove(id)
  await workspace.load()
  toast.success('工作空间注册已删除')
}

// ---- 模型 ----
const editOpen = ref(false)
const editing = ref<ModelConfig>({ name: '', endpointUrl: '', modelName: '', apiKey: '', role: 'main' })
const modelMsg = ref('')
/** 当前正在测试连接的端点 id（列表行内图标联动）。 */
const probingId = ref('')
/** 按端点 id 记录的探活结果（设置面板内展示）。 */
const probeResults = ref<Record<string, ProbeResult>>({})
/** Key 显示态：true=明文，false=掩码（默认，仅显示首尾、中间星号）。 */
const keyVisible = ref(false)
/** 用户是否手动改过 Key（未改动时保存传空字符串，由后端保留原 Key）。 */
const keyTouched = ref(false)
const keyLoading = ref(false)
/** 拉取 Key 的请求序号：防止连续打开多个端点时明文串值。 */
let keyReqId = 0

/** Key 掩码：保留前 6 位与后 4 位，中间星号（默认展示态，避免整串泄露）。 */
function maskKey(key: string): string {
  if (!key) return ''
  if (key.length <= 10) return '********'
  return `${key.slice(0, 6)}********${key.slice(-4)}`
}

async function openEdit(cfg?: ModelConfig) {
  keyReqId++
  const reqId = keyReqId
  keyVisible.value = false
  keyTouched.value = false
  keyLoading.value = false
  editing.value = cfg
    ? { ...cfg, apiKey: '' }
    : { name: '', endpointUrl: '', modelName: '', apiKey: '', role: 'main' }
  modelMsg.value = ''
  editOpen.value = true
  // 已配置 Key 的端点：单独拉取明文用于回显（列表接口不携带 Key，避免列表泄露）
  if (cfg?.id && cfg.keyConfigured) {
    keyLoading.value = true
    try {
      const r = await modelApi.key(cfg.id)
      if (reqId === keyReqId && r?.apiKey) {
        editing.value.apiKey = r.apiKey
      }
    } catch {
      // 拉取失败：保持空值，保存时留空即保留原 Key，不影响使用
    } finally {
      if (reqId === keyReqId) keyLoading.value = false
    }
  }
}

/** 用户输入 Key：视为已修改；未改动时保存传空、由后端保留原 Key。 */
function onKeyInput(e: Event) {
  keyTouched.value = true
  editing.value.apiKey = (e.target as HTMLInputElement).value
}

/** 小眼睛切换 Key 掩码/明文显示。 */
function toggleKey() {
  keyVisible.value = !keyVisible.value
}

async function saveModel() {
  // Key 未改动时传空字符串，后端「留空保留原 Key」；已改动则提交当前值
  const payload: ModelConfig = { ...editing.value, apiKey: keyTouched.value ? editing.value.apiKey : '' }
  try {
    await modelStore.save(payload)
    editOpen.value = false
    modelMsg.value = ''
    toast.success('模型端点已保存')
  } catch (e) {
    toast.error((e as Error).message)
  }
}

async function removeModel(id?: string) {
  if (!id) return
  if (!confirm('确认删除该模型端点？')) return
  await modelStore.remove(id)
  toast.success('模型端点已删除')
}

/** 测试单个已保存端点（列表行内图标），探测结果实时回填健康圆点。 */
async function testModel(cfg: ModelConfig) {
  if (!cfg.id) return
  probingId.value = cfg.id
  try {
    const res = await modelApi.probe({ id: cfg.id })
    const r = res.results?.[0]
    if (r) probeResults.value = { ...probeResults.value, [r.id ?? cfg.id]: r }
    if (r) {
      if (r.healthy) toast.success('连接成功')
      else toast.error('连接失败')
    }
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    probingId.value = ''
  }
}

/** 健康圆点提示文案：仅显示成功/失败，不展示 HTTP 状态码等技术细节。 */
function healthText(id?: string): string {
  const r = id ? probeResults.value[id] : undefined
  if (!r) return ''
  return r.healthy ? '连接正常' : '连接失败'
}

function healthClass(id?: string): string {
  const r = id ? probeResults.value[id] : undefined
  if (!r) return 'dot--idle'
  return r.healthy ? 'dot--ok' : 'dot--bad'
}

// ---- 权限规则 ----
const rules = ref<PermissionRule[]>([])

function loadRules() {
  return permissionApi.rules().then((r) => (rules.value = r))
}

const ruleEditOpen = ref(false)
const editingRule = ref<PermissionRule>(blankRule())

function blankRule(): PermissionRule {
  return {
    id: `rule-${Date.now()}`,
    priority: 100,
    type: 'PATH',
    matcher: { pattern: '/**', anchor: 'project' },
    action: 'ASK',
    reason: '',
  }
}

function openRuleNew() {
  editingRule.value = blankRule()
  ruleEditOpen.value = true
}

function openRuleEdit(r: PermissionRule) {
  editingRule.value = { ...r, matcher: { ...r.matcher } }
  ruleEditOpen.value = true
}

function confirmRule() {
  const r = editingRule.value
  if (!r.matcher.pattern.trim()) {
    toast.error('请填写匹配模式')
    return
  }
  const idx = rules.value.findIndex((x) => x.id === r.id)
  if (idx >= 0) rules.value[idx] = { ...r }
  else rules.value.push({ ...r })
  ruleEditOpen.value = false
}

async function saveRules() {
  try {
    await permissionApi.saveRules(rules.value)
    toast.success('权限规则已保存')
  } catch (e) {
    toast.error((e as Error).message)
  }
}

// ---- 配置文件 ----

async function openConfigFile() {
  try {
    const res = await systemApi.openSettingsFile()
    if (res.opened) toast.success('已在系统中打开配置文件')
    else toast.error('打开失败，请手动访问本机目录')
  } catch (e) {
    toast.error((e as Error).message)
  }
}

/** 打开规则文件（两级 LUCKY.md）：无参打开全局规则，传 workspaceId 打开对应项目规则。 */
async function openRules(workspaceId?: string) {
  try {
    const res = await systemApi.openRulesFile(workspaceId)
    if (res.opened) toast.success('已在系统中打开规则文件')
    else toast.info(res.message || '规则文件尚不存在')
  } catch (e) {
    toast.error((e as Error).message)
  }
}

// ---- Agent 预设 ----
const temperature = ref(0.7)
const maxSteps = ref(20)
const subAgents = ref('4')
const ctxWindow = ref('128k')
const autoRetry = ref(true)

// 打开时刷新数据
watch(
  () => props.open,
  async (v) => {
    if (v) {
      tab.value = 'general'
      await Promise.all([modelStore.load(), loadRules()])
    }
  },
)

onMounted(async () => {
  await modelStore.load()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="open" class="overlay" @click.self="emit('close')">
        <div class="settings" role="dialog" aria-modal="true" aria-label="设置">
          <!-- 头部 -->
          <header class="head">
            <div class="head__title">
              <span class="head__icon"><Icon name="sliders" :size="15" /></span>
              <span>设置</span>
            </div>
            <div class="head__acts">
              <button class="cfgbtn" @click="openConfigFile">
                <Icon name="external" :size="12" />
                <span>打开配置文件</span>
              </button>
              <button class="head__close" aria-label="关闭" @click="emit('close')">
                <Icon name="x" :size="15" />
              </button>
            </div>
          </header>

          <div class="body">
            <!-- 左导航 -->
            <nav class="snav">
              <button
                v-for="t in tabs"
                :key="t.key"
                class="snav__item"
                :class="{ 'snav__item--active': tab === t.key }"
                @click="tab = t.key"
              >
                <Icon :name="t.icon" :size="14" />
                <span>{{ t.label }}</span>
              </button>
            </nav>

            <!-- 右侧内容 -->
            <div class="spane">
              <!-- 通用设置 -->
              <section v-if="tab === 'general'" class="sg">
                <div class="sg__head">主题</div>
                <div class="theme-cards">
                  <button
                    v-for="t in themeOptions"
                    :key="t.mode"
                    class="theme-card"
                    :class="{ 'theme-card--on': theme.mode === t.mode }"
                    @click="theme.setMode(t.mode)"
                  >
                    <span class="theme-card__swatch" :class="`theme-card__swatch--${t.mode}`">
                      <span class="theme-card__sun"><Icon name="sun" :size="9" /></span>
                      <span class="theme-card__moon"><Icon name="moon" :size="9" /></span>
                    </span>
                    <span class="theme-card__label">{{ t.label }}</span>
                    <Icon v-if="theme.mode === t.mode" name="check" :size="13" class="theme-card__check" />
                  </button>
                </div>

                <div class="sg__head">语言</div>
                <select class="input input--select">
                  <option>简体中文</option>
                  <option>English</option>
                </select>

                <div class="sg__row">
                  <div class="sg__head">工作空间</div>
                  <button class="btn btn--primary btn--sm" @click="openCreate"><Icon name="plus" :size="12" /> 新建</button>
                </div>
                <div v-if="workspace.visible.length === 0" class="empty">
                  尚未创建工作空间（未创建时 Agent 产物落于本机默认目录）
                </div>
                <div v-else class="ws-list">
                  <button
                    v-for="w in workspace.visible"
                    :key="w.workspaceId"
                    class="ws"
                    :class="{ 'ws--active': w.workspaceId === workspace.currentId }"
                    @click="selectWorkspace(w.workspaceId)"
                  >
                    <span class="ws__icon"><Icon name="folder" :size="14" /></span>
                    <span class="ws__main">
                      <span class="ws__top">
                        <span class="ws__name">{{ w.name }}</span>
                        <Badge :tone="w.workspaceId === workspace.currentId ? 'accent' : 'neutral'">
                          {{ w.workspaceId === workspace.currentId ? '当前' : '切换' }}
                        </Badge>
                        <Badge tone="neutral">{{ levelLabel(w.permissionLevel) }}</Badge>
                      </span>
                      <span class="ws__meta mono">{{ w.path }}</span>
                    </span>
                    <span class="ws__del" @click.stop="removeWorkspace(w.workspaceId, w.name)" title="删除注册">
                      <Icon name="trash" :size="13" />
                    </span>
                  </button>
                </div>

                <div class="sg__row">
                  <div class="sg__head">规则（两级 LUCKY.md）</div>
                </div>
                <div class="rules-files">
                  <div class="rules-file">
                    <span class="rules-file__icon"><Icon name="book" :size="13" /></span>
                    <div class="rules-file__main">
                      <span class="rules-file__name">全局规则</span>
                      <span class="rules-file__path mono ellipsis">框架根 LUCKY.md（对所有项目生效）</span>
                    </div>
                    <button class="btn btn--ghost btn--sm" @click="openRules()">打开</button>
                  </div>
                  <div v-if="workspace.current" class="rules-file">
                    <span class="rules-file__icon"><Icon name="book" :size="13" /></span>
                    <div class="rules-file__main">
                      <span class="rules-file__name">项目规则（{{ workspace.current.name }}）</span>
                      <span class="rules-file__path mono ellipsis">{{ workspace.current.path }} 下的 LUCKY.md</span>
                    </div>
                    <button class="btn btn--ghost btn--sm" @click="openRules(workspace.current.workspaceId)">打开</button>
                  </div>
                </div>
                <p class="pane-hint text-3">
                  规则文件每次模型调用前读取、保存即生效；项目规则优先于全局规则。
                </p>

                <div class="sg__row">
                  <div class="sg__head">权限规则</div>
                  <div class="sg__acts">
                    <button class="btn btn--ghost btn--sm" @click="openRuleNew"><Icon name="plus" :size="12" /> 加规则</button>
                    <button class="btn btn--primary btn--sm" @click="saveRules">保存</button>
                  </div>
                </div>
                <div class="rules">
                  <button v-for="(r, i) in rules" :key="r.id" class="rule" @click="openRuleEdit(r)">
                    <span class="rule__summary">
                      <span class="rule__action" :class="`rule__action--${r.action.toLowerCase()}`">{{ r.action }}</span>
                      <span class="rule__type mono">{{ r.type }}</span>
                      <span class="rule__pat mono ellipsis">{{ r.matcher.anchor }}: {{ r.matcher.pattern }}</span>
                      <span class="rule__prio mono">P{{ r.priority }}</span>
                    </span>
                    <span class="rule__del" title="删除" @click.stop="rules.splice(i, 1)"><Icon name="trash" :size="13" /></span>
                  </button>
                  <div v-if="rules.length === 0" class="empty">暂无规则（默认按工作区权限级别），点击「加规则」新建</div>
                </div>
              </section>

              <!-- 模型 -->
              <section v-else-if="tab === 'model'" class="sg">
                <div class="sg__row">
                  <div class="sg__head">模型端点（点击列表项即可编辑并回填内容）</div>
                  <div class="sg__acts">
                    <button class="btn btn--primary btn--sm" @click="openEdit()"><Icon name="plus" :size="12" /> 新增</button>
                  </div>
                </div>

                <p v-if="modelMsg" class="spane__msg text-2">{{ modelMsg }}</p>
                <!-- 记忆管理 Agent 提示：显示当前实际生效的记忆总结模型（未配置时回退主力模型） -->
                <p v-if="modelStore.configs.length > 0" class="memory-hint">
                  <template v-if="modelStore.memoryModel">
                    会话记忆总结使用记忆管理 Agent：<span class="mono">{{ modelStore.memoryModel.modelName }}</span>
                    （仅用于记忆整理，不参与对话推理）
                  </template>
                  <template v-else>
                    当前未配置「记忆管理 Agent」：会话记忆总结将调用主力模型
                    <span class="mono">{{ modelStore.primary?.modelName || '主端点' }}</span>。
                    可在端点编辑中把某个端点角色设为「记忆管理 Agent」（推荐用低成本模型）。
                  </template>
                </p>
                <div v-if="modelStore.configs.length === 0" class="empty">尚未配置模型端点</div>
                <div v-else class="model-list">
                  <div v-for="cfg in modelStore.configs" :key="cfg.id" class="model model--clickable" @click="openEdit(cfg)">
                    <span class="model__dot" :class="healthClass(cfg.id)" :title="healthText(cfg.id)" />
                    <div class="model__icon"><Icon name="sparkles" :size="14" /></div>
                    <div class="model__main">
                      <div class="model__top">
                        <span class="model__name">{{ cfg.name || cfg.modelName || '未命名端点' }}</span>
                        <Badge v-if="cfg.role === 'main'" tone="accent">主端点</Badge>
                        <Badge v-else-if="cfg.role === 'memory'" tone="teal">记忆管理 Agent</Badge>
                        <Badge v-else tone="neutral">备用</Badge>
                        <Badge :tone="cfg.enabled ? 'teal' : 'neutral'">{{ cfg.enabled ? '启用' : '停用' }}</Badge>
                      </div>
                      <div class="model__meta mono">{{ cfg.modelName }} · {{ cfg.endpointUrl }}<span v-if="cfg.role === 'memory'"> · 仅用于记忆总结</span></div>
                    </div>
                    <div class="model__actions" @click.stop>
                      <button
                        class="icon-c icon-c--probe"
                        :title="healthText(cfg.id) || '测试连接'"
                        :disabled="probingId === cfg.id"
                        @click="testModel(cfg)"
                      >
                        <Icon name="refresh" :size="13" :class="{ spin: probingId === cfg.id }" />
                      </button>
                      <button class="icon-c icon-c--danger" title="删除" @click="removeModel(cfg.id)"><Icon name="trash" :size="13" /></button>
                    </div>
                  </div>
                </div>
              </section>

              <!-- Agent 预设 -->
              <section v-else class="sg">
                <div class="sg__head">生成参数</div>
                <div class="field">
                  <div class="field__row">
                    <label class="field__label">温度</label>
                    <span class="field__val mono">{{ temperature.toFixed(1) }}</span>
                  </div>
                  <input v-model.number="temperature" type="range" min="0" max="2" step="0.1" class="range" />
                </div>
                <div class="field">
                  <div class="field__row">
                    <label class="field__label">最大步数</label>
                    <span class="field__val mono">{{ maxSteps }}</span>
                  </div>
                  <input v-model.number="maxSteps" type="range" min="1" max="50" step="1" class="range" />
                </div>
                <div class="field-row">
                  <label class="field">
                    <span class="field__label">并行子代理</span>
                    <select v-model="subAgents" class="input input--select">
                      <option value="1">1（串行）</option>
                      <option value="2">2</option>
                      <option value="4">4</option>
                      <option value="8">8</option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="field__label">上下文窗口</span>
                    <select v-model="ctxWindow" class="input input--select">
                      <option value="32k">32k</option>
                      <option value="128k">128k</option>
                      <option value="200k">200k</option>
                    </select>
                  </label>
                </div>
                <div class="field field--switch">
                  <div class="field__row">
                    <label class="field__label">失败步骤自动重试</label>
                    <button
                      type="button"
                      class="switch"
                      :class="{ 'switch--on': autoRetry }"
                      role="switch"
                      :aria-checked="autoRetry"
                      @click="autoRetry = !autoRetry"
                    >
                      <span class="switch__knob" />
                    </button>
                  </div>
                </div>
                <p class="pane-hint text-3">预设将作为后续 Agent 任务的默认参数（本地生效）。</p>
              </section>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- 模型端点编辑 -->
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="editOpen" class="overlay" @click.self="editOpen = false">
        <div class="mini" role="dialog" aria-label="模型端点">
          <header class="head">
            <div class="head__title">
              <span class="head__icon"><Icon name="sparkles" :size="15" /></span>
              <span>模型端点</span>
            </div>
            <button class="head__close" aria-label="关闭" @click="editOpen = false"><Icon name="x" :size="15" /></button>
          </header>
          <div class="mini__body">
            <div class="form">
              <label class="field">
                <span class="field__label">名称</span>
                <input v-model="editing.name" class="input" placeholder="main" />
              </label>
              <label class="field">
                <span class="field__label">端点 URL（base_url 或完整地址）</span>
                <input v-model="editing.endpointUrl" class="input mono" placeholder="https://api.deepseek.com/anthropic 或 https://api.deepseek.com" />
              </label>
              <p class="pane-hint text-3">
                填写厂商 <b>base_url</b>，接口路径由 LangChain4j 官方 SDK 自动拼接：
                OpenAI 兼容填 <span class="mono">https://api.deepseek.com</span> 或
                <span class="mono">https://api.deepseek.com/v1</span>（自动补
                <span class="mono">/chat/completions</span>）；Anthropic 兼容填
                <span class="mono">https://api.deepseek.com/anthropic</span>（自动补
                <span class="mono">/messages</span>）。系统按 URL 自动识别格式。
              </p>
              <label class="field">
                <span class="field__label">模型名</span>
                <input v-model="editing.modelName" class="input mono" placeholder="deepseek-v4-flash" />
              </label>
              <label class="field">
                <span class="field__label">API Key（明文存于本机 settings.json）</span>
                <div class="key-field">
                  <input
                    :value="keyVisible ? editing.apiKey : maskKey(editing.apiKey ?? '')"
                    :readonly="!keyVisible"
                    class="input mono key-field__input"
                    :placeholder="editing.keyConfigured ? '已配置，留空则保持不变' : 'sk-…'"
                    autocomplete="off"
                    @input="onKeyInput"
                  />
                  <button
                    type="button"
                    class="key-field__eye"
                    :disabled="keyLoading || !editing.apiKey"
                    :title="keyVisible ? '隐藏 Key（掩码显示）' : '显示 Key 明文'"
                    @click="toggleKey"
                  >
                    <Icon :name="keyVisible ? 'eyeOff' : 'eye'" :size="15" />
                  </button>
                </div>
                <p class="pane-hint text-3">
                  默认仅显示首尾部分（中间掩码），点击小眼睛可查看完整明文，仅本机可见。
                </p>
              </label>
              <label class="field">
                <span class="field__label">角色</span>
                <select v-model="editing.role" class="input input--select">
                  <option value="main">主端点</option>
                  <option value="fallback">备用端点</option>
                  <option value="memory">记忆管理 Agent</option>
                </select>
              </label>
              <p v-if="editing.role === 'memory'" class="pane-hint text-3">
                记忆管理 Agent 仅用于会话记忆总结，不参与主链路推理；未配置时总结回退主力模型。建议使用低成本/快模型。
              </p>
              <div class="field-row">
                <label class="field">
                  <span class="field__label">上下文窗口（参考，可留空）</span>
                  <input v-model.number="editing.contextWindow" type="number" class="input mono" placeholder="留空则不限制" />
                </label>
                <label class="field">
                  <span class="field__label">最大输出 tokens（可留空）</span>
                  <input v-model.number="editing.maxTokens" type="number" class="input mono" placeholder="留空则用模型默认" />
                </label>
              </div>
              <p v-if="modelMsg" class="form__error">{{ modelMsg }}</p>
              <div class="form__actions">
                <span class="form__spacer" />
                <button class="btn btn--ghost" @click="editOpen = false">取消</button>
                <button class="btn btn--primary" @click="saveModel">保存</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- 新建工作空间 -->
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="createOpen" class="overlay" @click.self="createOpen = false">
        <div class="mini" role="dialog" aria-label="新建工作空间">
          <header class="head">
            <div class="head__title">
              <span class="head__icon"><Icon name="folderPlus" :size="15" /></span>
              <span>新建工作空间</span>
            </div>
            <button class="head__close" aria-label="关闭" @click="createOpen = false"><Icon name="x" :size="15" /></button>
          </header>
          <div class="mini__body">
            <div class="form">
              <label class="field">
                <span class="field__label">名称</span>
                <input v-model="wsName" class="input" placeholder="如 demo-project" />
              </label>
              <label class="field">
                <span class="field__label">本机路径</span>
                <div class="path-row">
                  <input v-model="wsPath" class="input mono" placeholder="点击「浏览」选择本机目录" />
                  <button class="btn btn--ghost btn--sm path-row__btn" @click="pickDirectory">
                    <Icon name="folder" :size="12" />
                    <span>浏览</span>
                  </button>
                </div>
              </label>
              <label class="field">
                <span class="field__label">权限级别</span>
                <select v-model="wsLevel" class="input input--select">
                  <option value="READ_ONLY">只读</option>
                  <option value="MODIFY">修改文件</option>
                  <option value="FULL">全部权限</option>
                </select>
              </label>
              <p class="pane-hint text-2">路径从本机目录选择框选取；该路径下会自动生成 skills / mcp / memory / config 目录，可直接编辑。</p>
              <p v-if="wsError" class="form__error">{{ wsError }}</p>
              <div class="form__actions">
                <button class="btn btn--ghost" @click="createOpen = false">取消</button>
                <button class="btn btn--primary" :disabled="creating" @click="doCreate">
                  {{ creating ? '创建中…' : '创建' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <!-- 规则编辑弹窗 -->
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="ruleEditOpen" class="overlay" @click.self="ruleEditOpen = false">
        <div class="mini" role="dialog" aria-label="编辑权限规则">
          <header class="head">
            <div class="head__title">
              <span class="head__icon"><Icon name="shield" :size="15" /></span>
              <span>{{ rules.some((x) => x.id === editingRule.id) ? '编辑规则' : '新建规则' }}</span>
            </div>
            <button class="head__close" aria-label="关闭" @click="ruleEditOpen = false"><Icon name="x" :size="15" /></button>
          </header>
          <div class="mini__body">
            <div class="form">
              <label class="field">
                <span class="field__label">规则 id</span>
                <input v-model="editingRule.id" class="input mono" placeholder="如 deny-secrets" />
              </label>
              <div class="field-row">
                <label class="field">
                  <span class="field__label">类型</span>
                  <select v-model="editingRule.type" class="input input--select">
                    <option value="PATH">PATH 路径</option>
                    <option value="COMMAND">COMMAND 命令</option>
                  </select>
                </label>
                <label class="field">
                  <span class="field__label">动作</span>
                  <select v-model="editingRule.action" class="input input--select">
                    <option value="ALLOW">ALLOW 放行</option>
                    <option value="DENY">DENY 拒绝</option>
                    <option value="ASK">ASK 询问</option>
                  </select>
                </label>
              </div>
              <div class="field-row">
                <label class="field">
                  <span class="field__label">优先级（数值越小越先）</span>
                  <input v-model.number="editingRule.priority" type="number" class="input mono" />
                </label>
                <label class="field">
                  <span class="field__label">锚定</span>
                  <select v-model="editingRule.matcher.anchor" class="input input--select">
                    <option value="project">project 工作区根</option>
                    <option value="home">home 用户目录</option>
                    <option value="//abs">//abs 绝对路径</option>
                    <option value="relative">relative 相对</option>
                  </select>
                </label>
              </div>
              <label class="field">
                <span class="field__label">匹配模式（glob 或 regex: 前缀）</span>
                <input v-model="editingRule.matcher.pattern" class="input mono" placeholder="/** 或 regex:^\\.env$" />
              </label>
              <label class="field">
                <span class="field__label">原因说明（可选）</span>
                <input v-model="editingRule.reason" class="input" placeholder="该规则的目的，便于审计" />
              </label>
              <p class="pane-hint text-2">规则按优先级升序评估，DENY 永远胜出。</p>
              <div class="form__actions">
                <button class="btn btn--ghost" @click="ruleEditOpen = false">取消</button>
                <button class="btn btn--primary" @click="confirmRule">确定</button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 95;
  background: rgba(15, 23, 42, 0.28);
  -webkit-backdrop-filter: blur(4px);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 5vh 16px;
}

/* 主面板：左右分栏 */
.settings {
  width: 760px;
  max-width: 96vw;
  height: min(76vh, 640px);
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-12);
  box-shadow: var(--shadow-2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
}
.head__title {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: var(--fs-15);
  font-weight: 600;
  color: var(--text-0);
}
.head__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-8);
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  color: var(--accent);
}
.head__acts {
  display: flex;
  align-items: center;
  gap: 6px;
}
.cfgbtn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 11px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
    background var(--dur-fast) var(--ease);
}
.cfgbtn:hover {
  border-color: var(--accent-border);
  color: var(--accent-text);
  background: var(--accent-dim);
}
.head__close {
  display: flex;
  padding: 5px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-6);
}
.head__close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.body {
  flex: 1;
  min-height: 0;
  display: flex;
}

/* 左导航 */
.snav {
  width: 168px;
  flex-shrink: 0;
  padding: 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-right: 1px solid var(--border);
  background: var(--bg-1);
}
.snav__item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 9px 11px;
  border: none;
  background: none;
  border-radius: var(--r-8);
  color: var(--text-2);
  font-size: var(--fs-13);
  text-align: left;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.snav__item svg {
  color: var(--text-3);
  flex-shrink: 0;
}
.snav__item:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.snav__item--active {
  background: var(--accent-dim);
  color: var(--accent-text);
  font-weight: 500;
}
.snav__item--active svg {
  color: var(--accent);
}
.snav__item--active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 8px;
  bottom: 8px;
  width: 3px;
  border-radius: var(--r-pill);
  background: var(--accent);
}

/* 右侧内容 */
.spane {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 20px 24px 26px;
}
.spane__msg {
  font-size: var(--fs-12);
  margin-bottom: 12px;
}
.sg {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sg__head {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--text-2);
  letter-spacing: 0.02em;
}
.sg__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
}
.sg__acts {
  display: flex;
  gap: 6px;
}

/* 主题卡片 */
.theme-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.theme-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 14px 10px 12px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-10);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease);
}
.theme-card:hover {
  border-color: var(--border-strong);
  transform: translateY(-1px);
}
.theme-card--on {
  border-color: var(--accent-border);
  background: var(--accent-dim);
  color: var(--accent-text);
  font-weight: 500;
}
.theme-card__swatch {
  width: 44px;
  height: 30px;
  border-radius: var(--r-6);
  border: 1px solid var(--border-strong);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: #ffffff;
}
.theme-card__swatch--dark {
  background: #0b0e14;
}
.theme-card__swatch--system {
  background: linear-gradient(90deg, #ffffff 0%, #ffffff 50%, #0b0e14 50%, #0b0e14 100%);
}
.theme-card__sun {
  display: flex;
  color: #d97706;
}
.theme-card__moon {
  display: flex;
  color: #7dd3fc;
}
.theme-card__check {
  position: absolute;
  top: 8px;
  right: 8px;
  color: var(--accent);
}

/* 工作空间 */
.ws-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.ws {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  padding: 10px 12px;
  text-align: left;
  color: var(--text-1);
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.ws:hover {
  border-color: var(--border-strong);
}
.ws--active {
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.ws__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-8);
  background: var(--bg-0);
  border: 1px solid var(--border);
  color: var(--accent-text);
  flex-shrink: 0;
}
.ws__main {
  flex: 1;
  min-width: 0;
}
.ws__top {
  display: flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
}
.ws__name {
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-0);
}
.ws__meta {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ws__del {
  display: flex;
  padding: 5px;
  color: var(--text-3);
  border-radius: var(--r-4);
  flex-shrink: 0;
}
.ws__del:hover {
  color: var(--danger-text);
  background: var(--bg-2);
}

/* 模型 */
.model-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.model {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  padding: 10px 12px;
}
.model--clickable {
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.model--clickable:hover {
  border-color: var(--accent-border);
  background: var(--bg-0);
}
.model__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-8);
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  color: var(--accent);
  flex-shrink: 0;
}
.model__main {
  flex: 1;
  min-width: 0;
}
.model__top {
  display: flex;
  align-items: center;
  gap: 7px;
}
.model__name {
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-0);
}
.model__meta {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model__actions {
  display: flex;
  gap: 2px;
}
.model__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.model__dot--idle {
  background: var(--bg-3);
}
.model__dot--ok {
  background: #4ade80;
}
.model__dot--bad {
  background: var(--danger);
}

/* 开关 */
.switch {
  position: relative;
  width: 34px;
  height: 19px;
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
  width: 13px;
  height: 13px;
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
  transform: translateX(15px);
}

/* 路径选择行：本机目录由后端弹框选取，输入框仅作展示与兜底手填 */
.path-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.path-row .input {
  flex: 1;
  min-width: 0;
}
.path-row__btn {
  flex-shrink: 0;
  white-space: nowrap;
}

/* 预设表单 */
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.field-row {
  display: flex;
  gap: 12px;
}
.field-row .field {
  flex: 1;
}
.field__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.field__label {
  font-size: var(--fs-12);
  color: var(--text-2);
}
.field__val {
  font-size: var(--fs-12);
  color: var(--accent-text);
  font-weight: 600;
}
.field--switch .field__row {
  justify-content: flex-start;
  gap: 10px;
}
.range {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 4px;
  border-radius: var(--r-pill);
  background: var(--bg-3);
  outline: none;
}
.range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--accent);
  border: 2.5px solid #fff;
  box-shadow: var(--shadow-1);
  cursor: pointer;
}
.range::-moz-range-thumb {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: var(--accent);
  border: 2.5px solid #fff;
  cursor: pointer;
}
.pane-hint {
  font-size: var(--fs-12);
}
/* 记忆管理 Agent 未配置提示（浅色强调，提醒回退主力模型） */
.memory-hint {
  margin: 4px 0 10px;
  padding: 8px 10px;
  font-size: var(--fs-12);
  line-height: 1.5;
  color: var(--text-2);
  background: color-mix(in srgb, var(--accent) 8%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent) 25%, transparent);
  border-radius: var(--r-8);
}

/* 规则文件（两级 LUCKY.md） */
.rules-files {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rules-file {
  display: flex;
  align-items: center;
  gap: 10px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  padding: 9px 12px;
}
.rules-file__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-6);
  background: var(--bg-0);
  border: 1px solid var(--border);
  color: var(--accent-text);
  flex-shrink: 0;
}
.rules-file__main {
  flex: 1;
  min-width: 0;
}
.rules-file__name {
  display: block;
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--text-0);
}
.rules-file__path {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--text-3);
}

/* 规则 */
.rules {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.rule {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  padding: 8px 10px;
  text-align: left;
  color: var(--text-1);
  font-size: var(--fs-12);
  cursor: pointer;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.rule:hover {
  border-color: var(--accent-border);
  background: var(--bg-0);
}
.rule__summary {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}
.rule__action {
  padding: 2px 7px;
  border-radius: var(--r-4);
  font-weight: 600;
  font-size: 11px;
  flex-shrink: 0;
}
.rule__action--allow {
  background: var(--teal-dim, rgba(52, 211, 153, 0.15));
  color: var(--teal, #34d399);
}
.rule__action--deny {
  background: var(--danger-dim);
  color: var(--danger-text);
}
.rule__action--ask {
  background: var(--accent-dim);
  color: var(--accent-text);
}
.rule__type {
  color: var(--text-3);
  font-size: 11px;
  flex-shrink: 0;
}
.rule__pat {
  flex: 1;
  min-width: 0;
  color: var(--text-2);
}
.rule__prio {
  color: var(--text-3);
  font-size: 11px;
  flex-shrink: 0;
}
.rule__del {
  display: flex;
  padding: 4px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-4);
  flex-shrink: 0;
}
.rule__del:hover {
  color: var(--danger-text);
  background: var(--danger-dim);
}

/* 通用 */
.empty {
  font-size: var(--fs-12);
  padding: 22px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-8);
  text-align: center;
  color: var(--text-3);
}
.icon-c {
  display: flex;
  padding: 5px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-4);
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.icon-c:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.icon-c--danger:hover {
  color: var(--danger-text);
}
.icon-c--probe:hover {
  color: var(--accent-text);
}
.icon-c:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 13px;
  border-radius: var(--r-6);
  font-size: var(--fs-13);
  font-weight: 500;
  border: 1px solid transparent;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease),
    transform var(--dur-fast) var(--ease);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn--sm {
  padding: 5px 10px;
  font-size: var(--fs-12);
}
.btn--primary {
  background: var(--accent);
  color: #fff;
}
.btn--primary:hover:not(:disabled) {
  background: var(--accent-strong);
}
.btn--ghost {
  background: var(--bg-1);
  border-color: var(--border);
  color: var(--text-2);
}
.btn--ghost:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--text-1);
}

/* 表单（迷你弹窗内） */
.form {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.input {
  background: var(--bg-1);
  border: 1px solid var(--border-strong);
  color: var(--text-0);
  border-radius: var(--r-6);
  padding: 8px 10px;
  font-size: var(--fs-13);
}
.input:focus {
  outline: none;
  border-color: var(--accent-border);
  box-shadow: var(--glow-accent);
}
.input--select {
  -webkit-appearance: none;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--text-3) 50%),
    linear-gradient(135deg, var(--text-3) 50%, transparent 50%);
  background-position: calc(100% - 16px) 50%, calc(100% - 11px) 50%;
  background-size: 5px 5px;
  background-repeat: no-repeat;
  padding-right: 28px;
  cursor: pointer;
}
/* API Key 输入：掩码/明文切换，小眼睛按钮内嵌右侧 */
.key-field {
  position: relative;
}
.key-field__input {
  width: 100%;
  padding-right: 36px;
}
.key-field__eye {
  position: absolute;
  right: 5px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  padding: 4px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-4);
  cursor: pointer;
}
.key-field__eye:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.key-field__eye:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.form__error {
  color: var(--danger-text);
  font-size: var(--fs-12);
}
.form__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}
.form__spacer {
  flex: 1;
}
.spin {
  animation: spin 1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}

/* 迷你弹窗 */
.mini {
  width: 460px;
  max-width: 94vw;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-12);
  box-shadow: var(--shadow-2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.mini__body {
  padding: 18px 20px;
  max-height: 68vh;
  overflow-y: auto;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--dur-med) var(--ease);
}
.fade-enter-active .settings,
.fade-enter-active .mini,
.fade-leave-active .settings,
.fade-leave-active .mini {
  transition: transform var(--dur-med) var(--ease);
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
.fade-enter-from .settings,
.fade-enter-from .mini,
.fade-leave-to .settings,
.fade-leave-to .mini {
  transform: translateY(-8px) scale(0.98);
}
</style>
