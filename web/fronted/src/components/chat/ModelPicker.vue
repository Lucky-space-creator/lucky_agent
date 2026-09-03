<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import Icon from '@/components/common/Icon.vue'
import { useModelStore } from '@/stores/model'

const model = useModelStore()

const open = ref(false)
const root = ref<HTMLElement | null>(null)

// 选中态提到 model store：提交对话时会把该 id 带给后端，否则选择只是本地装饰、不生效
const label = computed(() => model.active?.modelName || '未配置模型')
const activeId = computed(() => model.active?.id ?? null)

function pick(id: string) {
  model.select(id)
  open.value = false
}

function onDoc(e: MouseEvent) {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}
onMounted(() => document.addEventListener('mousedown', onDoc))
onUnmounted(() => document.removeEventListener('mousedown', onDoc))
</script>

<template>
  <div ref="root" class="mp">
    <button class="mp__btn" @click="open = !open">
      <Icon name="cpu" :size="12" />
      <span class="mp__name ellipsis">{{ label }}</span>
      <span class="mp__level">高</span>
      <Icon name="chevronDown" :size="12" class="mp__chev" :class="{ 'mp__chev--open': open }" />
    </button>

    <Transition name="drop">
      <div v-if="open" class="mp__menu">
        <div v-if="model.configs.length === 0" class="mp__empty">尚未配置模型端点</div>
        <button v-for="c in model.configs" :key="c.id" class="mp__opt" :class="{ 'mp__opt--on': activeId === c.id }" @click="pick(c.id!)">
          <Icon name="cpu" :size="12" />
          <span class="ellipsis mp__opt-name">{{ c.modelName }}</span>
          <span v-if="c.role === 'main'" class="mp__tag">主</span>
          <Icon v-if="activeId === c.id" name="check" :size="11" class="mp__check" />
        </button>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.mp {
  position: relative;
}
.mp__btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 5px 9px;
  background: var(--bg-0);
  border: 1px solid var(--border);
  border-radius: var(--r-pill);
  color: var(--text-2);
  font-size: var(--fs-12);
  transition: border-color var(--dur-fast) var(--ease), color var(--dur-fast) var(--ease),
    background var(--dur-fast) var(--ease);
}
.mp__btn:hover {
  border-color: var(--accent-border);
  color: var(--text-1);
}
.mp__btn svg {
  color: var(--accent-text);
  flex-shrink: 0;
}
.mp__name {
  min-width: 0;
}
.mp__level {
  font-size: 10px;
  font-weight: 600;
  color: var(--accent-text);
  background: var(--accent-dim);
  border-radius: var(--r-pill);
  padding: 1px 6px;
  flex-shrink: 0;
}
.mp__chev {
  transition: transform var(--dur-med) var(--ease);
}
.mp__chev--open {
  transform: rotate(180deg);
}
.mp__menu {
  position: absolute;
  right: 0;
  bottom: calc(100% + 6px);
  z-index: 40;
  min-width: 200px;
  max-width: 260px;
  max-height: 320px;
  overflow-y: auto;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-10);
  box-shadow: var(--shadow-2);
  padding: 5px;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.mp__empty {
  font-size: var(--fs-12);
  color: var(--text-3);
  padding: 8px 10px;
}
.mp__opt {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 9px;
  border: none;
  background: none;
  border-radius: var(--r-6);
  color: var(--text-2);
  font-size: var(--fs-12);
  text-align: left;
}
.mp__opt svg {
  color: var(--text-3);
  flex-shrink: 0;
}
.mp__opt:hover {
  background: var(--bg-2);
  color: var(--text-1);
}
.mp__opt--on {
  color: var(--accent-text);
  font-weight: 500;
}
.mp__opt--on svg {
  color: var(--accent);
}
.mp__opt-name {
  flex: 1;
  min-width: 0;
}
.mp__tag {
  font-size: 9px;
  color: var(--accent-text);
  border: 1px solid var(--accent-border);
  border-radius: var(--r-pill);
  padding: 0 5px;
  flex-shrink: 0;
}
.mp__check {
  flex-shrink: 0;
}
.drop-enter-active,
.drop-leave-active {
  transition: opacity var(--dur-fast) var(--ease), transform var(--dur-fast) var(--ease);
}
.drop-enter-from,
.drop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
