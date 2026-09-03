<script setup lang="ts">
import { useToastStore } from '@/stores/toast'
import Icon from './Icon.vue'

const toast = useToastStore()
</script>

<template>
  <Teleport to="body">
    <div class="toast-host">
      <TransitionGroup name="toast">
        <div
          v-for="t in toast.toasts"
          :key="t.id"
          class="toast"
          :class="`toast--${t.type}`"
          role="status"
        >
          <Icon :name="t.type === 'error' ? 'x' : t.type === 'info' ? 'help' : 'check'" :size="13" />
          <span class="toast__msg">{{ t.message }}</span>
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 18px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 200;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  pointer-events: none;
  width: max-content;
  max-width: 78vw;
}
.toast {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  border-radius: var(--r-8);
  background: var(--bg-1);
  border: 1px solid var(--border-strong);
  box-shadow: var(--shadow-2);
  font-size: var(--fs-13);
  color: var(--text-1);
  white-space: nowrap;
  max-width: 100%;
}
.toast__msg {
  overflow: hidden;
  text-overflow: ellipsis;
}
.toast--success {
  border-color: var(--teal, #34d399);
}
.toast--success svg {
  color: var(--teal, #34d399);
}
.toast--error {
  border-color: var(--danger);
}
.toast--error svg {
  color: var(--danger);
}
.toast--info svg {
  color: var(--accent-text);
}
.toast-enter-active,
.toast-leave-active {
  transition: opacity var(--dur-med) var(--ease), transform var(--dur-med) var(--ease);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
