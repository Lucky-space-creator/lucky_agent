<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { fileApi, permissionApi } from '@/api'
import type { ExecResult } from '@/api/types'
import { useWorkspaceStore } from '@/stores/workspace'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'
import Spinner from '@/components/common/Spinner.vue'
import FileTreeNode, { type FNode } from '@/components/files/FileTreeNode.vue'

const workspace = useWorkspaceStore()
const root = ref<FNode | null>(null)
const selectedPath = ref('')
const preview = ref<ExecResult | null>(null)
const previewLoading = ref(false)
const newName = ref('')
const creating = ref(false)
const err = ref('')
const PREVIEW_MAX_LINES = 200
const showAll = ref(false)
const previewLines = computed(() => {
  const c = preview.value?.content ?? ''
  if (showAll.value) return c
  const lines = c.split('\n')
  return lines.length <= PREVIEW_MAX_LINES ? c : lines.slice(0, PREVIEW_MAX_LINES).join('\n')
})
const previewTruncated = computed(
  () => !showAll.value && (preview.value?.content ?? '').split('\n').length > PREVIEW_MAX_LINES,
)
function toggleShowAll() {
  showAll.value = !showAll.value
}

const crumbs = computed(() => {
  const path = selectedPath.value || ''
  const segs = path.split('/').filter(Boolean)
  const acc: { label: string; path: string }[] = [{ label: workspace.current?.name ?? '根', path: '' }]
  let cur = ''
  for (const s of segs) {
    cur = cur ? `${cur}/${s}` : s
    acc.push({ label: s, path: cur })
  }
  return acc
})

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
  selectedPath.value = ''
  preview.value = null
  err.value = ''
  if (!workspace.current) return
  root.value = { name: workspace.current.name, path: '', dir: true, size: 0, expanded: true, loading: false }
  await loadDir(root.value)
}

/** 沿路径展开目录（按需加载），用于面包屑跳转。 */
async function revealDir(path: string) {
  if (!root.value) return
  const segs = path.split('/').filter(Boolean)
  let node = root.value
  for (const seg of segs) {
    if (!node.children) await loadDir(node)
    const next = node.children?.find((c) => c.name === seg && c.dir)
    if (!next) return
    node.expanded = true
    node = next
  }
  node.expanded = true
}

