<script setup lang="ts">
/**
 * 工作流画布：拖拽编排 + 连线 + 自动布局。
 *
 * <p>节点类型与字段全部来自后端 `/api/workflows/node-types`，本组件不硬编码任何
 * config schema —— 后端新增节点类型或改键名时，画布自动跟上，不会出现
 * 「表单填了但引擎读不到」的静默失配。</p>
 */
import { computed, markRaw } from 'vue'
import { MarkerType, Panel, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeChange, NodeChange, NodeMouseEvent } from '@vue-flow/core'
import WfNode from './WfNode.vue'
import Icon from '@/components/common/Icon.vue'
import type { WfGraphEdge, WfGraphNode } from './graph'
import type { WorkflowNodeType, WorkflowNodeTypeMeta } from '@/api/types'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

const props = defineProps<{
  metas: WorkflowNodeTypeMeta[]
}>()

/**
 * 双向绑定画布元素：图形状态由本组件持有并回写父层，
 * 父层只做「定义 ↔ 图形」的转换与落盘，不再维护第二份副本。
 */
const modelNodes = defineModel<WfGraphNode[]>('nodes', { required: true })
const modelEdges = defineModel<WfGraphEdge[]>('edges', { required: true })

/**
 * 与 vue-flow 交互的 any 边界：其 Node/Edge 泛型会在数组推导时触发 TS2589
 * （详见 graph.ts 说明）。转成 any[] 后 v-model 由组件内部类型安全的模型承担。
 */
const nodes = computed<any[]>({
  get: () => modelNodes.value,
  set: (v) => {
    modelNodes.value = v as WfGraphNode[]
  },
})
const edges = computed<any[]>({
  get: () => modelEdges.value,
  set: (v) => {
    modelEdges.value = v as WfGraphEdge[]
  },
})

const emit = defineEmits<{
  (e: 'select', id: string | null): void
  (e: 'add-node', payload: { type: WorkflowNodeType; position?: { x: number; y: number } }): void
  /** 用户确实改了图（拖动落点/连线/删除）时触发，用于父层标记未保存。 */
  (e: 'changed'): void
}>()

const { screenToFlowCoordinate, fitView, zoomIn, zoomOut, addEdges, removeNodes, removeEdges } =
  useVueFlow()

/** 7 种节点类型共用同一渲染器，视觉差异由 type 决定。 */
const nodeTypes = Object.fromEntries(props.metas.map((m) => [m.type, markRaw(WfNode)]))

const CATEGORY_LABEL: Record<string, string> = {
  structure: '流程结构',
  model: '模型调用',
  action: '执行动作',
  logic: '逻辑控制',
  composite: '组合',
}

/** 按 category 分组，保持后端下发顺序。 */
const grouped = computed(() => {
  const order: string[] = []
  const map = new Map<string, WorkflowNodeTypeMeta[]>()
  for (const m of props.metas) {
    if (!map.has(m.category)) {
      map.set(m.category, [])
      order.push(m.category)
    }
    map.get(m.category)!.push(m)
  }
  return order.map((c) => ({
    key: c,
    label: CATEGORY_LABEL[c] ?? c,
    items: map.get(c)!,
  }))
})

/** singleton 类型（START）已在画布时禁用，避免出现第二个入口被后端校验打回。 */
const takenSingletons = computed(
  () => new Set(nodes.value.filter((n) => props.metas.find((m) => m.type === n.type)?.singleton).map((n) => n.type)),
)

const isDisabled = (m: WorkflowNodeTypeMeta) => m.singleton && takenSingletons.value.has(m.type)

// ---------------- 拖拽落位 ----------------

function onDragStart(e: DragEvent, type: WorkflowNodeType) {
  if (!e.dataTransfer) return
  e.dataTransfer.setData('application/lucky-node-type', type)
  e.dataTransfer.effectAllowed = 'move'
}

function onDrop(e: DragEvent) {
  const type = e.dataTransfer?.getData('application/lucky-node-type') as WorkflowNodeType | ''
  if (!type) return
  const meta = props.metas.find((m) => m.type === type)
  if (!meta || isDisabled(meta)) return
  emit('add-node', { type, position: screenToFlowCoordinate({ x: e.clientX, y: e.clientY }) })
}

function onConnect(params: Connection) {
  addEdges([
    {
      ...params,
      type: 'smoothstep',
      markerEnd: MarkerType.ArrowClosed,
      animated: false,
    },
  ])
  emit('changed')
}

/**
 * 只把「用户意图」判为改动：position 变更需 {@code dragging === false}（落点），
 * select / dimensions 这类框架内部变更不算 —— 否则光是点一下节点画布就变「未保存」。
 */
const onNodesChangeEvt = (changes: NodeChange[]) => {
  if (changes.some((c) => c.type === 'remove' || (c.type === 'position' && c.dragging === false))) {
    emit('changed')
  }
}
const onEdgesChangeEvt = (changes: EdgeChange[]) => {
  if (changes.some((c) => c.type === 'remove')) emit('changed')
}

/** 画布内部改动（拖动/删除/新增）由 VueFlow 经 v-model 回写父层，父层即单一数据源。 */
const onNodeClick = (e: NodeMouseEvent) => emit('select', e.node.id)
const onPaneClick = () => emit('select', null)

/**
 * selected 只存在于运行期图形对象（GraphNode/GraphEdge）上，输入侧的 Node/Edge 类型没有它，
 * 这里按可选字段读取，避免把整个模型降级成 any。
 */
