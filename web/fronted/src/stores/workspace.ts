import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { workspaceApi } from '@/api'
import type { Workspace } from '@/api/types'

export const useWorkspaceStore = defineStore('workspace', () => {
  const workspaces = ref<Workspace[]>([])
  const currentId = ref<string | null>(null)
  const loaded = ref(false)
  const loading = ref(false)

  /** 用户自建工作空间（内置默认项不在界面展示）。 */
  const visible = computed(() => workspaces.value.filter((w) => !w.builtin))

  /** 内置默认工作空间：用户未自建时的兜底落盘位置。 */
  const builtin = computed(() => workspaces.value.find((w) => w.builtin) ?? null)

  /**
   * 当前生效工作空间。
   *
   * <p>优先用户显式选择；未选择或选择项已删除时，先回退到第一个自建工作空间，
   * 再回退到内置默认工作空间，避免出现「无工作空间」导致文件/会话接口不可用。</p>
   */
  const current = computed(
    () =>
      workspaces.value.find((w) => w.workspaceId === currentId.value) ??
      visible.value[0] ??
      builtin.value ??
      null,
  )

  function setCurrent(workspaceId: string) {
    if (workspaces.value.some((w) => w.workspaceId === workspaceId)) {
      currentId.value = workspaceId
    }
  }

  async function load() {
    loading.value = true
    try {
      workspaces.value = await workspaceApi.list()
      if (!currentId.value) {
        currentId.value = visible.value[0]?.workspaceId ?? null
      }
      loaded.value = true
    } finally {
      loading.value = false
    }
  }

  async function create(input: { name: string; path: string; permissionLevel: string }) {
    const ws = await workspaceApi.create(input)
    workspaces.value.push(ws)
    currentId.value = ws.workspaceId
    return ws
  }

  async function updatePermission(workspaceId: string, level: string) {
    const updated = await workspaceApi.updatePermission(workspaceId, level)
    if (updated) {
      const idx = workspaces.value.findIndex((w) => w.workspaceId === workspaceId)
      if (idx >= 0) workspaces.value[idx] = updated
    }
  }

  async function remove(workspaceId: string) {
    await workspaceApi.remove(workspaceId)
    workspaces.value = workspaces.value.filter((w) => w.workspaceId !== workspaceId)
    if (currentId.value === workspaceId) {
      currentId.value = visible.value[0]?.workspaceId ?? null
    }
  }

  return {
    workspaces,
    visible,
    builtin,
    currentId,
    current,
    loaded,
    loading,
    load,
    create,
    setCurrent,
    updatePermission,
    remove,
  }
})
