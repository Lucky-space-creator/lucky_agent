<script setup lang="ts">
import { ref } from 'vue'
import Icon from '@/components/common/Icon.vue'
import type { OptionsView, OptionItemView } from '@/stores/chat'

const props = defineProps<{ options: OptionsView }>()

const emit = defineEmits<{
  pick: [opt: OptionItemView]
  custom: [text: string]
}>()

const customOpen = ref(false)
const customText = ref('')

/** 卡片已锁定（用户已选或超时自动选优后回填），禁止重复提交。 */
function locked(): boolean {
  return !!props.options.pickedId
}

function pick(opt: OptionItemView) {
  if (locked()) return
  emit('pick', opt)
}

function submitCustom() {
  if (locked() || !customText.value.trim()) return
  emit('custom', customText.value.trim())
}

/** 超时提示：后端在 timeoutSec 秒后会代选推荐项。 */
function timeoutText(): string {
  const s = props.options.timeoutSec
  if (!s || s <= 0) return ''
  const min = Math.round(s / 60)
  return min >= 1 ? `${min} 分钟未选择将自动采用推荐方案` : `${s} 秒未选择将自动采用推荐方案`
}
</script>

<template>
  <div class="opt">
    <div class="opt__head">
      <span class="opt__ic"><Icon name="sparkles" :size="13" /></span>
      <span class="opt__label mono">请选择</span>
      <span v-if="timeoutText()" class="opt__hint mono">{{ timeoutText() }}</span>
    </div>
    <p class="opt__question">{{ options.question }}</p>

    <div class="opt__list">
      <button
        v-for="o in options.options"
        :key="o.id"
        class="opt__item"
        :class="{ 'opt__item--picked': options.pickedId === o.id, 'opt__item--locked': locked() }"
        :disabled="locked()"
        @click="pick(o)"
      >
        <span class="opt__dot" />
        <span class="opt__body">
          <span class="opt__title">
            {{ o.label }}
            <span v-if="o.recommended" class="opt__rec mono">推荐</span>
          </span>
          <span v-if="o.detail" class="opt__detail">{{ o.detail }}</span>
        </span>
        <Icon v-if="options.pickedId === o.id" name="check" :size="13" class="opt__check" />
      </button>
    </div>

    <!-- 自定义补充：允许用户输入选项之外的方案 -->
    <div v-if="options.allowCustom && !locked()" class="opt__custom">
      <button v-if="!customOpen" class="opt__custom-toggle" @click="customOpen = true">
        <Icon name="plus" :size="12" />
        {{ options.customHint || '其他（请补充说明）' }}
      </button>
      <div v-else class="opt__custom-box">
        <input
          v-model="customText"
          class="opt__input"
          :placeholder="options.customHint || '请输入你的方案…'"
          @keydown.enter="submitCustom"
        />
        <button class="btn btn--primary btn--sm" :disabled="!customText.trim()" @click="submitCustom">
          确定
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.opt {
  margin: 8px 0;
  border: 1px solid var(--border-strong, var(--border));
  border-radius: var(--r-6);
  background: var(--bg-1);
  padding: 10px 12px;
}
.opt__head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.opt__ic {
  color: var(--accent, var(--text-1));
  display: flex;
}
.opt__label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-2);
}
.opt__hint {
  margin-left: auto;
  font-size: 10px;
  color: var(--text-3, var(--text-2));
}
.opt__question {
  margin-top: 6px;
  font-size: var(--fs-13);
  color: var(--text-1);
}
.opt__list {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.opt__item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
  text-align: left;
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  background: var(--bg-0, var(--bg-1));
  padding: 8px 10px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.opt__item:hover:not(.opt__item--locked) {
  border-color: var(--accent, var(--text-2));
}
.opt__item--picked {
  border-color: var(--accent, var(--text-2));
  background: var(--accent-dim, var(--bg-1));
}
.opt__item--locked {
  cursor: default;
  opacity: 0.75;
}
.opt__dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 5px;
  border-radius: 50%;
  background: var(--text-3, var(--text-2));
}
.opt__item--picked .opt__dot {
  background: var(--accent, var(--text-1));
}
.opt__body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.opt__title {
  font-size: var(--fs-13);
  color: var(--text-1);
  display: flex;
  align-items: center;
  gap: 6px;
}
.opt__rec {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--accent, var(--text-1));
  background: var(--accent-dim, var(--bg-1));
  border: 1px solid var(--accent, var(--border));
}
.opt__detail {
  font-size: 11px;
  color: var(--text-2);
  white-space: pre-wrap;
  word-break: break-word;
}
.opt__check {
  flex: none;
  color: var(--accent, var(--text-1));
  margin-top: 2px;
}
.opt__custom {
  margin-top: 8px;
}
.opt__custom-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--text-2);
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px 0;
}
.opt__custom-toggle:hover {
  color: var(--text-1);
}
.opt__custom-box {
  display: flex;
  gap: 6px;
  align-items: center;
}
.opt__input {
  flex: 1;
  font-size: var(--fs-13);
  color: var(--text-1);
  background: var(--bg-0, var(--bg-1));
  border: 1px solid var(--border);
  border-radius: var(--r-4);
  padding: 5px 8px;
  outline: none;
}
.opt__input:focus {
  border-color: var(--accent, var(--text-2));
}
.btn--sm {
  padding: 4px 10px;
  font-size: 12px;
}
</style>