const isSelected = (el: unknown) => !!(el as { selected?: boolean }).selected

const hasSelection = computed(() => nodes.value.some(isSelected) || edges.value.some(isSelected))

/** 删除当前选中的节点/边（工具栏按钮，等价于键盘 Delete）。 */
function deleteSelection() {
  const nids = nodes.value.filter(isSelected).map((n) => n.id)
  const eids = edges.value.filter(isSelected).map((e) => e.id)
  if (nids.length) removeNodes(nids)
  if (eids.length) removeEdges(eids)
}
</script>

<template>
  <div class="wfc" @drop.prevent="onDrop" @dragover.prevent>
    <!-- 组件面板 -->
    <aside class="wfc__palette">
      <div class="wfc__palette-head">节点</div>
      <div v-for="g in grouped" :key="g.key" class="wfc__group">
        <div class="wfc__group-label">{{ g.label }}</div>
        <button
          v-for="m in g.items"
          :key="m.type"
          class="wfc__item"
          :class="{ 'wfc__item--off': isDisabled(m) }"
          :disabled="isDisabled(m)"
          :title="isDisabled(m) ? `${m.label} 只能有一个` : m.description"
          draggable="true"
          @dragstart="onDragStart($event, m.type)"
          @click="emit('add-node', { type: m.type })"
        >
          <span class="wfc__item-type mono">{{ m.type }}</span>
          <span class="wfc__item-label">{{ m.label }}</span>
        </button>
      </div>
      <p class="wfc__tip">拖到画布，或点击直接添加</p>
    </aside>

    <!-- 画布主体 -->
    <div class="wfc__stage">
      <VueFlow
        v-model:nodes="nodes"
        v-model:edges="edges"
        :node-types="nodeTypes"
        :default-viewport="{ x: 0, y: 0, zoom: 1 }"
        :min-zoom="0.25"
        :max-zoom="2"
        :snap-to-grid="true"
        :snap-grid="[12, 12]"
        :delete-key-code="['Delete', 'Backspace']"
        :connect-on-click="false"
        fit-view-on-init
        @connect="onConnect"
        @nodes-change="onNodesChangeEvt"
        @edges-change="onEdgesChangeEvt"
        @node-click="onNodeClick"
        @edge-click="onPaneClick"
        @pane-click="onPaneClick"
      >
        <Panel position="top-right" class="wfc__tools">
          <button class="wfc__tool" title="放大" @click="zoomIn()"><Icon name="plus" :size="13" /></button>
          <button class="wfc__tool" title="缩小" @click="zoomOut()"><Icon name="minus" :size="13" /></button>
          <button class="wfc__tool" title="适应画布" @click="fitView()">
            <Icon name="gauge" :size="13" />
          </button>
          <button
            class="wfc__tool wfc__tool--danger"
            title="删除选中（Delete）"
            :disabled="!hasSelection"
            @click="deleteSelection"
          >
            <Icon name="trash" :size="13" />
          </button>
        </Panel>

        <div v-if="!nodes.length" class="wfc__empty">
          <Icon name="gitBranch" :size="26" />
          <p>从左侧拖入节点开始编排</p>
        </div>
      </VueFlow>
    </div>
  </div>
</template>

<style scoped>
.wfc {
  flex: 1;
  min-width: 0;
  display: flex;
  background: var(--bg-0);
}
.wfc__palette {
  width: 158px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  padding: 10px;
  overflow-y: auto;
  background: var(--bg-1);
}
.wfc__palette-head {
  font-size: var(--fs-11);
  color: var(--text-3);
  letter-spacing: 0.1em;
  margin-bottom: 10px;
}
.wfc__group {
  margin-bottom: 12px;
}
.wfc__group-label {
  font-size: var(--fs-10);
  color: var(--text-3);
  margin-bottom: 5px;
}
.wfc__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  width: 100%;
  padding: 6px 8px;
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  background: var(--bg-0);
  cursor: grab;
  text-align: left;
  transition: border-color var(--dur-fast) var(--ease);
}
.wfc__item:hover:not(:disabled) {
  border-color: var(--accent);
}
.wfc__item--off {
  opacity: 0.42;
  cursor: not-allowed;
}
.wfc__item-type {
  font-size: 9px;
  letter-spacing: 0.08em;
  color: var(--text-3);
}
.wfc__item-label {
  font-size: var(--fs-12);
  color: var(--text-1);
}
.wfc__tip {
  font-size: var(--fs-10);
  color: var(--text-3);
  line-height: 1.6;
  margin: 12px 0 0;
}
.wfc__stage {
  flex: 1;
  min-width: 0;
  position: relative;
}
.wfc__stage :deep(.vue-flow) {
  background: var(--bg-0);
}
.wfc__stage :deep(.vue-flow__background) {
  background: var(--bg-1);
}
.wfc__tools {
  display: flex;
  gap: 4px;
  margin: 10px;
}
.wfc__tool {
  width: 26px;
  height: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--border-strong);
  border-radius: var(--r-6);
  background: var(--bg-0);
  color: var(--text-2);
  cursor: pointer;
}
.wfc__tool:hover:not(:disabled) {
  border-color: var(--accent);
  color: var(--accent-text);
}
.wfc__tool:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.wfc__tool--danger:hover:not(:disabled) {
  border-color: var(--danger);
  color: var(--danger-text);
}
.wfc__empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--text-3);
  pointer-events: none;
}
.wfc__empty p {
  margin: 0;
  font-size: var(--fs-13);
}
</style>
