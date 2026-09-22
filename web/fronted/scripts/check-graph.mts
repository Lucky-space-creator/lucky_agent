/**
 * 画布转换逻辑的无浏览器验证：defToGraph / graphToDef 是纯函数，
 * 可以完全脱离 vue-flow 确定性地验证「保存会不会丢数据」。
 * 运行：npm run check:graph（= node --experimental-strip-types scripts/check-graph.mts）
 */
import { autoLayout, defToGraph, graphToDef } from '../src/components/workflow/graph.ts'

let pass = 0
let fail = 0
function check(name: string, cond: boolean, extra?: unknown) {
  if (cond) {
    pass++
    console.log('  ✓ ' + name)
  } else {
    fail++
    console.log('  ✗ ' + name, extra === undefined ? '' : JSON.stringify(extra))
  }
}

const labelOf = (t: string) => ({ START: '开始', END: '结束', LLM: 'LLM 生成', TOOL: '工具调用', CONDITION: '条件判断' }[t] ?? t)

// ---------------------------------------------------------------- autoLayout

console.log('布局 1：线性链 分层递增')
{
  const pos = autoLayout({
    nodes: [{ id: 'start' }, { id: 'a' }, { id: 'end' }],
    edges: [
      { source: 'start', target: 'a' },
      { source: 'a', target: 'end' },
    ],
  })
  check('start.x=40 / a.x=290 / end.x=540', pos.start.x === 40 && pos.a.x === 290 && pos.end.x === 540, pos)
}

console.log('布局 2：菱形分支 同层纵排不重叠')
{
  const pos = autoLayout({
    nodes: [{ id: 'start' }, { id: 'llm' }, { id: 'tool' }, { id: 'end' }],
    edges: [
      { source: 'start', target: 'llm' },
      { source: 'start', target: 'tool' },
      { source: 'llm', target: 'end' },
      { source: 'tool', target: 'end' },
    ],
  })
  check('llm/tool 同层（x 相同、y 不同）', pos.llm.x === pos.tool.x && pos.llm.y !== pos.tool.y, pos)
  check('end 在下一层', pos.end.x > pos.llm.x, pos)
}

console.log('布局 3：取最长路径 不压在已占层')
{
  const pos = autoLayout({
    nodes: [{ id: 'start' }, { id: 'a' }, { id: 'c' }],
    edges: [
      { source: 'start', target: 'c' },
      { source: 'start', target: 'a' },
      { source: 'a', target: 'c' },
    ],
  })
  check('c 到第 2 层', pos.c.x === 540, pos)
}

console.log('布局 4：环 / 自环 / 孤立节点 / 断边 全覆盖且不挂死')
{
  const pos = autoLayout({
    nodes: [{ id: 'start' }, { id: 'a' }, { id: 'b' }, { id: 'loop' }, { id: 'orphan' }],
    edges: [
      { source: 'start', target: 'a' },
      { source: 'a', target: 'b' },
      { source: 'b', target: 'start' },
      { source: 'loop', target: 'loop' },
      { source: 'start', target: 'ghost' },
    ],
  })
  const ids = ['start', 'a', 'b', 'loop', 'orphan']
  check('每个节点都有有限坐标', ids.every((i) => pos[i] && Number.isFinite(pos[i].x) && Number.isFinite(pos[i].y)), pos)
}

console.log('布局 5：空图 / 单节点 不抛异常')
{
  check('空图返回空对象', Object.keys(autoLayout({ nodes: [], edges: [] })).length === 0)
}

// ---------------------------------------------------------------- 往返转换

