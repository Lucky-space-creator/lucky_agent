<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'
import { fileApi } from '@/api'
import type { ExecResult } from '@/api/types'
import { useWorkspaceStore } from '@/stores/workspace'
import Icon from '@/components/common/Icon.vue'
import Spinner from '@/components/common/Spinner.vue'
import FileTreeNode, { type FNode } from '@/components/files/FileTreeNode.vue'

const props = defineProps<{ treeOpen?: boolean; openSeq?: number; openPath?: string }>()

/** 文件树是否展开（受控 prop，由面板头部按钮控制）。 */
const treeOpen = ref(props.treeOpen ?? true)

watch(
  () => props.treeOpen,
  (v) => {
    if (v !== undefined) treeOpen.value = v
  },
)

const workspace = useWorkspaceStore()
const root = ref<FNode | null>(null)
const preview = ref<ExecResult | null>(null)
const previewLoading = ref(false)
const err = ref('')

async function loadDir(node: FNode): Promise<void> {
  node.loading = true
  try {
    const r = await fileApi.list(workspace.current!.workspaceId, node.path)
    node.children = (r.entries ?? []).map(
      (e): FNode => ({
        name: e.name,
        path: e.path,
        dir: e.dir,
        size: e.size,
        expanded: false,
        loading: false,
      }),
    )
  } finally {
    node.loading = false
  }
}

async function init() {
  root.value = null
  preview.value = null
  err.value = ''
  if (!workspace.current) return
  root.value = { name: workspace.current.name, path: '', dir: true, size: 0, expanded: true, loading: false }
  await loadDir(root.value)
}

async function open(node: FNode) {
  if (node.dir) return
  previewLoading.value = true
  try {
    preview.value = await fileApi.op({
      opType: 'READ',
      workspaceId: workspace.current!.workspaceId,
      path: node.path,
    })
  } catch (e) {
    preview.value = { ok: false, requestId: '', size: 0, error: (e as Error).message }
  } finally {
    previewLoading.value = false
  }
}

/** 解析路径各分段，展开文件树祖先目录（root 已含，故从 root.children 起定位）。 */
async function expandTo(path: string): Promise<FNode | null> {
  const segs = (path || '').split('/').filter((s) => s.length > 0)
  if (segs.length === 0 || !root.value) return null
  let node = root.value
  // 逐段展开：列出目录、定位子节点并展开
  for (let i = 0; i < segs.length; i++) {
    if (node.dir && !node.children) {
      node.loading = true
      try {
        const r = await fileApi.list(workspace.current!.workspaceId, node.path)
        node.children = (r.entries ?? []).map(
          (e): FNode => ({
            name: e.name,
            path: e.path,
            dir: e.dir,
            size: e.size,
            expanded: false,
            loading: false,
          }),
        )
      } finally {
        node.loading = false
      }
    }
    const child = (node.children ?? []).find((c) => c.name === segs[i])
    if (!child) return null
    if (i < segs.length - 1) {
      child.expanded = true
    }
    node = child
  }
  return node
}

/** 外部「查看文件」请求：展开祖先目录并读取目标文件。 */
async function revealFile(path: string) {
  if (!path || !workspace.current) return
  if (!root.value) await init()
  if (!root.value) return
  const target = await expandTo(path)
  if (target && !target.dir) {
    await open(target)
  } else if (target) {
    preview.value = { ok: true, requestId: '', path, size: 0, summary: '目录' }
  }
}

watch(
  () => workspace.current?.workspaceId,
  async () => {
    await init()
  },
)

// 消息内文件链接触发定位：openSeq 变化（含组件首次渲染）时读取 openPath 指向的文件
watch(
  () => props.openSeq,
  async (seq) => {
    if (seq && props.openPath) {
      await revealFile(props.openPath)
    }
  },
  { immediate: true },
)

onMounted(() => {
  if (!root.value) init()
})
</script>

<template>
  <div class="pe">
    <!-- 文件预览（左侧） -->
    <div class="pe__preview">
      <template v-if="previewLoading">
        <div class="pe__loading"><Spinner :size="14" /> 读取中…</div>
      </template>
      <template v-else-if="preview">
        <header class="pe__preview-head">
          <span class="mono pe__path">{{ preview.path }}</span>
          <span class="pe__badge" :class="preview.ok ? 'pe__badge--ok' : 'pe__badge--err'">
            {{ preview.ok ? '已读取' : '失败' }}
          </span>
        </header>
        <pre v-if="preview.ok" class="pe__content">{{ preview.content ?? '' }}</pre>
        <p v-else class="pe__err">{{ preview.error }}</p>
      </template>
      <div v-else class="pe__none pe__none--center text-3 mono">选择文件查看内容</div>
    </div>

    <!-- 文件树（右侧；收缩/展开由面板头部按钮控制，自身无箭头按钮） -->
    <div class="pe__tree" :class="{ 'pe__tree--hidden': !treeOpen }">
      <template v-if="treeOpen">
        <div class="pe__head">
          <Icon name="folder" :size="13" />
          <span class="pe__title mono">项目文件</span>
          <button class="pe__head-act" title="刷新" @click="init"><Icon name="refresh" :size="12" /></button>
        </div>
        <p v-if="err" class="pe__err">{{ err }}</p>
        <div v-if="!workspace.current" class="pe__none text-3 mono">请先选择工作空间</div>
        <div v-else class="pe__body">
          <FileTreeNode
            v-if="root"
            :node="root"
            :workspace-id="workspace.current!.workspaceId"
            :depth="0"
            :selected-path="preview?.path"
            @open="open"
          />
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.pe {
  display: flex;
  min-height: 0;
  height: 100%;
}
.pe__tree {
  width: 200px;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  min-width: 0;
  transition: width var(--dur-med) var(--ease);
}
.pe__tree--hidden {
  width: 34px;
  border-left: 1px solid var(--border);
  align-items: center;
  justify-content: center;
  padding: 8px 0;
}
.pe__head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
}
.pe__head svg {
  color: var(--accent-text);
  flex-shrink: 0;
}
.pe__title {
  flex: 1;
  min-width: 0;
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--text-3);
}
.pe__head-act {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 3px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-4);
}
.pe__head-act:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.pe__body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 4px;
}
.pe__none {
  font-size: 11px;
  padding: 12px;
  color: var(--text-3);
}
.pe__none--center {
  text-align: center;
  margin: auto;
}
.pe__err {
  color: var(--danger-text);
  font-size: var(--fs-12);
  padding: 10px 12px;
}
.pe__preview {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.pe__loading {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  height: 100%;
  color: var(--text-2);
  font-size: var(--fs-12);
}
.pe__preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border);
}
.pe__path {
  font-size: var(--fs-11);
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pe__badge {
  font-size: 10px;
  padding: 1px 7px;
  border-radius: var(--r-pill);
  flex-shrink: 0;
}
.pe__badge--ok {
  color: var(--teal);
  background: var(--teal-dim);
}
.pe__badge--err {
  color: var(--danger-text);
  background: var(--danger-dim);
}
.pe__content {
  flex: 1;
  overflow: auto;
  margin: 0;
  padding: 12px;
  background: var(--bg-0);
  border: none;
  color: var(--text-1);
  font-size: var(--fs-12);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
