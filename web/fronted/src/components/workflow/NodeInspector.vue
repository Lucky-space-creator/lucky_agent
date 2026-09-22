<script setup lang="ts">
/**
 * 节点属性面板：表单字段全部由后端元数据渲染。
 *
 * <p>JSON 类型字段保留一份本地草稿文本：用户输入过程中的非法 JSON 不会被写回定义，
 * 只在下方提示错误 —— 否则「边输边写」会把坏数据落盘，保存后才发现引擎读不到。</p>
 */
import { computed, ref, watch } from 'vue'
import Icon from '@/components/common/Icon.vue'
import type { WorkflowConfigField, WorkflowNodeDef, WorkflowNodeTypeMeta } from '@/api/types'

const props = defineProps<{
  node: WorkflowNodeDef | null
  meta: WorkflowNodeTypeMeta | null
  /** 该节点的出边（CONDITION 分流用，可直接在面板里写条件）。 */
  outEdges: { id: string; targetLabel: string; condition?: string; label?: string }[]
  canRemove: boolean
}>()

const emit = defineEmits<{
  (e: 'patch', p: { name?: string }): void
  (e: 'setConfig', p: { key: string; value: any }): void
  (e: 'setEdge', p: { id: string; patch: { condition?: string; label?: string } }): void
  (e: 'remove', id: string): void
}>()

const cfg = computed(() => props.node?.config ?? {})

/** JSON 字段草稿（按 key 暂存），切换节点时清空。 */
const draft = ref<Record<string, string>>({})
const draftErr = ref<Record<string, string>>({})

watch(
  () => props.node?.id,
  () => {
    draft.value = {}
    draftErr.value = {}
  },
)

const isLongField = (f: WorkflowConfigField) => f.input === 'TEXTAREA' || f.input === 'JSON'

/** 出边条件只在 CONDITION 节点上有意义 —— 后端分支路由读的是边的 condition。 */
const showEdges = computed(() => props.meta?.type === 'CONDITION' && props.outEdges.length > 0)

function textValue(f: WorkflowConfigField): string {
  const v = cfg.value[f.key]
  return v === undefined || v === null ? '' : String(v)
}

function jsonValue(f: WorkflowConfigField): string {
  if (draft.value[f.key] !== undefined) return draft.value[f.key]
  const v = cfg.value[f.key]
  if (v === undefined || v === null) return ''
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return ''
  }
}

function onText(f: WorkflowConfigField, v: string) {
  emit('setConfig', { key: f.key, value: v })
}

function onNumber(f: WorkflowConfigField, v: string) {
  const n = v === '' ? 0 : Number(v)
  if (!Number.isNaN(n)) emit('setConfig', { key: f.key, value: n })
}

function onBool(f: WorkflowConfigField, v: boolean) {
  emit('setConfig', { key: f.key, value: v })
}

function onJson(f: WorkflowConfigField, v: string) {
  draft.value = { ...draft.value, [f.key]: v }
  const trimmed = v.trim()
  if (!trimmed) {
    draftErr.value = { ...draftErr.value, [f.key]: '' }
    emit('setConfig', { key: f.key, value: null })
    return
  }
  try {
    const parsed = JSON.parse(trimmed)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      draftErr.value = { ...draftErr.value, [f.key]: '必须是一个 JSON 对象（形如 {"k": "v"}）' }
      return
    }
    draftErr.value = { ...draftErr.value, [f.key]: '' }
    emit('setConfig', { key: f.key, value: parsed })
  } catch (e: any) {
    draftErr.value = { ...draftErr.value, [f.key]: 'JSON 解析失败：' + (e?.message ?? e) }
  }
}

function onEdgeCond(id: string, v: string) {
  emit('setEdge', { id, patch: { condition: v } })
}

function onEdgeLabel(id: string, v: string) {
  emit('setEdge', { id, patch: { label: v } })
}
</script>

