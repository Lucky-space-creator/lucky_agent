<script setup lang="ts">
import { onMounted } from 'vue'
import Sidebar from './Sidebar.vue'
import TopBar from './TopBar.vue'
import ToastHost from '@/components/common/ToastHost.vue'
import { useWorkspaceStore } from '@/stores/workspace'
import { useModelStore } from '@/stores/model'
import { useChatStore } from '@/stores/chat'

const workspace = useWorkspaceStore()
const model = useModelStore()
const chat = useChatStore()

onMounted(async () => {
  await Promise.all([workspace.load(), model.load()])
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
