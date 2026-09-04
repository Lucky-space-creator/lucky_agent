<script setup lang="ts">
// 编译器风格代码预览：语法高亮 + 行号 + 语言徽标，适配浅/深主题。
// 代码高亮来自 highlight.ts（hljs 已转义输入，v-html 安全）。

import { computed } from 'vue'
import Badge from '@/components/common/Badge.vue'
import { detectLanguage, highlightText } from '@/utils/highlight'

const props = defineProps<{
  /** 文件完整路径（用于按扩展名识别语言）。 */
  path?: string
  /** 文件原始内容。 */
  content: string
}>()

// ---- 类型识别：语言徽标 + 高亮类型 ----
const info = computed(() => detectLanguage(props.path || 'file'))
const hl = computed(() => highlightText(props.content ?? '', props.path || ''))

/** 是否按「代码块」渲染（有高亮）。 */
const isCode = computed(() => hl.value.isCode)

/** 行号（1..N），用于边栏渲染。 */
const lineNumbers = computed(() => {
  const n = (props.content ?? '').split('\n').length
  return Array.from({ length: n }, (_, i) => i + 1)
})

/** 按语言分组的徽标色调。 */
const badgeTone = computed<'accent' | 'teal' | 'danger' | 'warn' | 'neutral'>(() => {
  switch (info.value.label.toLowerCase()) {
    case 'java': case 'python': return 'teal'
    case 'kotlin': case 'typescript': case 'css': case 'scss': case 'less': return 'accent'
    case 'javascript': return 'warn'
    case 'html': case 'xml': case 'vue': return 'danger'
    default: return 'neutral'
  }
})

/** 无内容的空态（仍保留语言徽标，但内容区提示）。 */
const isEmpty = computed(() => !props.content || props.content.length === 0)
</script>

<template>
  <div class="cv">
    <!-- 头部信息条：路径徽标 + 语言 + 行列 -->
    <header class="cv__head">
      <div class="cv__title">
        <Badge :tone="isCode ? badgeTone : 'neutral'">{{ info.label }}</Badge>
        <span class="cv__path mono">{{ path }}</span>
      </div>
      <span v-if="isCode" class="cv__meta mono">{{ lineNumbers.length }} 行 · {{ info.label }}</span>
    </header>

    <!-- 代码 / 纯文本内容区 -->
    <div class="cv__body" :class="{ 'cv__body--text': !isCode }">
      <!-- 空文件 -->
      <div v-if="isEmpty" class="cv__empty mono">（空文件）</div>

      <!-- 代码文件：语法高亮 + 行号（编译器样式） -->
      <div v-else-if="isCode" class="cv__code">
        <div class="cv__gutter mono" aria-hidden="true">
          <span v-for="n in lineNumbers" :key="n" class="cv__ln">{{ n }}</span>
        </div>
        <pre class="cv__hl"><code v-html="hl.html"></code></pre>
      </div>

      <!-- 纯文本 / 非代码文件：编辑器风格纯文本预览 -->
      <div v-else class="cv__plain">
        <div class="cv__gutter mono" aria-hidden="true">
          <span v-for="n in lineNumbers" :key="n" class="cv__ln">{{ n }}</span>
        </div>
        <pre class="cv__plain-text">{{ content }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cv {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-0);
  border: 1px solid var(--border);
  border-radius: var(--r-8);
  overflow: hidden;
}
.cv__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border);
  background: var(--bg-1);
}
.cv__title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.cv__path {
  font-size: var(--fs-12);
  color: var(--text-2);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cv__meta {
  font-size: 10px;
  color: var(--text-3);
  flex-shrink: 0;
}
.cv__body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  background: var(--bg-0);
  position: relative;
}
/* 编辑器风格的代码区：行号列 + 代码共用同一滚动容器 */
.cv__code,
.cv__plain {
  display: flex;
  min-width: max-content;
  min-height: 100%;
}
.cv__gutter {
  flex: none;
  width: 46px;
  text-align: right;
  padding: 10px 10px 12px 0;
  user-select: none;
  color: var(--text-3);
  border-right: 1px solid var(--border-faint);
  background: var(--bg-1);
  font-size: var(--fs-12);
  line-height: 1.6;
}
.cv__ln {
  display: block;
  min-height: 1.6em;
  padding-right: 10px;
}
.cv__hl,
.cv__plain-text {
  flex: 1;
  margin: 0;
  padding: 10px 16px 12px;
  font-family: var(--font-mono);
  font-size: var(--fs-13);
  line-height: 1.6;
  white-space: pre;
  tab-size: 4;
  color: var(--text-1);
  background: transparent;
}
.cv__empty {
  padding: 16px;
  color: var(--text-3);
  font-size: var(--fs-13);
}
/* 非代码文本：与代码区一致的等宽排版 */
.cv__plain-text {
  color: var(--text-2);
}
</style>

