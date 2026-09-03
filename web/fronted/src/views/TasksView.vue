<script setup lang="ts">
import Icon from '@/components/common/Icon.vue'
</script>

<template>
  <div class="tasks">
    <header class="tasks__head">
      <span class="mono tasks__label">定时任务</span>
      <span class="tasks__sub">界面预览 · 暂未接入任务执行</span>
    </header>
    <div class="tasks__body">
      <div class="tasks__empty">
        <Icon name="calendar" :size="28" />
        <p class="tasks__empty-title">定时任务 · 预览</p>
        <p class="tasks__empty-sub">
          此处将展示已创建的定时任务与执行历史。<br />
          当前版本仅提供界面预览，任务创建与调度功能尚未接入。
        </p>
        <button class="tasks__btn" disabled>
          <Icon name="plus" :size="13" />
          新建任务（暂不可用）
        </button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tasks {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
}
.tasks__head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border);
}
.tasks__label {
  font-size: 10px;
  letter-spacing: 0.14em;
  color: var(--text-3);
}
.tasks__sub {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.tasks__body {
  flex: 1;
  display: flex;
}
.tasks__empty {
  margin: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}
.tasks__empty svg {
  color: var(--text-3);
}
.tasks__empty-title {
  font-size: var(--fs-15);
  font-weight: 600;
  color: var(--text-1);
  margin: 4px 0 0;
}
.tasks__empty-sub {
  font-size: var(--fs-13);
  color: var(--text-3);
  line-height: 1.7;
  margin: 0;
}
.tasks__btn {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  padding: 8px 16px;
  border: 1px solid var(--border-strong);
  background: var(--bg-1);
  border-radius: var(--r-6);
  color: var(--text-3);
  font-size: var(--fs-13);
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
