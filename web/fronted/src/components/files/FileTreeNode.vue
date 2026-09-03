<script setup lang="ts">
import { fileApi } from '@/api'
import type { FileEntry } from '@/api/types'
import Icon from '@/components/common/Icon.vue'
import Spinner from '@/components/common/Spinner.vue'

export interface FNode {
  name: string
  path: string
  dir: boolean
  size: number
  children?: FNode[]
  expanded: boolean
  loading: boolean
}

const props = defineProps<{ node: FNode; workspaceId: string; depth?: number; selectedPath?: string }>()

const emit = defineEmits<{ open: [node: FNode]; remove: [node: FNode] }>()

async function loadDir() {
  props.node.loading = true
  try {
    const r = await fileApi.list(props.workspaceId, props.node.path)
    props.node.children = (r.entries ?? []).map(
      (e: FileEntry): FNode => ({
        name: e.name,
        path: e.path,
        dir: e.dir,
        size: e.size,
        expanded: false,
        loading: false,
      }),
    )
  } finally {
    props.node.loading = false
  }
}

async function toggle() {
  if (!props.node.dir) return
  if (!props.node.expanded && !props.node.children) {
    await loadDir()
  }
  props.node.expanded = !props.node.expanded
}

function onOpen() {
  if (props.node.dir) {
    toggle()
  } else {
    emit('open', props.node)
  }
}

function fmtSize(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <div>
    <div
      class="node"
      :class="{ 'node--sel': node.path === selectedPath, 'node--dir': node.dir }"
      :style="{ paddingLeft: 6 + (depth ?? 0) * 14 + 'px' }"
    >
      <span class="node__chev">
        <Spinner v-if="node.loading" :size="11" />
      </span>
      <span class="node__ic" @click="onOpen">
        <Icon :name="node.dir ? 'folder' : 'file'" :size="14" />
      </span>
      <span class="node__name" @click="onOpen">{{ node.name }}</span>
      <span class="node__size mono">{{ node.dir ? '' : fmtSize(node.size) }}</span>
      <span v-if="node.dir || node.path !== ''" class="node__del" @click="emit('remove', node)">
        <Icon name="trash" :size="11" />
      </span>
    </div>

    <div v-if="node.expanded && node.children" class="node__children">
      <template v-for="child in node.children" :key="child.path">
        <FileTreeNode
          :node="child"
          :workspace-id="workspaceId"
          :depth="(depth ?? 0) + 1"
          :selected-path="selectedPath"
          @open="emit('open', $event)"
          @remove="emit('remove', $event)"
        />
      </template>
      <div v-if="node.children.length === 0" class="node__empty text-3 mono">目录为空</div>
    </div>
  </div>
</template>

<style scoped>
.node {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 6px;
  border-radius: var(--r-4);
  font-size: var(--fs-13);
  color: var(--text-1);
}
.node:hover {
  background: var(--bg-2);
}
.node--sel {
  background: var(--bg-3);
}
.node__chev {
  width: 14px;
  display: flex;
  justify-content: center;
  color: var(--text-3);
  cursor: pointer;
  flex-shrink: 0;
}
.node__ic {
  display: flex;
  color: var(--teal);
  flex-shrink: 0;
}
.node--dir .node__ic {
  color: var(--accent);
}
.node__name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  cursor: pointer;
}
.node__size {
  font-size: 10px;
  color: var(--text-3);
  flex-shrink: 0;
}
.node__del {
  display: none;
  color: var(--text-3);
  padding: 2px;
  flex-shrink: 0;
}
.node:hover .node__del {
  display: flex;
}
.node__del:hover {
  color: var(--danger-text);
}
.node__empty {
  font-size: 11px;
  padding: 4px 6px 4px 34px;
  color: var(--text-3);
}
</style>
