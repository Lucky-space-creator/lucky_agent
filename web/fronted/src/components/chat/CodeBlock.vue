<script setup lang="ts">
// 聊天/消息内的代码块：语法高亮 + 语言标签 + 复制。
// 高亮来自 utils/highlight（hljs 已转义输入，v-html 安全）。

import { computed, ref } from 'vue'
import { highlightText } from '@/utils/highlight'
import Icon from '@/components/common/Icon.vue'

const props = defineProps<{ code: string; lang?: string }>()

const hl = computed(() => highlightText(props.code || '', 'file.' + (props.lang || ''), props.lang))
const copied = ref(false)
/** 语言徽标文案（未知时隐藏）。 */
const langLabel = computed(() => {
  const tag = (props.lang || '').trim()
  if (!tag) return ''
  return tag.length <= 14 ? tag : tag.slice(0, 14)
})

async function copy() {
  try {
    await navigator.clipboard.writeText(props.code || '')
    copied.value = true
    setTimeout(() => (copied.value = false), 1600)
  } catch {
    // 剪贴板不可用时静默
  }
}
</script>

<template>
  <div class="cblk">
    <header class="cblk__head">
      <span class="cblk__lang mono">{{ langLabel || 'code' }}</span>
      <button class="cblk__copy mono" :title="copied ? '已复制' : '复制代码'" @click="copy">
        <Icon :name="copied ? 'check' : 'copy'" :size="11" />
        {{ copied ? '已复制' : '复制' }}
      </button>
    </header>
    <!-- hl==null 表示纯文本（未识别语言），直接展示原文避免多余标签 -->
    <pre v-if="hl.isCode" class="cblk__pre"><code v-html="hl.html"></code></pre>
    <pre v-else class="cblk__pre cblk__pre--plain">{{ code }}</pre>
  </div>
</template>

<style scoped>
.cblk {
  margin: 8px 0;
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  overflow: hidden;
  background: var(--bg-1);
}
.cblk__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 8px 4px 12px;
  border-bottom: 1px solid var(--border-faint);
  background: var(--bg-1);
}
.cblk__lang {
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cblk__copy {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 6px;
  background: none;
  border: none;
  color: var(--text-3);
  font-size: 10px;
  border-radius: var(--r-4);
  cursor: pointer;
}
.cblk__copy:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.cblk__pre {
  margin: 0;
  padding: 10px 14px;
  overflow: auto;
  background: var(--bg-0);
  font-family: var(--font-mono);
  font-size: var(--fs-12.5, 12.5px);
  line-height: 1.6;
  white-space: pre;
  tab-size: 4;
  color: var(--text-1);
}
.cblk__pre--plain {
  color: var(--text-2);
}
</style>

<style>
/* 聊天代码块的 token 配色（复用文件预览同套，作用域到 .cblk）。
   深浅主题由 data-theme 控制。 */
.cblk .hljs-comment,
.cblk .hljs-quote {
  color: #6a737d;
  font-style: italic;
}
.cblk .hljs-keyword,
.cblk .hljs-selector-tag,
.cblk .hljs-literal,
.cblk .hljs-section {
  color: #d73a49;
}
.cblk .hljs-string,
.cblk .hljs-regexp,
.cblk .hljs-addition,
.cblk .hljs-attribute,
.cblk .hljs-meta .hljs-string {
  color: #0a7d33;
}
.cblk .hljs-number,
.cblk .hljs-symbol,
.cblk .hljs-bullet,
.cblk .hljs-link {
  color: #005cc5;
}
.cblk .hljs-title,
.cblk .hljs-title.function_,
.cblk .hljs-name,
.cblk .hljs-variable,
.cblk .hljs-template-variable,
.cblk .hljs-params {
  color: #e36209;
}
.cblk .hljs-built_in,
.cblk .hljs-type,
.cblk .hljs-class .hljs-title,
.cblk .hljs-tag,
.cblk .hljs-selector-class,
.cblk .hljs-selector-id {
  color: #005cc5;
}
.cblk .hljs-meta,
.cblk .hljs-meta .hljs-keyword {
  color: #6f42c1;
}
.cblk .hljs-attr,
.cblk .hljs-attribute,
.cblk .hljs-selector-attr,
.cblk .hljs-selector-pseudo {
  color: #0a7d33;
}
.cblk .hljs-deletion {
  color: #b31d28;
}
.cblk .hljs-doctag,
.cblk .hljs-keyword {
  font-weight: 600;
}
[data-theme='dark'] .cblk .hljs-comment,
[data-theme='dark'] .cblk .hljs-quote {
  color: #7d8799;
}
[data-theme='dark'] .cblk .hljs-keyword,
[data-theme='dark'] .cblk .hljs-selector-tag,
[data-theme='dark'] .cblk .hljs-literal,
[data-theme='dark'] .cblk .hljs-section {
  color: #ff7b72;
}
[data-theme='dark'] .cblk .hljs-string,
[data-theme='dark'] .cblk .hljs-regexp,
[data-theme='dark'] .cblk .hljs-addition,
[data-theme='dark'] .cblk .hljs-attribute,
[data-theme='dark'] .cblk .hljs-meta .hljs-string {
  color: #7ee787;
}
[data-theme='dark'] .cblk .hljs-number,
[data-theme='dark'] .cblk .hljs-symbol,
[data-theme='dark'] .cblk .hljs-bullet,
[data-theme='dark'] .cblk .hljs-link {
  color: #79c0ff;
}
[data-theme='dark'] .cblk .hljs-title,
[data-theme='dark'] .cblk .hljs-title.function_,
[data-theme='dark'] .cblk .hljs-name,
[data-theme='dark'] .cblk .hljs-variable,
[data-theme='dark'] .cblk .hljs-template-variable,
[data-theme='dark'] .cblk .hljs-params {
  color: #ffa657;
}
[data-theme='dark'] .cblk .hljs-built_in,
[data-theme='dark'] .cblk .hljs-type,
[data-theme='dark'] .cblk .hljs-class .hljs-title,
[data-theme='dark'] .cblk .hljs-tag,
[data-theme='dark'] .cblk .hljs-selector-class,
[data-theme='dark'] .cblk .hljs-selector-id {
  color: #79c0ff;
}
[data-theme='dark'] .cblk .hljs-meta,
[data-theme='dark'] .cblk .hljs-meta .hljs-keyword {
  color: #d2a8ff;
}
[data-theme='dark'] .cblk .hljs-attr,
[data-theme='dark'] .cblk .hljs-attribute,
[data-theme='dark'] .cblk .hljs-selector-attr,
[data-theme='dark'] .cblk .hljs-selector-pseudo {
  color: #7ee787;
}
[data-theme='dark'] .cblk .hljs-deletion {
  color: #ffa198;
}
</style>
