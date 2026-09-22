/**
 * 画布元素的最小类型定义。
 *
 * <p><b>为什么不直接用 vue-flow 的 {@code Node} / {@code Edge}：</b>它们的泛型
 * （{@code Node<Data, CustomEvents extends Record<string, CustomEvent>, Type>}）在
 * {@code strict} 下做数组推导时会触发 TS2589「类型实例化过深」，且报错位置飘忽
 * （换成 map / 展开字面量 / filter 都可能触发）。这里只声明本项目实际读写的字段，
 * 与 vue-flow 的交互统一在 {@code WorkflowCanvas} 内以 any 边界收口。</p>
 *
 * <p>字段含义与后端 {@code NodeDef} / {@code EdgeDef} 对应，转换逻辑见
 * {@code defToGraph} / {@code graphToDef}。</p>
 */
import type { WorkflowDef, WorkflowEdgeDef, WorkflowNodeDef, WorkflowNodeType } from '@/api/types'

/** 节点在 {@code data} 中携带的业务载荷。 */
export interface WfNodeData {
  /** 展示名（对应 NodeDef.name）。 */
  label?: string
  /** 节点参数（对应 NodeDef.config）。 */
  config?: Record<string, any>
  /** 输入/输出连接器（对应 NodeDef.inputs/outputs，通常为空）。 */
  inputs?: any
  outputs?: any
  /** 最近一次运行的节点状态（临时标记，不落盘）。 */
  runStatus?: string
}

export interface WfGraphNode {
  id: string
  /** 节点类型枚举名（START / LLM / TOOL ...）。 */
  type: string
  position: { x: number; y: number }
  data?: WfNodeData
  selected?: boolean
}

export interface WfGraphEdge {
  id: string
  source: string
  target: string
  type?: string
  label?: string
  /** 分支路由表达式与展示标签（对应 EdgeDef.condition / label）。 */
  data?: { condition?: string; label?: string }
  /** vue-flow 运行时字段：连线箭头。 */
  markerEnd?: any
  animated?: boolean
  selected?: boolean
}

/** autoLayout 的输入（只依赖 id 与边，便于脱离画布单测）。 */
export interface LayoutInput {
  nodes: { id: string }[]
  edges: { source: string; target: string }[]
}

/**
 * 无人工坐标时的自动分层：按最长路径（Kahn 拓扑）给每个节点定层，层内按输入顺序纵向排列。
 *
 * <p>为什么需要它：{@code position} 是可选的，既有定义与 API 直接创建的工作流都没有坐标。
 * 没有兜底就会出现「所有节点叠在原点」——画布打开即不可用。</p>
 *
 * <p>鲁棒性要求：自环忽略、断边（端点不存在）忽略、环中节点与不可达节点兜底到第 0 层，
 * 任何输入都必须返回全部节点的坐标（缺一个就会渲染到原点）。</p>
 */
export function autoLayout(def: LayoutInput): Record<string, { x: number; y: number }> {
  const ids = def.nodes.map((n) => n.id)
  const known = new Set(ids)
  const out = new Map<string, string[]>()
  const indeg = new Map<string, number>()
  ids.forEach((id) => {
    out.set(id, [])
    indeg.set(id, 0)
  })
  for (const e of def.edges) {
    if (e.source === e.target) continue
    if (!known.has(e.source) || !known.has(e.target)) continue
    out.get(e.source)!.push(e.target)
    indeg.set(e.target, (indeg.get(e.target) ?? 0) + 1)
  }

  const layer = new Map<string, number>()
  const deg = new Map(indeg)
  const queue: string[] = []
  ids.forEach((id) => {
    if ((deg.get(id) ?? 0) === 0) {
      queue.push(id)
      layer.set(id, 0)
    }
  })
  let head = 0
  while (head < queue.length) {
    const cur = queue[head++]
    for (const nx of out.get(cur) ?? []) {
      layer.set(nx, Math.max(layer.get(nx) ?? 0, (layer.get(cur) ?? 0) + 1))
      const d = (deg.get(nx) ?? 1) - 1
      deg.set(nx, d)
      if (d === 0) queue.push(nx)
    }
  }
  ids.forEach((id) => {
    if (!layer.has(id)) layer.set(id, 0)
  })

  const cols = new Map<number, string[]>()
  ids.forEach((id) => {
    const l = layer.get(id) ?? 0
    if (!cols.has(l)) cols.set(l, [])
    cols.get(l)!.push(id)
  })
  const pos: Record<string, { x: number; y: number }> = {}
  cols.forEach((members, l) => {
    members.forEach((id, i) => {
      pos[id] = { x: 40 + l * 250, y: 40 + i * 116 }
    })
  })
  return pos
}

/**
 * 后端定义 → 画布元素。
 *
 * @param labelOf 由节点类型取展示名（依赖后端元数据，故由调用方注入）
 */
export function defToGraph(
  def: WorkflowDef,
  labelOf: (t: WorkflowNodeType) => string,
): { nodes: WfGraphNode[]; edges: WfGraphEdge[] } {
  const auto = autoLayout(def)
  const nodes: WfGraphNode[] = def.nodes.map((n) => ({
    id: n.id,
    type: n.type,
    position: n.position ? { x: n.position.x, y: n.position.y } : (auto[n.id] ?? { x: 80, y: 240 }),
    data: {
      label: n.name || labelOf(n.type),
      config: { ...(n.config ?? {}) },
      inputs: n.inputs,
      outputs: n.outputs,
    },
  }))
  // 边 id 用「端点 + 序号」合成：同一对节点之间允许存在多条条件出边，
  // 只用 source->target 会撞 id，导致其中一条在画布上被覆盖。
  const edges: WfGraphEdge[] = def.edges.map((e, i) => ({
    id: e.id || `${e.source}->${e.target}#${i}`,
    source: e.source,
    target: e.target,
    type: 'smoothstep',
    markerEnd: 'arrowclosed',
    label: e.label || undefined,
    data: { condition: e.condition ?? '', label: e.label ?? '' },
  }))
  return { nodes, edges }
}

/**
 * 画布元素 → 后端定义。
 *
 * <p>只保留 {@code base} 的身份信息（id/name/description/version/trigger/enabled），
 * 节点与边一律由画布推导 —— 画布编辑期是唯一事实源，避免两份数据互相覆盖。</p>
 *
 * <p>节点的 {@code position} 会一并写出，让布局随定义持久化；
 * 空白的 name/condition/label 转成 undefined，避免落盘一堆空串。</p>
 */
export function graphToDef(base: WorkflowDef, nodes: WfGraphNode[], edges: WfGraphEdge[]): WorkflowDef {
  const nodeDefs: WorkflowNodeDef[] = []
  for (const n of nodes) {
    const d = n.data ?? {}
    const nd: WorkflowNodeDef = {
      id: n.id,
      type: n.type as WorkflowNodeType,
      name: (d.label ?? '').trim() || undefined,
      config: d.config ?? {},
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    }
    if (d.inputs?.length) nd.inputs = d.inputs
    if (d.outputs?.length) nd.outputs = d.outputs
    nodeDefs.push(nd)
  }
  const edgeDefs: WorkflowEdgeDef[] = []
  for (const e of edges) {
    edgeDefs.push({
      source: e.source,
      target: e.target,
      condition: (e.data?.condition ?? '').trim() || undefined,
      label: (e.data?.label ?? '').trim() || undefined,
    })
  }
  return {
    id: base.id,
    name: base.name,
    description: base.description,
    version: base.version ?? 1,
    trigger: base.trigger,
    enabled: base.enabled,
    nodes: nodeDefs,
    edges: edgeDefs,
  }
}
