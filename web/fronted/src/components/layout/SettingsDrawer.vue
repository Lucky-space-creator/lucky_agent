<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'
import { modelApi, permissionApi, workspaceApi, systemApi, ruleApi, presetApi } from '@/api'
import type { ModelConfig, ModelUsage, PermissionRule, ProbeResult, RuleItem, AgentPresetBundle } from '@/api/types'
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

// ---- 模型用量统计与详情小窗 ----
/** 全部端点用量（列表角标 + 详情小窗数据源），打开设置面板时拉取。 */
const usages = ref<Record<string, ModelUsage>>({})

async function loadUsages() {
  try {
    usages.value = await modelApi.usages()
  } catch {
    usages.value = {}
  }
}

/** 千分位数字格式（用量统计用）。 */
function formatCount(n?: number): string {
  if (!n || n <= 0) return '0'
  return n.toLocaleString('zh-CN')
}

/** token 数可读化：204800 → 200K，1048576 → 1M（1024 进位，与配置预设一致）。 */
function tokensLabel(v?: number | null): string {
  if (v === undefined || v === null || v <= 0) return ''
  if (v >= 1048576) {
    const m = v / 1048576
    return `${Number.isInteger(m) ? m : m.toFixed(1)}M`
  }
  if (v >= 1024) {
    const k = v / 1024
    return `${Number.isInteger(k) ? k : k.toFixed(1)}K`
  }
  return String(v)
}

