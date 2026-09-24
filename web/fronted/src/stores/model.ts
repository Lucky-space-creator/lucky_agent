import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { modelApi } from '@/api'
import type { InferenceDepth, ModelConfig } from '@/api/types'

/** 推理深度档位（数组顺序即滑块 0–4 档）。 */
export interface DepthOption {
  value: InferenceDepth
  label: string
  short: string
  desc: string
}

export const depthOptions: DepthOption[] = [
  { value: 'OFF', label: '模型默认', short: '默认', desc: '不发送思考参数，沿用模型自身默认（DeepSeek·Qwen 默认即开启思考）' },
  { value: 'QUICK', label: '轻量思考', short: '轻量', desc: '轻量推理，适合简单问答' },
  { value: 'BALANCED', label: '标准思考', short: '标准', desc: '均衡推理（默认）' },
  { value: 'DEEP', label: '深度思考', short: '深入', desc: '深度思考，适合复杂任务' },
  { value: 'MAXIMUM', label: '极致思考', short: '极致', desc: '最大思考预算，最耗 token' },
]

export const useModelStore = defineStore('model', () => {
  const configs = ref<ModelConfig[]>([])
  const loaded = ref(false)
  /** 全局推理深度（枚举名，作用于模型请求）。默认 OFF：不强制，交给模型自身能力。 */
  const inferenceDepth = ref<InferenceDepth>('OFF')
  /**
   * 用户在模型选择器里选定的端点 id。
   * null 表示跟随主端点；每次对话提交会带上它，后端据此路由，不再固定用 primary。
   */
  const selectedId = ref<string | null>(null)

  /** 主对话端点：role=main 且启用优先；否则回退第一个「非记忆」启用端点（记忆端点不做对话模型）。 */
  const primary = computed(
    () =>
      configs.value.find((c) => c.role === 'main' && c.enabled) ??
      configs.value.find((c) => c.enabled && c.role !== 'memory') ??
      null,
  )
  /** 实际生效的端点：显式选择优先（记忆端点不可选作对话模型），否则跟随主端点。 */
  const active = computed(
    () =>
      configs.value.find((c) => c.id === selectedId.value && c.role !== 'memory') ??
      primary.value,
  )
  /** 记忆管理 Agent 端点（role=memory，会话记忆总结专用）；未配置返回 null（总结回退主力模型）。 */
  const memoryModel = computed(
    () => configs.value.find((c) => c.role === 'memory' && c.enabled) ?? null,
  )
  const hasModel = computed(() => configs.value.length > 0)

  /** 选定端点（传 null 恢复跟随主端点）。 */
  function select(id: string | null) {
    selectedId.value = id
  }

  /** 当前深度在档位数组中的下标（0–4），供滑块初始定位。 */
  const depthIndex = computed(() => Math.max(0, depthOptions.findIndex((o) => o.value === inferenceDepth.value)))
  /** 当前深度的语义标签。 */
  const depthLabel = computed(() => depthOptions[depthIndex.value]?.label ?? '标准思考')

  async function load() {
    configs.value = await modelApi.list()
    loaded.value = true
  }

  async function save(config: ModelConfig) {
    const saved = await modelApi.save(config)
    await load()
    return saved
  }

  async function remove(id: string) {
    await modelApi.remove(id)
    await load()
  }

  async function loadDepth() {
    const res = await modelApi.depth()
    inferenceDepth.value = res.depth
  }

  async function saveDepth(depth: InferenceDepth) {
    await modelApi.saveDepth(depth)
    inferenceDepth.value = depth
  }

  return {
    configs,
    primary,
    active,
    memoryModel,
    selectedId,
    select,
    hasModel,
    inferenceDepth,
    depthOptions,
    depthIndex,
    depthLabel,
    loaded,
    load,
    save,
    remove,
    loadDepth,
    saveDepth,
  }
})
