<script setup lang="ts">
withDefaults(
  defineProps<{
    tone?: 'accent' | 'teal' | 'warn' | 'danger' | 'neutral'
    dot?: boolean
  }>(),
  { tone: 'neutral', dot: false },
)
</script>

<template>
  <span class="badge" :class="`badge--${tone}`">
    <span v-if="dot" class="badge__dot" />
    <slot />
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 1px 8px;
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 18px;
  border-radius: var(--r-pill);
  border: 1px solid var(--border);
  color: var(--text-2);
  background: var(--bg-2);
  white-space: nowrap;
}
.badge--accent {
  color: var(--accent-text);
  border-color: var(--accent-border);
  background: var(--accent-dim);
}
.badge--teal {
  color: var(--teal);
  border-color: rgba(95, 176, 169, 0.4);
  background: var(--teal-dim);
}
.badge--warn {
  color: var(--warn);
  border-color: rgba(217, 164, 65, 0.4);
  background: var(--warn-dim);
}
.badge--danger {
  color: var(--danger-text);
  border-color: rgba(224, 84, 84, 0.4);
  background: var(--danger-dim);
}
.badge__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
}
</style>
