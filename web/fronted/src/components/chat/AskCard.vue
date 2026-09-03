<script setup lang="ts">
import Icon from '@/components/common/Icon.vue'
import type { AskView } from '@/stores/chat'

const props = defineProps<{ ask: AskView }>()

const emit = defineEmits<{ confirm: [allow: boolean] }>()

function targetLabel(): string {
  if (!props.ask.op?.path) return ''
  return props.ask.op.opType === 'EXEC' ? props.ask.op.args?.command ?? '' : props.ask.op.path
}
</script>

<template>
  <div class="ask">
    <div class="ask__head">
      <span class="ask__ic"><Icon name="key" :size="13" /></span>
      <span class="ask__label mono">需要你的确认</span>
      <span class="ask__risk mono" v-if="ask.risk">{{ ask.risk }}</span>
    </div>
    <p class="ask__question">{{ ask.question }}</p>
    <p v-if="targetLabel()" class="ask__target mono ellipsis">{{ targetLabel() }}</p>
    <div class="ask__actions">
      <button class="btn btn--primary" @click="emit('confirm', true)">
        <Icon name="check" :size="13" /> 允许
      </button>
      <button class="btn btn--ghost" @click="emit('confirm', false)">
        <Icon name="x" :size="13" /> 拒绝
      </button>
    </div>
  </div>
</template>

<style scoped>
.ask {
  margin: 8px 0;
  border: 1px solid var(--warn);
  border-radius: var(--r-6);
  background: var(--warn-dim);
  padding: 10px 12px;
}
.ask__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.ask__ic {
  color: var(--warn);
  display: flex;
}
.ask__label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--warn);
}
.ask__risk {
  font-size: 10px;
  color: var(--danger-text);
}
.ask__question {
  margin-top: 6px;
  font-size: var(--fs-13);
  color: var(--text-1);
}
.ask__target {
  margin-top: 5px;
  font-size: 11px;
  color: var(--text-2);
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  padding: 4px 8px;
}
.ask__actions {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}
</style>
