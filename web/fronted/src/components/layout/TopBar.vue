<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import Icon from '@/components/common/Icon.vue'
import Badge from '@/components/common/Badge.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useModelStore } from '@/stores/model'
import { useChatStore } from '@/stores/chat'
import { useUiStore } from '@/stores/ui'

const route = useRoute()
const workspace = useWorkspaceStore()
const model = useModelStore()
const chat = useChatStore()
const ui = useUiStore()

const title = computed(() => {
  const map: Record<string, string> = { chat: '对话', files: '文件' }
  return map[String(route.name)] ?? 'Lucky Agent'
})

const levelLabel = computed(() => {
  const level = workspace.current?.permissionLevel
  return { READ_ONLY: '只读', MODIFY: '修改文件', FULL: 'Full access' }[level ?? 'MODIFY']
})
</script>

<template>
  <header class="topbar">
    <div class="topbar__left">
      <h1 class="topbar__title">{{ title }}</h1>
      <span v-if="workspace.current" class="topbar__ctx">
        <span class="topbar__dot" />
        {{ workspace.current.name }}
        <span class="topbar__sep">·</span>
        <span class="topbar__level">{{ levelLabel }}</span>
      </span>
    </div>

    <div class="topbar__right">
      <Badge v-if="chat.running" tone="accent" dot>执行中</Badge>
      <Badge v-else-if="model.primary" tone="neutral">
        <Icon name="sparkles" :size="11" /> {{ model.primary.modelName }}
      </Badge>
      <Badge v-else tone="warn">未配置模型</Badge>
      <!-- 展示/收起右侧工具栏（原主题切换位置） -->
      <button
        class="topbar__rail-btn"
        :class="{ 'topbar__rail-btn--on': ui.rightRailOpen }"
        :title="ui.rightRailOpen ? '收起右侧工具栏' : '展示右侧工具栏'"
        @click="ui.toggleRightRail()"
      >
        <Icon name="panel" :size="15" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  height: var(--topbar-h);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--bg-0);
  border-bottom: 1px solid var(--border-faint);
}
.topbar__left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}
.topbar__title {
  font-size: var(--fs-14);
  font-weight: 600;
  color: var(--text-0);
}
.topbar__ctx {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-12);
  color: var(--text-2);
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.topbar__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--accent);
}
.topbar__sep {
  color: var(--border-strong);
}
.topbar__level {
  color: var(--accent-text);
  font-weight: 500;
}
.topbar__right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.topbar__rail-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--border);
  background: var(--bg-1);
  color: var(--text-3);
  border-radius: var(--r-6);
  cursor: pointer;
  transition: color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease),
    border-color var(--dur-fast) var(--ease);
}
.topbar__rail-btn:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.topbar__rail-btn--on {
  color: var(--accent-text);
  background: var(--accent-dim);
  border-color: var(--accent-border);
}
</style>