const baseDef = {
  id: 'wf-rt',
  name: '往返验证',
  description: 'desc',
  version: 3,
  enabled: true,
  trigger: { type: 'MANUAL', enabled: true },
  nodes: [
    { id: 'start', type: 'START', name: '开始', config: {}, position: { x: 40, y: 40 } },
    {
      id: 'gen',
      type: 'LLM',
      name: '生成摘要',
      config: { prompt: '主题：${topic}', system: '你是编辑' },
      inputs: [{ source: 'topic', target: 'topic' }],
      position: { x: 290, y: 156 },
    },
    { id: 'branch', type: 'CONDITION', name: '', config: { condition: 'score >= 80' }, position: null },
    { id: 'end', type: 'END', name: '结束', config: {}, position: { x: 540, y: 40 } },
  ],
  edges: [
    { source: 'start', target: 'gen', condition: null, label: null },
    { source: 'gen', target: 'branch' },
    // 同一对节点之间的两条条件出边：合成 id 必须区分，否则画布上会丢一条
    { source: 'branch', target: 'end', condition: 'score >= 80', label: '达标' },
    { source: 'branch', target: 'end', condition: 'score < 80', label: '不达标' },
  ],
}

console.log('转换 1：defToGraph 保持节点与边数量')
const g = defToGraph(baseDef, labelOf)
check('节点数一致', g.nodes.length === baseDef.nodes.length, g.nodes.length)
check('边数一致', g.edges.length === baseDef.edges.length, g.edges.length)
check(
  '无坐标节点被自动摆位（非 0,0）',
  !!g.nodes.find((n) => n.id === 'branch') &&
    !(g.nodes.find((n) => n.id === 'branch')!.position.x === 0 &&
      g.nodes.find((n) => n.id === 'branch')!.position.y === 0),
  g.nodes.find((n) => n.id === 'branch'),
)
check(
  '有人工坐标的节点保持原值',
  JSON.stringify(g.nodes.find((n) => n.id === 'gen')!.position) === JSON.stringify({ x: 290, y: 156 }),
)
check('空 name 回落到类型展示名', g.nodes.find((n) => n.id === 'branch')!.data!.label === '条件判断')
check('条件边的 id 互不相同', new Set(g.edges.map((e) => e.id)).size === g.edges.length, g.edges.map((e) => e.id))

console.log('转换 2：graphToDef 不丢语义')
const back = graphToDef(baseDef, g.nodes, g.edges)
check('身份字段透传', back.id === baseDef.id && back.name === baseDef.name && back.version === 3 && back.enabled === true)
check('trigger 原样保留', JSON.stringify(back.trigger) === JSON.stringify(baseDef.trigger))
check('节点数一致', back.nodes.length === baseDef.nodes.length)
check(
  'config 原样保留（LLM 的 prompt/system）',
  JSON.stringify(back.nodes.find((n) => n.id === 'gen')!.config) === JSON.stringify(baseDef.nodes[1].config),
  back.nodes.find((n) => n.id === 'gen')!.config,
)
check(
  'inputs（连接器）保留',
  JSON.stringify(back.nodes.find((n) => n.id === 'gen')!.inputs) === JSON.stringify([{ source: 'topic', target: 'topic' }]),
)
check(
  '边条件与标签保留',
  JSON.stringify(back.edges.map((e) => [e.condition ?? null, e.label ?? null])) ===
    JSON.stringify([
      [null, null],
      [null, null],
      ['score >= 80', '达标'],
      ['score < 80', '不达标'],
    ]),
  back.edges,
)
check('position 落为整数', back.nodes.every((n) => Number.isInteger(n.position!.x) && Number.isInteger(n.position!.y)))
check(
  'TOOL 的嵌套 params 对象不被展平',
  (() => {
    const d2 = { ...baseDef, nodes: [{ id: 't', type: 'TOOL', name: 'x', config: { toolName: 'file.list', params: { path: 'config' } }, position: { x: 1, y: 2 } }] , edges: [] }
    const gg = defToGraph(d2 as any, labelOf)
    const bb = graphToDef(d2 as any, gg.nodes, gg.edges)
    return JSON.stringify(bb.nodes[0].config) === JSON.stringify({ toolName: 'file.list', params: { path: 'config' } })
  })(),
)

console.log('转换 3：二次往返幂等（编辑多次保存不漂移）')
{
  const g2 = defToGraph(back as any, labelOf)
  const back2 = graphToDef(back as any, g2.nodes, g2.edges)
  check('第二次往返与第一次一致', JSON.stringify(back2) === JSON.stringify(back))
}

console.log(`\n结果：${pass} 通过 / ${fail} 失败`)
process.exit(fail === 0 ? 0 : 1)