<style>
/* 语法高亮 token 配色：经 hljs 输出的 .hljs-xxx，需作用在渲染后的子元素上，
   故用非 scoped 全局样式；深浅主题用 data-theme 控制。 */

/* ---- 全局配色变量（浅色默认） ---- */
.cv .hljs-comment,
.cv .hljs-quote {
  color: #6a737d;
  font-style: italic;
}
.cv .hljs-keyword,
.cv .hljs-selector-tag,
.cv .hljs-literal,
.cv .hljs-section {
  color: #d73a49;
}
.cv .hljs-string,
.cv .hljs-regexp,
.cv .hljs-addition,
.cv .hljs-attribute,
.cv .hljs-meta .hljs-string {
  color: #0a7d33;
}
.cv .hljs-number,
.cv .hljs-symbol,
.cv .hljs-bullet,
.cv .hljs-link {
  color: #005cc5;
}
.cv .hljs-title,
.cv .hljs-title.function_,
.cv .hljs-name,
.cv .hljs-variable,
.cv .hljs-template-variable,
.cv .hljs-params {
  color: #e36209;
}
.cv .hljs-built_in,
.cv .hljs-type,
.cv .hljs-class .hljs-title,
.cv .hljs-tag,
.cv .hljs-selector-class,
.cv .hljs-selector-id {
  color: #005cc5;
}
.cv .hljs-meta,
.cv .hljs-meta .hljs-keyword {
  color: #6f42c1;
}
.cv .hljs-attr,
.cv .hljs-attribute,
.cv .hljs-selector-attr,
.cv .hljs-selector-pseudo {
  color: #0a7d33;
}
.cv .hljs-deletion {
  color: #b31d28;
}
.cv .hljs-emphasis {
  font-style: italic;
}
.cv .hljs-strong {
  font-weight: 600;
}
.cv .hljs-doctag,
.cv .hljs-keyword {
  font-weight: 600;
}

/* ---- 深色主题覆盖 ---- */
[data-theme='dark'] .cv .hljs-comment,
[data-theme='dark'] .cv .hljs-quote {
  color: #7d8799;
}
[data-theme='dark'] .cv .hljs-keyword,
[data-theme='dark'] .cv .hljs-selector-tag,
[data-theme='dark'] .cv .hljs-literal,
[data-theme='dark'] .cv .hljs-section {
  color: #ff7b72;
}
[data-theme='dark'] .cv .hljs-string,
[data-theme='dark'] .cv .hljs-regexp,
[data-theme='dark'] .cv .hljs-addition,
[data-theme='dark'] .cv .hljs-attribute,
[data-theme='dark'] .cv .hljs-meta .hljs-string {
  color: #7ee787;
}
[data-theme='dark'] .cv .hljs-number,
[data-theme='dark'] .cv .hljs-symbol,
[data-theme='dark'] .cv .hljs-bullet,
[data-theme='dark'] .cv .hljs-link {
  color: #79c0ff;
}
[data-theme='dark'] .cv .hljs-title,
[data-theme='dark'] .cv .hljs-title.function_,
[data-theme='dark'] .cv .hljs-name,
[data-theme='dark'] .cv .hljs-variable,
[data-theme='dark'] .cv .hljs-template-variable,
[data-theme='dark'] .cv .hljs-params {
  color: #ffa657;
}
[data-theme='dark'] .cv .hljs-built_in,
[data-theme='dark'] .cv .hljs-type,
[data-theme='dark'] .cv .hljs-class .hljs-title,
[data-theme='dark'] .cv .hljs-tag,
[data-theme='dark'] .cv .hljs-selector-class,
[data-theme='dark'] .cv .hljs-selector-id {
  color: #79c0ff;
}
[data-theme='dark'] .cv .hljs-meta,
[data-theme='dark'] .cv .hljs-meta .hljs-keyword {
  color: #d2a8ff;
}
[data-theme='dark'] .cv .hljs-attr,
[data-theme='dark'] .cv .hljs-attribute,
[data-theme='dark'] .cv .hljs-selector-attr,
[data-theme='dark'] .cv .hljs-selector-pseudo {
  color: #7ee787;
}
[data-theme='dark'] .cv .hljs-deletion {
  color: #ffa198;
}
</style>
