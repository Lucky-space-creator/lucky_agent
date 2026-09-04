<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { fileApi, permissionApi } from '@/api'
import type { ExecResult } from '@/api/types'
import { useWorkspaceStore } from '@/stores/workspace'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'
import Spinner from '@/components/common/Spinner.vue'
import FileTreeNode, { type FNode } from '@/components/files/FileTreeNode.vue'
import CodeViewer from '@/components/files/CodeViewer.vue'

const workspace = useWorkspaceStore()
const root = ref<FNode | null>(null)
const selectedPath = ref('')
const preview = ref<ExecResult | null>(null)
const previewLoading = ref(false)
const newName = ref('')
const creating = ref(false)
const err = ref('')
/** 是否处于编辑态（用 textarea 修改文件内容）。 */
const editing = ref(false)
/** 编辑器内临时内容。 */
const draft = ref('')
const saving = ref(false)
const editErr = ref('')
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
  editing.value = false
  editErr.value = ''
  previewLoading.value = true
  try {
    const r = await fileApi.op({
      opType: 'READ',
      workspaceId: workspace.current!.workspaceId,
      path: node.path,
    })
    preview.value = r
    draft.value = r.content ?? ''
  } catch (e) {
    preview.value = { ok: false, requestId: '', size: 0, error: (e as Error).message }
  } finally {
    previewLoading.value = false
  }
}

/** 可写权限判定：MODIFY/FULL 允许编辑。 */
const canEdit = computed(
  () =>
    preview.value?.ok === true &&
    selectedPath.value !== '' &&
    (workspace.current?.permissionLevel === 'MODIFY' || workspace.current?.permissionLevel === 'FULL'),
)

/** 进入编辑态：从已读内容初始化草稿。 */
function startEdit() {
  if (!canEdit.value) return
  editErr.value = ''
  // 展开全部行再编辑，避免保存时丢掉被 200 行截断的内容
  if (previewTruncated.value) {
    draft.value = preview.value?.content ?? ''
  }
  editing.value = true
}

/** 取消编辑：丢弃草稿回到只读预览。 */
function cancelEdit() {
  editing.value = false
  editErr.value = ''
  if (preview.value) draft.value = preview.value.content ?? ''
}

/** 保存编辑：WRITE 落盘后刷新预览。 */
async function saveEdit() {
  if (!selectedPath.value || saving.value) return
  saving.value = true
  editErr.value = ''
  try {
    const r = await fileApi.op({
      opType: 'WRITE',
      workspaceId: workspace.current!.workspaceId,
      path: selectedPath.value,
      content: draft.value,
    })
    if (!r.ok) {
      editErr.value = r.error || '保存失败'
      return
    }
    editing.value = false
    // 保存后本地同步内容（磁盘一致由 WRITE 成功保证），避免整页重读闪烁
    const cur = preview.value
    preview.value = {
      ok: true,
      requestId: cur?.requestId ?? '',
      size: new TextEncoder().encode(draft.value).length,
      content: draft.value,
      path: cur?.path ?? selectedPath.value,
    }
    // 目录行大小等变化异步刷新一次
    void refresh()
  } catch (e) {
    editErr.value = (e as Error).message
  } finally {
    saving.value = false
  }
}

function onEditKeydown(e: KeyboardEvent) {
  // 仅保存-快捷键建议：Ctrl/Cmd + S 保存（阻止浏览器默认下载）
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault()
    void saveEdit()
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
        <!-- 工具栏：读/写模式切换 -->
        <div v-if="preview.ok" class="preview__toolbar">
          <span class="preview__mode mono">{{ editing ? '编辑' : '只读' }}</span>
          <div class="preview__toolbar-actions">
            <template v-if="!editing">
              <button
                v-if="canEdit"
                class="preview__btn mono"
                :disabled="previewLoading"
                title="修改文件内容"
                @click="startEdit"
              >
                编辑
              </button>
              <button
                v-else-if="workspace.current?.permissionLevel === 'READ_ONLY'"
                class="preview__btn mono"
                disabled
                title="当前工作空间为只读权限，无法编辑"
              >
                只读权限
              </button>
            </template>
            <template v-else>
              <button class="preview__btn mono" @click="cancelEdit">取消</button>
              <button
                class="preview__btn preview__btn--primary mono"
                :disabled="saving"
                @click="saveEdit"
              >
                {{ saving ? '保存中…' : '保存' }}
              </button>
            </template>
          </div>
        </div>
        <p v-if="editErr" class="preview__toolbar-err">{{ editErr }}</p>

        <!-- 编辑态：可修改的代码编辑器 -->
        <textarea
          v-if="editing"
          v-model="draft"
          class="preview__editor mono"
          spellcheck="false"
          @keydown="onEditKeydown"
        />
        <!-- 只读态：语法高亮预览 -->
        <CodeViewer v-else :path="selectedPath" :content="previewLines" />
        <template v-if="!preview.ok">
          <div class="preview__fail">
            <span class="mono preview__path">{{ preview.path }}</span>
            <Badge tone="danger">失败</Badge>
            <p class="preview__err">{{ preview.error }}</p>
          </div>
        </template>
        <button
          v-if="previewTruncated && !editing"
          class="preview__more mono"
          @click="toggleShowAll"
        >
          展开全部内容（共 {{ preview.content?.split('\n').length }} 行）
        </button>
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
  padding: 0 12px 12px;
}
.preview__crumbs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 9px 4px;
  border-bottom: 1px solid var(--border);
  overflow-x: auto;
  white-space: nowrap;
  flex: none;
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
.preview__fail {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 4px;
}
.preview__path {
  font-size: var(--fs-12);
  color: var(--text-1);
}
.preview__more {
  flex: none;
  margin: 8px 4px 0;
  padding: 8px 16px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  color: var(--accent-text);
  font-size: var(--fs-12);
  text-align: center;
  cursor: pointer;
}
.preview__more:hover {
  background: var(--accent-dim);
}
.preview__err {
  margin: 0;
  color: var(--danger-text);
  font-size: var(--fs-13);
}
.preview__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 4px 4px;
  flex: none;
}
.preview__mode {
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--text-3);
}
.preview__toolbar-actions {
  display: flex;
  gap: 6px;
}
.preview__btn {
  padding: 4px 14px;
  font-size: var(--fs-12);
  color: var(--text-2);
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  cursor: pointer;
  transition: background var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease);
}
.preview__btn:hover:not(:disabled) {
  color: var(--text-1);
  background: var(--bg-2);
}
.preview__btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.preview__btn--primary {
  color: var(--accent-text);
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.preview__btn--primary:hover:not(:disabled) {
  color: var(--accent-strong);
  background: var(--accent-dim);
}
.preview__toolbar-err {
  margin: 4px 0 0;
  color: var(--danger-text);
  font-size: var(--fs-12);
}
.preview__editor {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 10px 14px;
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  background: var(--bg-0);
  color: var(--text-1);
  font-size: var(--fs-13);
  line-height: 1.6;
  resize: none;
  outline: none;
  white-space: pre;
  overflow: auto;
}
.preview__editor:focus {
  border-color: var(--accent-border);
}
</style>
