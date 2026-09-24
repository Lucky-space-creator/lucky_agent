<script setup lang="ts">
/**
 * 工作流画布：拖拽编排 + 连线 + 自动布局 + 运行态渲染。
 *
 * <p>节点类型与字段全部来自后端 `/api/workflows/node-types`，本组件不硬编码任何
 * config schema —— 后端新增节点类型或改键名时，画布自动跟上，不会出现
 * 「表单填了但引擎读不到」的静默失配。</p>
 *
 * <p><b>关于点阵网格底：</b>{@code @vue-flow/core} 1.x <b>不再内置</b>
 * Background / MiniMap / Controls（它们已拆到独立包 `@vue-flow/background`
 * 等，本项目未安装）。这里不去引新依赖，而是自绘一层网格：
 * 用根元素的 {@code transform} 不能直接用（transformationpane 的盒子不随缩放扩张，
 * 放大会露白），所以把 {@code viewport} 的 x/y/zoom 绑成 CSS 变量，
 * 由 `background-position` / `background-size` 复现同一套平移缩放。</p>
 */
import { computed, markRaw, nextTick, provide, watch } from 'vue'
import { MarkerType, Panel, VueFlow, useVueFlow } from '@vue-flow/core'
import type { Connection, EdgeChange, NodeChange, NodeMouseEvent } from '@vue-flow/core'
import WfNode from './WfNode.vue'
import Icon from '@/components/common/Icon.vue'
import { WF_RUN_MARKS } from './graph'
import type { WfGraphEdge, WfGraphNode } from './graph'
import { nodeView } from './format'
import type { RunMark } from './useWorkflowRun'
import type { WorkflowNodeType, WorkflowNodeTypeMeta } from '@/api/types'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'

const props = withDefaults(
  defineProps<{
    metas: WorkflowNodeTypeMeta[]
    /** 运行标记（由 useWorkflowRun 下发），经 provide 传给节点卡片。 */
    marks?: Record<string, RunMark>
    /** 父层持有的选中节点 id（点执行轨迹要能同步高亮画布节点）。 */
    selectedId?: string | null
  }>(),
  { marks: () => ({}), selectedId: null },
)

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
  /** 请求父层按当前边关系重排节点坐标（父层是 nodes 的 owner）。 */
  (e: 'auto-layout'): void
}>()

const {
  screenToFlowCoordinate,
  fitView,
  zoomIn,
  zoomOut,
  addEdges,
  removeNodes,
  removeEdges,
  viewport,
  findNode,
  addSelectedNodes,
  removeSelectedElements,
  getSelectedNodes,
} = useVueFlow()

/**
 * 父层改选中态（点执行轨迹项 / 失败自动定位）时同步到 vue-flow 内部选中集。
 *
 * <p><b>为什么必须显式同步：</b>vue-flow 的选中态是它自己 store 里的 {@code Node.selected}，
 * 由画布上的鼠标事件维护；父层只是「知道选中了谁」，改写它不会影响画布高亮 ——
 * 表现为「点轨迹项，右侧属性面板切了，画布上却没有任何节点高亮」。</p>
 *
 * <p>已一致时不重复设值，避免打断用户在画布上的多选。</p>
 */
watch(
  () => props.selectedId,
  (id) => {
    // Actions 里的 getter 在 store 上被 ComputedGetters 包成了 ComputedRef
    const cur = getSelectedNodes.value.map((n) => n.id)
    if (!id && !cur.length) return
    if (id && cur.length === 1 && cur[0] === id) return
    removeSelectedElements()
    if (!id) return
    const node = findNode(id)
    if (node) addSelectedNodes([node])
  },
)

/** 运行标记按注入下发，避免把运行态写进 node.data 引起整块画布重建。 */
const marksRef = computed(() => props.marks)
provide(WF_RUN_MARKS, marksRef)

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
const disabledHint = (m: WorkflowNodeTypeMeta) => `${m.label} 在一条流程中只能有一个`

// ---------------- 网格底 ----------------

const GRID = 12

/** 与 viewport 同源，保证网格和节点「一起平移、一起缩放」。 */
const gridStyle = computed(() => {
  const v = viewport.value
  return `--wfc-grid:${GRID * v.zoom}px;--wfc-grid-x:${v.x}px;--wfc-grid-y:${v.y}px`
})

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

