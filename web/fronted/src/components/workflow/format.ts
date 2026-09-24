/** 工作流编排 / 运行监控共用的展示格式化函数（纯函数，可脱离组件使用）。 */

/**
 * 毫秒 → 紧凑可读时长。
 *
 * <p>约定：<b>未开始节点传 undefined</b>（画布上显示占位符），
 * 而已跑过但耗时为 0 的节点应显示 `0ms` 而不是占位符 —— 后端
 * {@code NodeInstance.startedAt/endedAt} 默认值就是 0，靠调用方区分
 * 「未开始」（undefined）与「已跑完且极快」（0）。</p>
 */
export function fmtMs(ms?: number): string {
  if (ms === undefined || ms === null || !Number.isFinite(ms) || ms < 0) return '—'
  if (ms < 1000) return `${Math.round(ms)}ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const m = Math.floor(ms / 60_000)
  return `${m}m${Math.round((ms % 60_000) / 1000)}s`
}

/** 变量值 → 展示文本：对象/数组走缩进 JSON，其余直接字符串化。 */
export function fmtVar(v: unknown): string {
  if (v === undefined) return '—'
  if (v === null) return 'null'
  if (typeof v === 'string') return v
  if (typeof v === 'number' || typeof v === 'boolean') return String(v)
  try {
    return JSON.stringify(v, null, 2)
  } catch {
    return String(v)
  }
}

/** 节点类型 → 展示元数据（画布节点卡片与左侧组件面板共用，两处各写一份必然漂移）。 */
export const NODE_VIEW: Record<string, { icon: string; tone: string; label: string }> = {
  START: { icon: 'play', tone: 'ok', label: '开始' },
  END: { icon: 'check', tone: 'ok', label: '结束' },
  LLM: { icon: 'sparkles', tone: 'accent', label: 'LLM 生成' },
  TOOL: { icon: 'terminal', tone: 'teal', label: '工具调用' },
  CONDITION: { icon: 'gitBranch', tone: 'warn', label: '条件判断' },
  CODE: { icon: 'command', tone: 'violet', label: '命令执行' },
  SUBFLOW: { icon: 'layers', tone: 'accent', label: '子流程' },
}

/** 取节点展示元数据；未知类型回落为中性方块（后端新增类型时不至于渲染成空白）。 */
export function nodeView(type: string): { icon: string; tone: string; label: string } {
  return NODE_VIEW[type] ?? { icon: 'box', tone: 'muted', label: type }
}

/** 变量值是否为「多行更适合」的类型（决定表格是否用等宽预格式）。 */
export function isStructured(v: unknown): boolean {
  return v !== null && typeof v === 'object'
}

/** 各节点类型最具信息量的 config 键（节点卡片摘要行取它）。 */
const SUMMARY_KEY: Record<string, string> = {
  LLM: 'prompt',
  TOOL: 'toolName',
  CONDITION: 'condition',
  CODE: 'command',
  SUBFLOW: 'subWorkflowId',
}

/**
 * 节点配置摘要：卡片上扫一眼就知道这个节点在干什么。
 *
 * <p>START / END 无参数，返回空串（调用方跳过该行）——不要用「无参数」之类的
 * 占位文本，那只会让画布更吵。截断交给 CSS（ellipsis），
 * 这样宽度变化时不会出现「莫名多了个省略号」。</p>
 */
export function configSummary(type: string, config?: Record<string, any>): string {
  const key = SUMMARY_KEY[type]
  if (!key || !config) return ''
  const v = config[key]
  if (v === undefined || v === null || v === '') return ''
  const s = typeof v === 'string' ? v : fmtVar(v)
  return s.replace(/\s+/g, ' ').trim()
}