<template>
  <aside class="wfi">
    <div class="wfi__head">
      <span class="wfi__head-label">属性</span>
      <span v-if="meta" class="wfi__tag mono">{{ meta.type }}</span>
    </div>

    <div v-if="!node || !meta" class="wfi__empty">
      <Icon name="sliders" :size="20" />
      <p>选中画布上的节点以编辑其参数</p>
    </div>

    <div v-else class="wfi__body">
      <div class="wfi__id mono">{{ node.id }}</div>
      <p class="wfi__desc">{{ meta.description }}</p>

      <label class="wfi__field">
        <span class="wfi__label">名称</span>
        <input
          class="wfi__input"
          :value="node.name ?? ''"
          placeholder="用于画布与日志展示"
          @input="emit('patch', { name: ($event.target as HTMLInputElement).value })"
        />
      </label>

      <p v-if="!meta.fields.length" class="wfi__none">
        {{ meta.type === 'START' ? '入口节点无需参数，触发时的变量直接进入作用域。' : '该类型暂无参数。' }}
      </p>

      <div v-for="f in meta.fields" :key="f.key" class="wfi__field">
        <span class="wfi__label">
          {{ f.label }}
          <span v-if="f.required" class="wfi__req">*</span>
        </span>

        <select
          v-if="f.input === 'SELECT'"
          class="wfi__input"
          :value="textValue(f)"
          @change="onText(f, ($event.target as HTMLSelectElement).value)"
        >
          <option value="">（未选择）</option>
          <option v-for="o in f.options ?? []" :key="o.value" :value="o.value">{{ o.label }}</option>
        </select>

        <label v-else-if="f.input === 'BOOLEAN'" class="wfi__check">
          <input
            type="checkbox"
            :checked="!!cfg[f.key]"
            @change="onBool(f, ($event.target as HTMLInputElement).checked)"
          />
          <span>启用</span>
        </label>

        <input
          v-else-if="f.input === 'NUMBER'"
          class="wfi__input"
          type="number"
          :value="cfg[f.key] ?? ''"
          :placeholder="f.placeholder ?? ''"
          @input="onNumber(f, ($event.target as HTMLInputElement).value)"
        />

        <textarea
          v-else-if="isLongField(f)"
          class="wfi__input wfi__input--area"
          :class="{ 'wfi__input--bad': f.input === 'JSON' && draftErr[f.key] }"
          :rows="f.input === 'JSON' ? 4 : 5"
          :spellcheck="false"
          :value="f.input === 'JSON' ? jsonValue(f) : textValue(f)"
          :placeholder="f.placeholder ?? ''"
          @input="
            f.input === 'JSON'
              ? onJson(f, ($event.target as HTMLTextAreaElement).value)
              : onText(f, ($event.target as HTMLTextAreaElement).value)
          "
        />

        <input
          v-else
          class="wfi__input"
          :value="textValue(f)"
          :placeholder="f.placeholder ?? ''"
          @input="onText(f, ($event.target as HTMLInputElement).value)"
        />

        <span v-if="f.input === 'JSON' && draftErr[f.key]" class="wfi__err">{{ draftErr[f.key] }}</span>
        <span v-else-if="f.hint" class="wfi__hint">{{ f.hint }}</span>
      </div>

      <!-- 出边条件：CONDITION 节点的分支路由由边的 condition 决定 -->
      <template v-if="showEdges">
        <div class="wfi__sep" />
        <div class="wfi__label wfi__label--block">
          分支条件
          <span class="wfi__hint wfi__hint--inline">为每条出边写表达式，求值为真的分支才会走</span>
        </div>
        <div v-for="e in outEdges" :key="e.id" class="wfi__edge">
          <div class="wfi__edge-top mono">
            <Icon name="chevronRight" :size="11" />
            {{ e.targetLabel }}
          </div>
          <input
            class="wfi__input"
            :value="e.condition ?? ''"
            placeholder="留空 = 无条件（必走）"
            @input="onEdgeCond(e.id, ($event.target as HTMLInputElement).value)"
          />
          <input
            class="wfi__input wfi__input--sm"
            :value="e.label ?? ''"
            placeholder="标签（可选，仅展示）"
            @input="onEdgeLabel(e.id, ($event.target as HTMLInputElement).value)"
          />
        </div>
      </template>

      <div class="wfi__acts">
        <button class="wfi__btn wfi__btn--danger" :disabled="!canRemove" @click="emit('remove', node.id)">
          <Icon name="trash" :size="12" />
          删除节点
        </button>
      </div>
      <p v-if="!canRemove" class="wfi__hint">START / END 为流程必需节点，不可删除。</p>
    </div>
  </aside>
