<script setup lang="ts">
import { onMounted } from 'vue'
import Sidebar from './Sidebar.vue'
import TopBar from './TopBar.vue'
import ToastHost from '@/components/common/ToastHost.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useModelStore } from '@/stores/model'
import { useChatStore } from '@/stores/chat'
import { useToastStore } from '@/stores/toast'

const workspace = useWorkspaceStore()
const model = useModelStore()
const chat = useChatStore()
const toast = useToastStore()

onMounted(async () => {
  // 工作空间与模型的加载互不依赖，用 allSettled 隔离失败：
  // 原先 Promise.all 任一失败（后端未起、500）都会抛异常，
  // 导致后面的 chat.loadSessions() 永不执行 —— 侧栏会话列表永久空白且无任何提示。
  const results = await Promise.allSettled([workspace.load(), model.load()])
  const failed = results.filter((r) => r.status === 'rejected') as PromiseRejectedResult[]
  if (failed.length) {
    const reason = failed[0].reason
    toast.error(`初始化失败：${reason instanceof Error ? reason.message : String(reason ?? '无法连接后端')}`)
  }
  // loadSessions 内部已自行兜底，无论上面是否失败都要执行
  await chat.loadSessions()
})
</script>

<template>
  <div class="shell">
    <Sidebar />
    <div class="main">
      <TopBar />
      <main class="content">
        <RouterView />
      </main>
    </div>
    <ToastHost />
  </div>
</template>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  background: var(--bg-0);
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
  display: flex;
}
</style>
