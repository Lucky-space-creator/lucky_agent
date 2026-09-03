<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import Icon from '@/components/common/Icon.vue'
import { useModelStore } from '@/stores/model'

const model = useModelStore()

const open = ref(false)
const draft = ref(2)
let timer: ReturnType<typeof setTimeout> | null = null
const root = ref<HTMLElement | null>(null)

const currentLabel = computed(() => model.depthLabel)
const draftLabel = computed(() => model.depthOptions[draft.value]?.label ?? '标准思考')
const draftDesc = computed(() => model.depthOptions[draft.value]?.desc ?? '')

function toggle() {
  open.value = !open.value
  if (open.value) draft.value = model.depthIndex
}

/** 拖动中：本地 draft 即时生效（标签随动），落库防抖 300ms 保持流畅。 */
function onInput(e: Event) {
  draft.value = Number((e.target as HTMLInputElement).value)
  if (timer) clearTimeout(timer)
  timer = setTimeout(commit, 300)
}

/** 松手：立即提交，避免防抖滞后。 */
function onChange(e: Event) {
  draft.value = Number((e.target as HTMLInputElement).value)
  commit()
}

async function commit() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
  const value = model.depthOptions[draft.value]?.value
  if (!value || value === model.inferenceDepth) return
  try {
    await model.saveDepth(value)
  } catch {
    // 保存失败回退到后端实际值
    await model.loadDepth()
    draft.value = model.depthIndex
  }
}

function onDoc(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => {
  document.addEventListener('mousedown', onDoc)
  // 挂载时同步后端实际深度，保证按钮标签准确
  model.loadDepth().catch(() => {})
})
onUnmounted(() => {
  document.removeEventListener('mousedown', onDoc)
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <div ref="root" class="dp">
    <button type="button" class="dp__btn" :class="{ 'dp__btn--on': open }" :title="currentLabel" @click="toggle">
      <Icon name="gauge" :size="12" />
      <span class="dp__label">{{ open ? draftLabel : currentLabel }}</span>
      <Icon name="chevronDown" :size="10" class="dp__chev" :class="{ 'dp__chev--open': open }" />
    </button>

    <Transition name="drop">
      <div v-if="open" class="dp__pop">
        <div class="dp__head">
          <span class="dp__title">推理深度</span>
          <span class="dp__cur">{{ draftLabel }}</span>
        </div>
        <p class="dp__desc">{{ draftDesc }}</p>
        <input
          type="range"
          class="dp__range"
          min="0"
          max="4"
          step="1"
          :value="draft"
          @input="onInput"
          @change="onChange"
        />
        <div class="dp__ticks">
          <span
            v-for="(o, i) in model.depthOptions"
            :key="o.value"
            class="dp__tick"
            :class="{ 'dp__tick--on': i === draft }"
          >{{ o.short }}</span>
        </div>
        <p class="dp__hint">作用于后续模型请求（Anthropic thinking / OpenAI reasoning_effort）</p>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.dp {
  position: relative;
}
.dp__btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 9px;
  background: var(--bg-1);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
    background var(--dur-fast) var(--ease);
}
.dp__btn:hover {
  border-color: var(--accent-border);
  color: var(--text-1);
}
.dp__btn--on {
  border-color: var(--accent-border);
  color: var(--accent-text);
  background: var(--accent-dim);
}
.dp__btn svg:first-child {
  color: var(--accent-text);
  flex-shrink: 0;
}
.dp__label {
  white-space: nowrap;
}
.dp__chev {
  transition: transform var(--dur-med) var(--ease);
}
.dp__chev--open {
  transform: rotate(180deg);
}
.dp__pop {
  position: absolute;
  left: 0;
  bottom: calc(100% + 8px);
  z-index: 40;
  width: 260px;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-10);
  box-shadow: var(--shadow-2);
  padding: 12px 14px 10px;
}
.dp__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.dp__title {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--text-1);
}
.dp__cur {
  font-size: var(--fs-12);
  font-weight: 600;
  color: var(--accent-text);
}
.dp__desc {
  font-size: 11px;
  color: var(--text-3);
  margin-top: 4px;
  min-height: 15px;
}
.dp__range {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 4px;
  border-radius: var(--r-pill);
  background: var(--bg-3);
  outline: none;
  margin: 10px 0 2px;
}
.dp__range::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent);
  border: 2.5px solid #fff;
  box-shadow: var(--shadow-1);
  cursor: pointer;
}
.dp__range::-moz-range-thumb {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--accent);
  border: 2.5px solid #fff;
  cursor: pointer;
}
.dp__ticks {
  display: flex;
  justify-content: space-between;
  padding-top: 4px;
}
.dp__tick {
  font-size: 10px;
  color: var(--text-3);
  white-space: nowrap;
}
.dp__tick--on {
  color: var(--accent-text);
  font-weight: 600;
}
.dp__hint {
  font-size: 10px;
  color: var(--text-3);
  margin-top: 8px;
  line-height: 1.5;
}
.drop-enter-active,
.drop-leave-active {
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(6px);
}
</style>
