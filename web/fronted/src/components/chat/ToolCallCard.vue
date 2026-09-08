<script setup lang="ts">
import { ref, computed } from 'vue'
import type { ToolCallView } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'

const props = defineProps<{ call: ToolCallView }>()

const ui = useUiStore()
const expanded = ref(false)
const argsText = computed(() => JSON.stringify(props.call.args ?? {}, null, 2))

/** 文件类工具（带路径）：提供「查看文件」定位链接（write/mkdir/rename/delete/read/list/stat 等）。 */
const fileTool = computed(() => {
  const t = props.call.tool || ''
  return t.startsWith('file.')
})
const filePath = computed(() => {
  const args = (props.call.args ?? {}) as Record<string, any>
  return args?.path || args?.args?.path || ''
})

function openInExplorer() {
  if (filePath.value) ui.openFilePanel(filePath.value)
}
</script>

<template>
  <div class="tool">
    <button class="tool__head" @click="expanded = !expanded">
      <span class="tool__ic"><Icon name="terminal" :size="12" /></span>
      <span class="tool__name mono">{{ call.tool }}</span>
      <Badge :tone="call.status === 'running' ? 'accent' : call.ok ? 'teal' : 'danger'" :dot="call.status === 'running'">
        {{ call.status === 'running' ? '执行中' : call.ok ? '成功' : '失败' }}
      </Badge>
      <span class="tool__chev"><Icon :name="expanded ? 'chevronDown' : 'chevronRight'" :size="12" /></span>
    </button>

    <!-- 文件类工具（带路径）→ 提供「查看文件」链接，点击在右侧文件面板定位展示 -->
    <button
      v-if="fileTool && filePath && call.status === 'done'"
      class="tool__file"
      :title="call.ok ? '在文件面板中查看' : '查看该文件（操作失败）'"
      @click="openInExplorer"
    >
      <Icon name="file" :size="12" />
      <span class="tool__file-name mono">{{ filePath }}</span>
      <span class="tool__file-go">查看文件 <Icon name="external" :size="11" /></span>
    </button>

    <div v-if="expanded" class="tool__body">
      <div class="tool__section">
        <span class="tool__label mono">入参</span>
        <pre class="tool__code">{{ argsText }}</pre>
      </div>
      <div v-if="call.summary || call.error" class="tool__section">
        <span class="tool__label mono">{{ call.error ? '结果（错误）' : '结果' }}</span>
        <pre class="tool__code" :class="{ 'tool__code--err': call.error }">{{ call.error || call.summary }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool {
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  background: var(--bg-1);
  margin: 6px 0;
  overflow: hidden;
}
.tool__head {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 10px;
  background: none;
  border: none;
  color: var(--text-1);
  font-size: var(--fs-13);
  text-align: left;
}
.tool__head:hover {
  background: var(--bg-2);
}
.tool__ic {
  color: var(--teal);
  display: flex;
}
.tool__name {
  font-size: var(--fs-12);
  color: var(--text-0);
  font-weight: 500;
}
.tool__chev {
  margin-left: auto;
  color: var(--text-3);
  display: flex;
}
.tool__file {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 5px 10px;
  border-top: 1px solid var(--border);
  background: none;
  color: var(--accent-text);
  font-size: var(--fs-12);
  text-align: left;
  cursor: pointer;
}
.tool__file:hover {
  background: var(--accent-dim);
}
.tool__file-name {
  color: var(--accent-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tool__file-go {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--text-3);
  flex-shrink: 0;
  font-size: 11px;
}
.tool__file:hover .tool__file-go {
  color: var(--accent-text);
}
.tool__body {
  padding: 8px 10px;
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.tool__section {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.tool__label {
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--text-3);
}
.tool__code {
  margin: 0;
  background: var(--bg-0);
  border-color: var(--border);
  font-size: var(--fs-12);
  max-height: 220px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.tool__code--err {
  color: var(--danger-text);
}
</style>
