<script setup lang="ts">
import Icon from './Icon.vue'

withDefaults(defineProps<{ open: boolean; title?: string; width?: string }>(), {
  title: '',
  width: '480px',
})

const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="overlay" @click.self="emit('close')">
        <div class="panel" :style="{ width }" role="dialog" aria-modal="true">
          <header class="head">
            <h3 class="title">{{ title }}</h3>
            <button class="close" aria-label="关闭" @click="emit('close')">
              <Icon name="x" :size="15" />
            </button>
          </header>
          <div class="body">
            <slot />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(4, 4, 6, 0.6);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 10vh 16px 16px;
}
.panel {
  background: var(--bg-1);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-8);
  box-shadow: var(--shadow-2);
  max-height: 80vh;
  display: flex;
  flex-direction: column;
}
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
}
.title {
  font-size: var(--fs-15);
  font-weight: 600;
  color: var(--text-0);
}
.close {
  background: none;
  border: none;
  color: var(--text-3);
  padding: 4px;
  border-radius: var(--r-4);
  display: flex;
}
.close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.body {
  padding: 16px;
  overflow-y: auto;
}
.modal-enter-active,
.modal-leave-active {
  transition: opacity var(--dur-med) var(--ease);
}
.modal-enter-active .panel,
.modal-leave-active .panel {
  transition: transform var(--dur-med) var(--ease);
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .panel,
.modal-leave-to .panel {
  transform: translateY(-6px);
}
</style>