/** 最近调用时间可读化（无记录显示 —）。 */
function fmtTime(ts?: number): string {
  if (!ts) return '—'
  const d = new Date(ts)
  const p = (x: number) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/**
 * 上下文窗口 / 最大输出预设（点击即填充，另有「自定义」数值输入框兜底）。
 * 「不限制」置空表示用模型默认/不截断。
 */
const contextPresets: { label: string; v?: number }[] = [
  { label: '8K', v: 8192 },
  { label: '16K', v: 16384 },
  { label: '32K', v: 32768 },
  { label: '64K', v: 65536 },
  { label: '128K', v: 131072 },
  { label: '200K', v: 204800 },
  { label: '1M', v: 1048576 },
]
const outputPresets: { label: string; v?: number }[] = [
  { label: '1K', v: 1024 },
  { label: '2K', v: 2048 },
  { label: '4K', v: 4096 },
  { label: '8K', v: 8192 },
  { label: '16K', v: 16384 },
  { label: '32K', v: 32768 },
]

/** 预设是否命中当前值（「不限制」命中 空/0）。 */
function chipActive(cur: number | undefined | null, v?: number): boolean {
  if (v === undefined) return cur === undefined || cur === null || cur === 0
  return cur === v
}

// ---- 模型详情/用量小窗 ----
const detailOpen = ref(false)
/** 详情小窗内可编辑的配置副本（保存后同步回配置）。 */
const detailCfg = ref<ModelConfig | null>(null)
/** 当前详情端点的用量统计。 */
const detailUsage = ref<ModelUsage | null>(null)
const usageLoading = ref(false)
let detailReqId = 0

async function openDetail(cfg: ModelConfig) {
  detailReqId++
  const reqId = detailReqId
  detailCfg.value = { ...cfg }
  detailUsage.value = null
  detailOpen.value = true
  await loadDetailUsage(cfg.id, reqId)
}

/** 拉取指定端点用量（请求序号防串值：连续打开不同端点时只采用最后一次）。 */
async function loadDetailUsage(id?: string, reqId?: number) {
  const my = reqId ?? detailReqId
  if (!id) {
    detailUsage.value = null
    return
  }
  usageLoading.value = true
  try {
    const u = await modelApi.usage(id)
    if (my === detailReqId) detailUsage.value = u
  } catch {
    if (my === detailReqId) detailUsage.value = null
  } finally {
    if (my === detailReqId) usageLoading.value = false
  }
}

/** 保存详情小窗内的上下文/输出配置，并同步刷新列表用量角标。 */
async function saveDetail() {
  if (!detailCfg.value) return
  try {
    const payload: ModelConfig = { ...detailCfg.value, apiKey: '' }
    await modelStore.save(payload)
    // 保存后同步列表里的本地副本（上下文等字段回填，Key 保持脱敏）
    await loadUsages()
    toast.success('模型配置已同步')
  } catch (e) {
    toast.error((e as Error).message)
  }
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

// ---- 多条规则（全局 / 项目） ----
/** 规则编辑范围：全局 or 当前工作空间项目。 */
const ruleScope = ref<'global' | 'project'>('global')
/** 当前作用域下的规则列表（编辑副本，保存时整表提交）。 */
const ruleItems = ref<RuleItem[]>([])
/** 每个作用域的规则列表缓存（切换作用域时避免重复请求）。 */
const ruleCache = ref<Record<'global' | 'project', RuleItem[]>>({ global: [], project: [] })
const ruleLoading = ref(false)
const ruleSaving = ref(false)
/** 规则存储形态（single-file / multi-file），用于面板底部提示。 */
const ruleStorage = ref<'single-file' | 'multi-file'>('single-file')

/** 拉取规则（全局 + 项目一次取回，分别缓存）。 */
async function loadRuleItems() {
  ruleLoading.value = true
  try {
    const bundle = await ruleApi.list(workspace.current?.workspaceId)
    ruleStorage.value = bundle.storage
    ruleCache.value = {
      global: (bundle.global ?? []).map((r) => ({ ...r })),
      project: (bundle.project ?? []).map((r) => ({ ...r })),
    }
    ruleItems.value = ruleCache.value[ruleScope.value]
  } catch (e) {
    toast.error((e as Error).message)
    ruleCache.value = { global: [], project: [] }
    ruleItems.value = []
  } finally {
    ruleLoading.value = false
  }
}

/** 切换作用域：优先用本地缓存，避免多余请求。 */
function switchRuleScope(scope: 'global' | 'project') {
  ruleScope.value = scope
  ruleItems.value = ruleCache.value[scope]
}

/** 新增一条空白规则（名称可留待用户填写，保存前做非空校验）。 */
function addRule() {
  ruleItems.value.push({
    name: '',
    content: '',
    enabled: true,
    scope: ruleScope.value,
  })
}

/** 删除规则（本地删除，点保存后落盘；作用于当前作用域）。 */
function removeRule(index: number) {
  ruleItems.value.splice(index, 1)
}

/** 保存当前作用域规则并同步缓存。 */
async function saveRuleItems() {
  if (ruleScope.value === 'project' && !workspace.current) {
    toast.error('请先选择工作空间')
    return
  }
  const bad = ruleItems.value.findIndex((r) => !r.name.trim())
  if (bad >= 0) {
    toast.error(`第 ${bad + 1} 条规则缺少名称`)
    return
  }
  const empty = ruleItems.value.findIndex((r) => !r.content.trim())
  if (empty >= 0) {
    toast.error(`第 ${empty + 1} 条规则内容为空`)
    return
  }
  ruleSaving.value = true
  try {
    const payload = {
      workspaceId: ruleScope.value === 'project' ? workspace.current?.workspaceId : undefined,
      scope: ruleScope.value,
      rules: ruleItems.value.map((r) => ({ ...r, scope: ruleScope.value })),
    }
    await ruleApi.save(payload as any)
    ruleCache.value[ruleScope.value] = ruleItems.value.map((r) => ({ ...r }))
    toast.success(`已保存 ${ruleItems.value.length} 条${ruleScope.value === 'global' ? '全局' : '项目'}规则`)
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    ruleSaving.value = false
  }
}

// ---- Agent 预设（真实接线到后端 CoreProperties / 运行时） ----
/** 预设总览：preset 为用户配置，effective 为与 yml 合成后的生效值，overridden 标记来源。 */
const presetBundle = ref<AgentPresetBundle | null>(null)
const presetSaving = ref(false)
/** 本地编辑副本（null 字段表示「沿用默认」，UI 上以「跟随默认」展示）。 */
const presetDraft = ref({
  maxSteps: undefined as number | undefined,
  subagentEnabled: undefined as boolean | undefined,
  subagentMaxConcurrency: undefined as number | undefined,
  verificationEnabled: undefined as boolean | undefined,
  contextThreshold: undefined as number | undefined,
  autoRetry: undefined as boolean | undefined,
  systemPrompt: '',
})

/** 拉取预设，并把 null 映射为 undefined（表示沿用默认）。 */
async function loadPreset() {
  try {
    const b = await presetApi.get()
    presetBundle.value = b
    presetDraft.value = {
      maxSteps: b.preset.maxSteps ?? undefined,
      subagentEnabled: b.preset.subagentEnabled ?? undefined,
      subagentMaxConcurrency: b.preset.subagentMaxConcurrency ?? undefined,
      verificationEnabled: b.preset.verificationEnabled ?? undefined,
      contextThreshold: b.preset.contextThreshold ?? undefined,
      autoRetry: b.preset.autoRetry ?? undefined,
      systemPrompt: b.preset.systemPrompt ?? '',
    }
  } catch (e) {
    toast.error((e as Error).message)
  }
}

/** 某字段当前是否被用户自定义（用于展示「已自定义 / 跟随默认」标签）。 */
function isOverridden(key: string): boolean {
  return presetBundle.value?.overridden?.[key] === true
}

/** 保存预设：undefined 字段序列化为 null，后端据此回退 yml 默认。 */
async function savePreset() {
  presetSaving.value = true
  try {
    const d = presetDraft.value
    const b = await presetApi.save({
      maxSteps: d.maxSteps ?? null,
      subagentEnabled: d.subagentEnabled ?? null,
      subagentMaxConcurrency: d.subagentMaxConcurrency ?? null,
      verificationEnabled: d.verificationEnabled ?? null,
      contextThreshold: d.contextThreshold ?? null,
      autoRetry: d.autoRetry ?? null,
      systemPrompt: d.systemPrompt.trim() || null,
    })
    presetBundle.value = b
    toast.success('Agent 预设已保存，下次任务生效')
  } catch (e) {
    toast.error((e as Error).message)
  } finally {
    presetSaving.value = false
  }
}

/** 恢复出厂：清空预设，全部沿用 application.yml 默认值。 */
async function resetPreset() {
  if (!confirm('确认将 Agent 预设恢复为默认？（自定义提示词也会被清空）')) return
  try {
    const b = await presetApi.reset()
    presetBundle.value = b
    presetDraft.value = {
      maxSteps: undefined,
      subagentEnabled: undefined,
      subagentMaxConcurrency: undefined,
      verificationEnabled: undefined,
      contextThreshold: undefined,
      autoRetry: undefined,
      systemPrompt: '',
    }
    toast.success('已恢复默认预设')
  } catch (e) {
    toast.error((e as Error).message)
  }
}

// 打开时刷新数据
watch(
  () => props.open,
  async (v) => {
    if (v) {
      tab.value = 'general'
      ruleScope.value = 'global'
      await Promise.all([modelStore.load(), loadRules(), loadUsages(), loadRuleItems(), loadPreset()])
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
                  <div class="sg__head">规则（多条，Web 直接编辑）</div>
                  <div class="sg__acts">
                    <button class="btn btn--ghost btn--sm" @click="addRule"><Icon name="plus" :size="12" /> 加规则</button>
                    <button class="btn btn--primary btn--sm" :disabled="ruleSaving" @click="saveRuleItems">
                      {{ ruleSaving ? '保存中…' : '保存' }}
                    </button>
                  </div>
                </div>
                <!-- 作用域切换：全局 / 当前项目 -->
                <div class="rule-scope">
                  <button
                    class="rule-scope__btn"
                    :class="{ 'rule-scope__btn--on': ruleScope === 'global' }"
                    @click="switchRuleScope('global')"
                  >
                    全部项目（全局）
                  </button>
                  <button
                    class="rule-scope__btn"
                    :class="{ 'rule-scope__btn--on': ruleScope === 'project' }"
                    :disabled="!workspace.current"
                    @click="switchRuleScope('project')"
                  >
                    {{ workspace.current ? `仅 ${workspace.current.name}` : '仅当前项目' }}
                  </button>
                </div>

                <div v-if="ruleLoading" class="empty">规则加载中…</div>
                <div v-else-if="ruleItems.length === 0" class="empty">
                  暂无{{ ruleScope === 'global' ? '全局' : '项目' }}规则，点击「加规则」新建
                </div>
                <div v-else class="rule-editor">
                  <div v-for="(r, i) in ruleItems" :key="i" class="rule-item" :class="{ 'rule-item--off': !r.enabled }">
                    <div class="rule-item__head">
                      <button
                        type="button"
                        class="switch switch--tiny"
                        :class="{ 'switch--on': r.enabled }"
                        role="switch"
                        :aria-checked="r.enabled"
                        :title="r.enabled ? '已启用（点击停用）' : '已停用（点击启用）'"
                        @click="r.enabled = !r.enabled"
                      >
                        <span class="switch__knob" />
                      </button>
                      <input v-model="r.name" class="input rule-item__name" placeholder="规则名（如：提交规范）" />
                      <span class="rule-item__scope mono">{{ ruleScope === 'global' ? '全局' : '项目' }}</span>
                      <button class="icon-c icon-c--danger" title="删除该规则" @click="removeRule(i)">
                        <Icon name="trash" :size="13" />
                      </button>
                    </div>
                    <textarea
                      v-model="r.content"
                      class="input rule-item__body"
                      rows="3"
                      placeholder="规则内容，将作为 system prompt 的一部分注入模型"
                    />
                  </div>
                </div>
                <p class="pane-hint text-3">
                  规则保存于
                  <span class="mono">
                    {{ ruleScope === 'global' ? '框架根 LUCKY.md' : (workspace.current?.path || '项目根') + '/LUCKY.md' }}
                  </span>
                  的 <span class="mono">## 分节</span>（{{ ruleStorage === 'multi-file' ? '当前为多文件目录模式' : '单文件模式' }}）；
                  每次模型调用前读取，保存即生效；项目规则优先于全局规则。
                  <button class="link-btn" @click="openRules(ruleScope === 'project' ? workspace.current?.workspaceId : undefined)">
                    用系统编辑器打开
                  </button>
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
                      <div v-if="usages[cfg.id!]?.calls" class="model__usage" :title="`累计调用 ${formatCount(usages[cfg.id!]?.calls)} 次`">
                        <Icon name="chart" :size="11" />
                        <span>{{ formatCount(usages[cfg.id!]?.calls) }} 次</span>
                      </div>
                    </div>
                    <div class="model__actions" @click.stop>
                      <button
                        class="icon-c icon-c--probe"
                        title="模型详情与用量统计"
                        @click="openDetail(cfg)"
                      >
                        <Icon name="info" :size="13" />
                      </button>
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
                <div class="sg__row">
                  <div class="sg__head">Agent 预设（保存后对后续任务生效）</div>
                  <div class="sg__acts">
                    <button class="btn btn--ghost btn--sm" @click="resetPreset">恢复默认</button>
                    <button class="btn btn--primary btn--sm" :disabled="presetSaving" @click="savePreset">
                      {{ presetSaving ? '保存中…' : '保存' }}
                    </button>
                  </div>
                </div>
                <p class="pane-hint text-3">
                  未设置的项 <b>跟随默认</b>（取自 <span class="mono">application.yml</span>），
                  修改后保存即生效，无需重启；右侧标签显示该项当前来源。
                </p>

                <!-- ACT 最大步数 -->
                <div class="field field--switch">
                  <div class="field__row">
                    <label class="field__label">
                      最大步数（ACT）
                      <span class="src-tag" :class="isOverridden('maxSteps') ? 'src-tag--own' : 'src-tag--def'">
                        {{ isOverridden('maxSteps') ? '已自定义' : '跟随默认' }}
                      </span>
                    </label>
                    <span class="field__val mono">
                      {{ presetDraft.maxSteps ?? presetBundle?.effective.maxSteps ?? 30 }}
                    </span>
                  </div>
                  <input
                    v-model.number="presetDraft.maxSteps"
                    type="range"
                    min="1"
                    max="50"
                    step="1"
                    class="range"
                  />
                </div>

                <!-- 上下文压缩阈值 -->
                <div class="field field--switch">
                  <div class="field__row">
                    <label class="field__label">
                      上下文压缩阈值
                      <span class="src-tag" :class="isOverridden('contextThreshold') ? 'src-tag--own' : 'src-tag--def'">
                        {{ isOverridden('contextThreshold') ? '已自定义' : '跟随默认' }}
                      </span>
                    </label>
                    <span class="field__val mono">{{ (presetDraft.contextThreshold ?? presetBundle?.effective.contextThreshold ?? 0.9).toFixed(2) }}</span>
                  </div>
                  <input
                    v-model.number="presetDraft.contextThreshold"
                    type="range"
                    min="0.5"
                    max="1"
                    step="0.05"
                    class="range"
                  />
                </div>

                <!-- 子代理 -->
                <div class="field-row">
                  <label class="field">
                    <span class="field__label">
                      多 Agent（子代理）
                      <span class="src-tag" :class="isOverridden('subagentEnabled') ? 'src-tag--own' : 'src-tag--def'">
                        {{ isOverridden('subagentEnabled') ? '已自定义' : '跟随默认' }}
                      </span>
                    </span>
                    <select v-model="presetDraft.subagentEnabled" class="input input--select">
                      <option :value="undefined">跟随默认（{{ presetBundle?.effective.subagentEnabled ? '启用' : '停用' }}）</option>
                      <option :value="true">启用</option>
                      <option :value="false">停用（单 Agent）</option>
                    </select>
                  </label>
                  <label class="field">
                    <span class="field__label">
                      并行子代理上限
                      <span class="src-tag" :class="isOverridden('subagentMaxConcurrency') ? 'src-tag--own' : 'src-tag--def'">
                        {{ isOverridden('subagentMaxConcurrency') ? '已自定义' : '跟随默认' }}
                      </span>
                    </span>
                    <select v-model="presetDraft.subagentMaxConcurrency" class="input input--select">
                      <option :value="undefined">跟随默认（{{ presetBundle?.effective.subagentMaxConcurrency ?? 4 }}）</option>
                      <option :value="1">1（串行）</option>
                      <option :value="2">2</option>
                      <option :value="4">4</option>
                      <option :value="8">8</option>
                    </select>
                  </label>
                </div>

                <!-- 客观验证开关 -->
                <div class="field field--switch">
                  <div class="field__row">
                    <label class="field__label">
                      客观验证链（文件/命令信号覆盖模型自述）
                      <span class="src-tag" :class="isOverridden('verificationEnabled') ? 'src-tag--own' : 'src-tag--def'">
                        {{ isOverridden('verificationEnabled') ? '已自定义' : '跟随默认' }}
                      </span>
                    </label>
                    <select v-model="presetDraft.verificationEnabled" class="input input--select preset-select">
                      <option :value="undefined">跟随默认（{{ presetBundle?.effective.verificationEnabled ? '启用' : '停用' }}）</option>
                      <option :value="true">启用</option>
                      <option :value="false">停用</option>
                    </select>
                  </div>
                </div>

                <!-- 失败重试 -->
                <div class="field field--switch">
                  <div class="field__row">
                    <label class="field__label">
                      失败步骤自动重试
                      <span class="src-tag" :class="isOverridden('autoRetry') ? 'src-tag--own' : 'src-tag--def'">
                        {{ isOverridden('autoRetry') ? '已自定义' : '跟随默认' }}
                      </span>
                    </label>
                    <select v-model="presetDraft.autoRetry" class="input input--select preset-select">
                      <option :value="undefined">跟随默认（{{ presetBundle?.effective.autoRetry ? '开启' : '关闭' }}）</option>
                      <option :value="true">开启</option>
                      <option :value="false">关闭（不重试）</option>
                    </select>
                  </div>
                </div>

                <!-- 自定义系统提示词 -->
                <div class="field">
                  <label class="field__label">自定义系统提示词（追加在基座与人格之后）</label>
                  <textarea
                    v-model="presetDraft.systemPrompt"
                    class="input preset-prompt"
                    rows="5"
                    placeholder="例如：回答一律使用简体中文；结论先行；代码示例必须可直接运行。"
                  />
                  <p class="pane-hint text-3">
                    此处填写的内容会作为「自定义指令」注入 system prompt，与规则（全局/项目）叠加生效；
                    留空表示不追加。基座提示词请到「通用设置 → 规则」或框架根 LUCKY.md 修改。
                  </p>
                </div>

                <p v-if="presetBundle" class="pane-hint text-3">
                  当前编排器：<span class="mono">{{ presetBundle.effective.orchestratorMode }}</span>（在
                  <span class="mono">application.yml → core.orchestrator-mode</span> 修改后重启生效）。
                </p>
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
              <div class="field">
                <span class="field__label">
                  上下文窗口（参考，可留空）
                  <span v-if="tokensLabel(editing.contextWindow)" class="tokens-now mono">{{ tokensLabel(editing.contextWindow) }} tokens</span>
                </span>
                <div class="chips">
                  <button
                    v-for="p in contextPresets"
                    :key="p.label"
                    type="button"
                    class="chip"
                    :class="{ 'chip--on': chipActive(editing.contextWindow, p.v) }"
                    @click="editing.contextWindow = p.v"
                  >
                    {{ p.label }}
                  </button>
                  <button
                    type="button"
                    class="chip chip--clear"
                    :class="{ 'chip--on': chipActive(editing.contextWindow, undefined) }"
                    title="不限制（留空）"
                    @click="editing.contextWindow = undefined"
                  >
                    不限制
                  </button>
                </div>
                <div class="token-input">
                  <input
                    v-model.number="editing.contextWindow"
                    type="number"
                    min="0"
                    step="1024"
                    class="input mono"
                    placeholder="自定义 token 数，留空则不限制"
                  />
                  <span class="token-input__suffix mono">tokens</span>
                </div>
              </div>
              <div class="field">
                <span class="field__label">
                  最大输出 tokens（可留空）
                  <span v-if="tokensLabel(editing.maxTokens)" class="tokens-now mono">{{ tokensLabel(editing.maxTokens) }} tokens</span>
                </span>
                <div class="chips">
                  <button
                    v-for="p in outputPresets"
                    :key="p.label"
                    type="button"
                    class="chip"
                    :class="{ 'chip--on': chipActive(editing.maxTokens, p.v) }"
                    @click="editing.maxTokens = p.v"
                  >
                    {{ p.label }}
                  </button>
                  <button
                    type="button"
                    class="chip chip--clear"
                    :class="{ 'chip--on': chipActive(editing.maxTokens, undefined) }"
                    title="不限制（留空，用模型默认）"
                    @click="editing.maxTokens = undefined"
                  >
                    默认
                  </button>
                </div>
                <div class="token-input">
                  <input
                    v-model.number="editing.maxTokens"
                    type="number"
                    min="0"
                    step="1024"
                    class="input mono"
                    placeholder="自定义 token 数，留空则用模型默认"
                  />
                  <span class="token-input__suffix mono">tokens</span>
                </div>
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

  <!-- 模型详情/用量小窗 -->
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="detailOpen" class="overlay" @click.self="detailOpen = false">
        <div class="mini detail" role="dialog" aria-label="模型详情与用量统计">
          <header class="head">
            <div class="head__title">
              <span class="head__icon"><Icon name="info" :size="15" /></span>
              <span v-if="detailCfg">模型详情 · {{ detailCfg.name || detailCfg.modelName }}</span>
            </div>
            <button class="head__close" aria-label="关闭" @click="detailOpen = false"><Icon name="x" :size="15" /></button>
          </header>
          <div class="mini__body">
            <!-- 用量统计 -->
            <div class="detail-section">
              <div class="detail-section__title">用量统计</div>
              <div v-if="usageLoading" class="detail-loading">加载用量中…</div>
              <div v-else-if="!detailUsage?.calls" class="detail-empty">暂无调用记录</div>
              <div v-else class="detail-stats">
                <div class="detail-stat">
                  <span class="detail-stat__label">调用次数</span>
                  <span class="detail-stat__val mono">{{ formatCount(detailUsage.calls) }}</span>
                </div>
                <div class="detail-stat">
                  <span class="detail-stat__label">累计输入 token</span>
                  <span class="detail-stat__val mono">{{ formatCount(detailUsage.inputTokens) }} ({{ tokensLabel(detailUsage.inputTokens) }})</span>
                </div>
                <div class="detail-stat">
                  <span class="detail-stat__label">累计输出 token</span>
                  <span class="detail-stat__val mono">{{ formatCount(detailUsage.outputTokens) }} ({{ tokensLabel(detailUsage.outputTokens) }})</span>
                </div>
                <div class="detail-stat">
                  <span class="detail-stat__label">失败次数</span>
                  <span class="detail-stat__val mono">{{ formatCount(detailUsage.errors) }}</span>
                </div>
                <div class="detail-stat" v-if="detailUsage.lastUsedAt">
                  <span class="detail-stat__label">最近调用</span>
                  <span class="detail-stat__val">{{ fmtTime(detailUsage.lastUsedAt) }}</span>
                </div>
              </div>
            </div>
            <!-- 配置编辑 -->
            <div class="detail-section">
              <div class="detail-section__title">上下文与输出长度配置</div>
              <div v-if="detailCfg" class="detail-form">
                <div class="field">
                  <span class="field__label">
                    上下文窗口（参考）
                    <span v-if="tokensLabel(detailCfg.contextWindow)" class="tokens-now mono">{{ tokensLabel(detailCfg.contextWindow) }} tokens</span>
                  </span>
                  <div class="chips">
                    <button
                      v-for="p in contextPresets"
                      :key="p.label"
                      type="button"
                      class="chip"
                      :class="{ 'chip--on': chipActive(detailCfg.contextWindow, p.v) }"
                      @click="detailCfg.contextWindow = p.v"
                    >
                      {{ p.label }}
                    </button>
                    <button
                      type="button"
                      class="chip chip--clear"
                      :class="{ 'chip--on': chipActive(detailCfg.contextWindow, undefined) }"
                      title="不限制（留空）"
                      @click="detailCfg.contextWindow = undefined"
                    >
                      不限制
                    </button>
                  </div>
                  <div class="token-input">
                    <input
                      v-model.number="detailCfg.contextWindow"
                      type="number"
                      min="0"
                      step="1024"
                      class="input mono"
                      placeholder="自定义 token 数，留空则不限制"
                    />
                    <span class="token-input__suffix mono">tokens</span>
                  </div>
                </div>
                <div class="field">
                  <span class="field__label">
                    最大输出 tokens
                    <span v-if="tokensLabel(detailCfg.maxTokens)" class="tokens-now mono">{{ tokensLabel(detailCfg.maxTokens) }} tokens</span>
                  </span>
                  <div class="chips">
                    <button
                      v-for="p in outputPresets"
                      :key="p.label"
                      type="button"
                      class="chip"
                      :class="{ 'chip--on': chipActive(detailCfg.maxTokens, p.v) }"
                      @click="detailCfg.maxTokens = p.v"
                    >
                      {{ p.label }}
                    </button>
                    <button
                      type="button"
                      class="chip chip--clear"
                      :class="{ 'chip--on': chipActive(detailCfg.maxTokens, undefined) }"
                      title="不限制（留空，用模型默认）"
                      @click="detailCfg.maxTokens = undefined"
                    >
                      默认
                    </button>
                  </div>
                  <div class="token-input">
                    <input
                      v-model.number="detailCfg.maxTokens"
                      type="number"
                      min="0"
                      step="1024"
                      class="input mono"
                      placeholder="自定义 token 数，留空则用模型默认"
                    />
                    <span class="token-input__suffix mono">tokens</span>
                  </div>
                </div>
                <div class="detail-actions">
                  <button class="btn btn--ghost" @click="detailOpen = false">取消</button>
                  <button class="btn btn--primary" @click="saveDetail">保存配置</button>
                </div>
              </div>
            </div>
            <p class="pane-hint text-3">用量数据仅本机统计，不对外传输；配置修改后同步保存到配置文件。</p>
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

/* 模型调用次数角标 */
.model__usage {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-top: 4px;
  font-size: 11px;
  color: var(--text-3);
}
.model__usage svg {
  color: var(--accent-text);
}

/* 上下文/输出长度预设 chips + 自定义输入 */
.tokens-now {
  margin-left: 6px;
  font-size: 11px;
  color: var(--accent-text);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.chip {
  padding: 4px 11px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    color var(--dur-fast) var(--ease);
}
.chip:hover {
  border-color: var(--border-strong);
  color: var(--text-1);
}
.chip--on {
  border-color: var(--accent-border);
  background: var(--accent-dim);
  color: var(--accent-text);
  font-weight: 500;
}
.chip--clear {
  color: var(--text-3);
}
.token-input {
  display: flex;
  align-items: center;
  gap: 8px;
}
.token-input .input {
  flex: 1;
  min-width: 0;
}
.token-input__suffix {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-3);
  white-space: nowrap;
}

/* 模型详情小窗 */
.mini.detail {
  width: 500px;
}
.detail-section {
  margin-bottom: 16px;
}
.detail-section__title {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--text-2);
  letter-spacing: 0.02em;
  margin-bottom: 8px;
}
.detail-stats {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 8px;
}
.detail-stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 11px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
}
.detail-stat__label {
  font-size: 11px;
  color: var(--text-3);
}
.detail-stat__val {
  font-size: var(--fs-13);
  font-weight: 600;
  color: var(--text-0);
  word-break: break-all;
}
.detail-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}
.detail-loading,
.detail-empty {
  padding: 14px;
  border: 1px dashed var(--border-strong);
  border-radius: var(--r-8);
  text-align: center;
  font-size: var(--fs-12);
  color: var(--text-3);
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

/* 多条规则编辑器（全局 / 项目，Web 直接编辑） */
.rule-scope {
  display: inline-flex;
  gap: 4px;
  padding: 3px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  align-self: flex-start;
}
.rule-scope__btn {
  padding: 5px 12px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.rule-scope__btn:hover:not(:disabled) {
  color: var(--text-1);
}
.rule-scope__btn--on {
  background: var(--accent-dim);
  color: var(--accent-text);
  font-weight: 500;
}
.rule-scope__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.rule-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rule-item {
  display: flex;
  flex-direction: column;
  gap: 7px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  padding: 9px 11px;
  transition: border-color var(--dur-fast) var(--ease), opacity var(--dur-fast) var(--ease);
}
.rule-item--off {
  opacity: 0.55;
}
.rule-item__head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.rule-item__name {
  flex: 1;
  min-width: 0;
  font-weight: 600;
}
.rule-item__scope {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--text-3);
  padding: 2px 7px;
  background: var(--bg-0);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
}
.rule-item__body {
  width: 100%;
  resize: vertical;
  font-family: var(--font-mono, monospace);
  line-height: 1.55;
  font-size: var(--fs-12);
}
/* 行内小开关（规则启停） */
.switch--tiny {
  width: 28px;
  height: 16px;
}
.switch--tiny .switch__knob {
  width: 11px;
  height: 11px;
}
.switch--tiny.switch--on .switch__knob {
  transform: translateX(12px);
}
.link-btn {
  padding: 0;
  margin-left: 6px;
  background: none;
  border: none;
  color: var(--accent-text);
  font-size: inherit;
  text-decoration: underline;
  cursor: pointer;
}
.link-btn:hover {
  color: var(--accent-strong);
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

/* 预设字段的来源标签（已自定义 / 跟随默认） */
.src-tag {
  margin-left: 7px;
  padding: 1px 7px;
  border-radius: var(--r-4);
  font-size: 10px;
  font-weight: 500;
  vertical-align: middle;
}
.src-tag--own {
  background: var(--accent-dim);
  color: var(--accent-text);
}
.src-tag--def {
  background: var(--bg-2);
  color: var(--text-3);
}

/* 预设：字段内右置下拉（跟随默认 / 启用 / 停用） */
.preset-select {
  width: 180px;
  flex-shrink: 0;
}

/* 预设：自定义系统提示词多行输入 */
.preset-prompt {
  width: 100%;
  resize: vertical;
  line-height: 1.6;
  font-size: var(--fs-13);
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