/**
 * 自动布局：坐标计算在父层（graph.ts 的 autoLayout 纯函数）完成，
 * 这里只负责等 DOM 更新后再 fitView。
 *
 * <p>为什么不是 nextTick 之后立刻 fit：父层改的是 `nodes` 数组，
 * 需要经 v-model 回灌到 vue-flow 的内部 store，再触发一轮布局测量；
 * 只等一个 tick 会拿到旧尺寸，导致「适应画布」只适应了一半。</p>
 */
async function autoArrange() {
  emit('auto-layout')
  await nextTick()
  await new Promise((r) => setTimeout(r, 60))
  await fitView({ padding: 0.2, duration: 260 })
}
</script>

<template>
  <div class="wfc" @drop.prevent="onDrop" @dragover.prevent>
    <!-- 组件面板 -->
    <aside class="wfc__palette">
      <div class="wfc__palette-head">节点库</div>
      <div v-for="g in grouped" :key="g.key" class="wfc__group">
        <div class="wfc__group-label">{{ g.label }}</div>
        <button
          v-for="m in g.items"
          :key="m.type"
          class="wfc__item"
          :class="{ 'wfc__item--off': isDisabled(m) }"
          :disabled="isDisabled(m)"
          :title="isDisabled(m) ? disabledHint(m) : `${m.description}｜${m.type}`"
          draggable="true"
          @dragstart="onDragStart($event, m.type)"
          @click="emit('add-node', { type: m.type })"
        >
          <span class="wfc__item-icon" :class="`wfc__item-icon--${nodeView(m.type).tone}`">
            <Icon :name="nodeView(m.type).icon" :size="13" />
          </span>
          <span class="wfc__item-label">{{ m.label }}</span>
        </button>
      </div>
      <p class="wfc__tip">拖到画布，或点击直接添加</p>
    </aside>

    <!-- 画布主体 -->
    <div class="wfc__stage" :style="gridStyle">
      <!-- 点阵网格底：位于 .vue-flow 之下（pane 自身无背景，节点照常渲染在其上） -->
      <div class="wfc__grid" aria-hidden="true" />

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
        <Panel position="bottom-left" class="wfc__tools">
          <button class="wfc__tool" title="放大" @click="zoomIn()"><Icon name="plus" :size="13" /></button>
          <button class="wfc__tool" title="缩小" @click="zoomOut()"><Icon name="minus" :size="13" /></button>
          <button class="wfc__tool" title="适应画布" @click="fitView({ padding: 0.2 })">
            <Icon name="gauge" :size="13" />
          </button>
          <span class="wfc__tools-sep" />
          <button class="wfc__tool" title="按边关系自动分层重排" @click="autoArrange">
            <Icon name="wand" :size="13" />
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
  width: 148px;
  flex-shrink: 0;
  border-right: 1px solid var(--border);
  padding: 10px 10px 14px;
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
  align-items: center;
  gap: 7px;
  width: 100%;
  padding: 6px 8px;
  margin-bottom: 4px;
  border: 1px solid var(--border);
  border-radius: var(--r-6);
  background: var(--bg-0);
  cursor: grab;
  text-align: left;
  transition: border-color var(--dur-fast) var(--ease), background var(--dur-fast) var(--ease);
}
.wfc__item:hover:not(:disabled) {
  border-color: var(--accent);
  background: var(--accent-dim);
}
.wfc__item--off {
  opacity: 0.42;
  cursor: not-allowed;
}
.wfc__item-icon {
  display: flex;
  flex-shrink: 0;
  color: var(--text-2);
}
.wfc__item-icon--ok {
  color: #2ea043;
}
.wfc__item-icon--accent {
  color: var(--accent);
}
.wfc__item-icon--teal {
  color: var(--teal);
}
.wfc__item-icon--warn {
  color: var(--warn);
}
.wfc__item-icon--violet {
  color: #8b5cf6;
}
.wfc__item-label {
  font-size: var(--fs-12);
  color: var(--text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
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
  background: var(--bg-0);
}
.wfc__grid {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  background-image: radial-gradient(circle, var(--border-strong) 1px, transparent 1px);
  background-size: var(--wfc-grid, 12px) var(--wfc-grid, 12px);
  background-position: var(--wfc-grid-x, 0px) var(--wfc-grid-y, 0px);
}
/* 让 vue-flow 自身透明，网格从底下透出来（.vue-flow 自带 background 会被 theme 覆写） */
.wfc__stage :deep(.vue-flow) {
  background: transparent;
  z-index: 1;
}
.wfc__tools {
  display: flex;
  gap: 4px;
  align-items: center;
  margin: 10px;
}
.wfc__tools-sep {
  width: 1px;
  height: 16px;
  background: var(--border-strong);
  margin: 0 2px;
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