async function open(node: FNode) {
  if (node.dir) return
  selectedPath.value = node.path
  showAll.value = false
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

async function createFile() {
  const name = newName.value.trim()
  if (!name) return
  const dirPath = currentDirPath()
  const path = dirPath ? `${dirPath}/${name}` : name
  creating.value = true
  try {
    await fileApi.op({ opType: 'WRITE', workspaceId: workspace.current!.workspaceId, path, content: '' })
    await refresh()
    newName.value = ''
  } catch (e) {
    err.value = (e as Error).message
  } finally {
    creating.value = false
  }
}

async function createDir() {
  const name = newName.value.trim()
  if (!name) return
  const dirPath = currentDirPath()
  const path = dirPath ? `${dirPath}/${name}` : name
  creating.value = true
  try {
    await fileApi.op({ opType: 'MKDIR', workspaceId: workspace.current!.workspaceId, path })
    await refresh()
    newName.value = ''
  } catch (e) {
    err.value = (e as Error).message
  } finally {
    creating.value = false
  }
}

async function removeNode(node: FNode) {
  if (!confirm(`确认删除 ${node.name}？（软删除，可回收站恢复）`)) return
  const doDelete = async () =>
    fileApi.op({ opType: 'DELETE', workspaceId: workspace.current!.workspaceId, path: node.path })
  let r = await doDelete()
  if (!r.ok && r.error?.includes('需用户确认')) {
    // 高危操作需二次确认：放行后重试（TTL 内不再 ASK）
    if (!confirm(`高危操作：删除 ${node.name} 将被执行，是否确认？`)) return
    await permissionApi.confirm({
      opType: 'DELETE',
      workspaceId: workspace.current!.workspaceId,
      path: node.path,
    })
    r = await doDelete()
  }
  if (!r.ok) {
    err.value = r.error || '删除被拒绝'
    return
  }
  if (selectedPath.value === node.path) {
    selectedPath.value = ''
    preview.value = null
  }
  await refresh()
}

function currentDirPath(): string {
  if (!selectedPath.value) return ''
  const lastSlash = selectedPath.value.lastIndexOf('/')
  return lastSlash >= 0 ? selectedPath.value.slice(0, lastSlash) : ''
}

async function refresh() {
  if (!root.value) return
  const wasExpanded = new Map<string, boolean>()
  collectExpanded(root.value, wasExpanded)
  await init()
  // 恢复展开状态（按路径）
  await restoreExpanded(root.value, wasExpanded)
}

function collectExpanded(node: FNode, map: Map<string, boolean>) {
  if (node.dir && node.expanded) map.set(node.path, true)
  node.children?.forEach((c) => collectExpanded(c, map))
}

async function restoreExpanded(node: FNode, map: Map<string, boolean>) {
  if (!node.children) return
  for (const c of node.children) {
    if (c.dir && map.has(c.path)) {
      c.expanded = true
      await loadDir(c)
      await restoreExpanded(c, map)
    }
  }
}

watch(
  () => workspace.current?.workspaceId,
  async () => {
    await init()
  },
)

init()
</script>

<template>
  <div class="files">
    <aside class="files__tree">
      <div class="files__head">
        <span class="mono files__title">文件</span>
        <div class="files__actions">
          <input v-model="newName" class="files__new mono" placeholder="新建…" @keydown.enter="createFile" />
          <button class="files__icon" title="新建文件" @click="createFile"><Icon name="file" :size="13" /></button>
          <button class="files__icon" title="新建目录" @click="createDir"><Icon name="folder" :size="13" /></button>
          <button class="files__icon" title="刷新" @click="refresh"><Icon name="refresh" :size="13" /></button>
        </div>
      </div>

      <p v-if="err" class="files__err">{{ err }}</p>

      <div v-if="!workspace.current" class="files__none text-3 mono">请先选择工作空间</div>
      <div v-else class="files__tree-body">
        <FileTreeNode
          v-if="root"
          :node="root"
          :workspace-id="workspace.current!.workspaceId"
          :depth="0"
          :selected-path="selectedPath"
          @open="open"
          @remove="removeNode"
        />
      </div>
    </aside>

    <section class="files__preview">
      <div class="preview__crumbs">
        <template v-for="(c, i) in crumbs" :key="c.path">
          <button class="preview__crumb mono" @click="revealDir(c.path)">
            {{ c.label }}
          </button>
          <span v-if="i < crumbs.length - 1" class="preview__sep">/</span>
        </template>
      </div>

      <template v-if="previewLoading">
        <div class="files__loading"><Spinner :size="16" /> 读取中…</div>
      </template>
      <template v-else-if="preview">
        <header class="preview__head">
          <span class="mono preview__path">{{ preview.path }}</span>
          <Badge :tone="preview.ok ? 'teal' : 'danger'">{{ preview.ok ? '已读取' : '失败' }}</Badge>
        </header>
        <pre v-if="preview.ok" class="preview__content">{{ previewLines }}</pre>
        <button v-if="previewTruncated" class="preview__more mono" @click="toggleShowAll">
          展开全部内容（共 {{ preview.content?.split('\n').length }} 行）
        </button>
        <p v-else-if="preview && !preview.ok" class="preview__err">{{ preview.error }}</p>
      </template>
      <template v-else>
        <div class="files__none files__none--center text-3 mono">选择文件查看内容</div>
      </template>
    </section>
  </div>
</template>

<style scoped>
.files {
  flex: 1;
  display: flex;
  min-width: 0;
}
.files__tree {
  width: 300px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
}
.files__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
  gap: 8px;
  flex-wrap: wrap;
}
.files__title {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.files__actions {
  display: flex;
  align-items: center;
  gap: 4px;
}
.files__new {
  width: 90px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  padding: 4px 6px;
  font-size: 11px;
  color: var(--text-1);
}
.files__new:focus {
  outline: none;
  border-color: var(--accent-border);
}
.files__icon {
  display: flex;
  padding: 4px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-4);
}
.files__icon:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.files__err {
  color: var(--danger-text);
  font-size: var(--fs-12);
  padding: 8px 12px;
}
.files__none {
  font-size: 11px;
  padding: 12px;
}
.files__none--center {
  text-align: center;
  margin: auto;
}
.files__tree-body {
  flex: 1;
  overflow-y: auto;
  padding: 6px 6px;
}

.files__preview {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
}
.preview__crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 9px 16px;
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  white-space: nowrap;
}
.preview__crumb {
  background: none;
  border: none;
  color: var(--text-2);
  font-size: 12px;
  padding: 2px 6px;
  border-radius: var(--r-4);
}
.preview__crumb:hover {
  color: var(--accent-text);
  background: var(--accent-dim);
}
.preview__sep {
  color: var(--text-3);
  font-size: 11px;
}
.files__loading {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: center;
  height: 100%;
  color: var(--text-2);
  font-size: var(--fs-13);
}
.preview__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
}
.preview__path {
  font-size: var(--fs-12);
  color: var(--text-1);
}
.preview__content {
  flex: 1;
  overflow: auto;
  max-height: 100%;
  margin: 0;
  padding: 16px;
  background: var(--bg-0);
  border: none;
  color: var(--text-1);
  font-size: var(--fs-13);
  white-space: pre-wrap;
  word-break: break-word;
}
.preview__more {
  margin: 0;
  padding: 8px 16px;
  background: var(--bg-1);
  border: none;
  border-top: 1px solid var(--border);
  color: var(--accent-text);
  font-size: var(--fs-12);
  text-align: center;
  cursor: pointer;
}
.preview__more:hover {
  background: var(--accent-dim);
}
.preview__err {
  padding: 16px;
  color: var(--danger-text);
}
</style>
