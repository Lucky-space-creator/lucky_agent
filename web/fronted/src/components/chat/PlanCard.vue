<script setup lang="ts">
import { computed } from 'vue'
import type { TaskPlanView } from '@/stores/chat'
import Icon from '@/components/common/Icon.vue'

const props = defineProps<{ plan: TaskPlanView[] }>()

const done = computed(() => props.plan.filter((t) => t.status === 'done').length)
const percent = computed(() =>
  props.plan.length === 0 ? 0 : Math.round((done.value / props.plan.length) * 100),
)

function statusIcon(status: TaskPlanView['status']): string {
  return { done: 'check', running: 'terminal', pending: '', failed: 'x', ask: 'key' }[status]
}
</script>

<template>
  <div class="plan">
    <div class="plan__head">
      <span class="mono plan__label">任务计划</span>
      <span class="plan__count mono">{{ done }}/{{ plan.length }}</span>
      <div class="plan__bar"><div class="plan__bar-fill" :style="{ width: percent + '%' }" /></div>
    </div>
    <ul class="plan__list">
      <li v-for="t in plan" :key="t.taskId" class="plan__item" :class="`plan__item--${t.status}`">
        <span v-if="statusIcon(t.status)" class="plan__ic"><Icon :name="statusIcon(t.status)" :size="12" /></span>
        <span v-else class="plan__ic plan__ic--empty" />
        <span class="plan__title ellipsis">{{ t.title }}</span>
        <span class="plan__state mono">{{ t.status }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.plan {
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  background: var(--bg-1);
  overflow: hidden;
  margin: 8px 0;
}
.plan__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-bottom: 1px solid var(--border);
}
.plan__label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.plan__count {
  font-size: 11px;
  color: var(--text-2);
}
.plan__bar {
  flex: 1;
  height: 3px;
  background: var(--bg-3);
  border-radius: var(--r-pill);
  overflow: hidden;
}
.plan__bar-fill {
  height: 100%;
  background: var(--accent);
  transition: width var(--dur-med) var(--ease);
}
.plan__list {
  list-style: none;
  padding: 4px 0;
}
.plan__item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px;
  font-size: var(--fs-13);
  color: var(--text-1);
}
.plan__ic {
  display: flex;
  width: 15px;
  flex-shrink: 0;
}
.plan__item--done .plan__ic {
  color: var(--accent);
}
.plan__item--running .plan__ic {
  color: var(--accent);
}
.plan__item--failed .plan__ic {
  color: var(--danger-text);
}
.plan__item--ask .plan__ic {
  color: var(--warn);
}
.plan__ic--empty {
  width: 12px;
  height: 12px;
  border: 1px solid var(--border-strong);
  border-radius: 50%;
}
.plan__title {
  flex: 1;
}
.plan__state {
  font-size: 10px;
  color: var(--text-3);
}
</style>