</template>

<style scoped>
.wfi {
  width: 278px;
  flex-shrink: 0;
  border-left: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  background: var(--bg-1);
  overflow: hidden;
}
.wfi__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border);
}
.wfi__head-label {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.wfi__tag {
  margin-left: auto;
  font-size: 9px;
  letter-spacing: 0.08em;
  padding: 2px 6px;
  border-radius: var(--r-4);
  background: var(--accent-dim);
  color: var(--accent-text);
}
.wfi__empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
  padding: 20px;
  text-align: center;
}
.wfi__empty p {
  margin: 0;
  font-size: var(--fs-12);
  line-height: 1.7;
}
.wfi__body {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
}
.wfi__id {
  font-size: var(--fs-11);
  color: var(--text-3);
  word-break: break-all;
}
.wfi__desc {
  margin: 6px 0 14px;
  font-size: var(--fs-12);
  color: var(--text-2);
  line-height: 1.7;
}
.wfi__field {
  display: block;
  margin-bottom: 12px;
}
.wfi__label {
  display: block;
  font-size: var(--fs-12);
  color: var(--text-2);
  margin-bottom: 5px;
}
.wfi__label--block {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex-wrap: wrap;
}
.wfi__req {
  color: var(--danger);
}
.wfi__input {
  width: 100%;
  padding: 7px 9px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-0);
  color: var(--text-1);
  font-size: var(--fs-12);
  font-family: inherit;
  box-sizing: border-box;
}
.wfi__input:focus {
  outline: none;
  border-color: var(--accent);
  box-shadow: var(--glow-accent);
}
.wfi__input--area {
  resize: vertical;
  font-family: var(--font-mono);
  line-height: 1.6;
}
.wfi__input--bad {
  border-color: var(--danger);
}
.wfi__input--sm {
  margin-top: 6px;
}
.wfi__check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--fs-12);
  color: var(--text-1);
}
.wfi__hint {
  display: block;
  margin-top: 4px;
  font-size: var(--fs-10);
  color: var(--text-3);
  line-height: 1.6;
}
.wfi__hint--inline {
  margin: 0;
}
.wfi__err {
  display: block;
  margin-top: 4px;
  font-size: var(--fs-10);
  color: var(--danger-text);
  line-height: 1.6;
}
.wfi__none {
  font-size: var(--fs-12);
  color: var(--text-3);
  line-height: 1.7;
  margin: 0 0 12px;
}
.wfi__sep {
  height: 1px;
  background: var(--border);
  margin: 16px 0 12px;
}
.wfi__edge {
  padding: 8px;
  margin-bottom: 8px;
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  background: var(--bg-0);
}
.wfi__edge-top {
  display: flex;
  align-items: center;
  gap: 3px;
  font-size: var(--fs-11);
  color: var(--text-2);
  margin-bottom: 6px;
}
.wfi__acts {
  margin-top: 18px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}
.wfi__btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  width: 100%;
  padding: 7px;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-0);
  color: var(--text-1);
  font-size: var(--fs-12);
  cursor: pointer;
}
.wfi__btn--danger:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger-text);
}
.wfi__btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
</style>
