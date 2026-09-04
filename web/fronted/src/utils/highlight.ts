// 代码高亮：按需注册 highlight.js 语言，按扩展名判定语言，返回受信任的高亮 HTML。
// highlight.js 会自行转义输入文本，输出不含用户原始 HTML，可安全 v-html 渲染。

import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import typescript from 'highlight.js/lib/languages/typescript'
import javascript from 'highlight.js/lib/languages/javascript'
import python from 'highlight.js/lib/languages/python'
import xml from 'highlight.js/lib/languages/xml'
import css from 'highlight.js/lib/languages/css'
import scss from 'highlight.js/lib/languages/scss'
import less from 'highlight.js/lib/languages/less'
import json from 'highlight.js/lib/languages/json'
import markdown from 'highlight.js/lib/languages/markdown'
import yaml from 'highlight.js/lib/languages/yaml'
import shell from 'highlight.js/lib/languages/bash'
import powershell from 'highlight.js/lib/languages/powershell'
import sql from 'highlight.js/lib/languages/sql'
import go from 'highlight.js/lib/languages/go'
import rust from 'highlight.js/lib/languages/rust'
import c from 'highlight.js/lib/languages/c'
import cpp from 'highlight.js/lib/languages/cpp'
import csharp from 'highlight.js/lib/languages/csharp'
import kotlin from 'highlight.js/lib/languages/kotlin'
import php from 'highlight.js/lib/languages/php'
import ruby from 'highlight.js/lib/languages/ruby'
import swift from 'highlight.js/lib/languages/swift'
import http from 'highlight.js/lib/languages/http'
import ini from 'highlight.js/lib/languages/ini'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import nginx from 'highlight.js/lib/languages/nginx'
import makefile from 'highlight.js/lib/languages/makefile'
import properties from 'highlight.js/lib/languages/properties'
import groovy from 'highlight.js/lib/languages/groovy'
import vbnet from 'highlight.js/lib/languages/vbnet'
import cmake from 'highlight.js/lib/languages/cmake'

hljs.registerLanguage('java', java)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('python', python)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('css', css)
hljs.registerLanguage('scss', scss)
hljs.registerLanguage('less', less)
hljs.registerLanguage('json', json)
hljs.registerLanguage('markdown', markdown)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('bash', shell)
hljs.registerLanguage('powershell', powershell)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('go', go)
hljs.registerLanguage('rust', rust)
hljs.registerLanguage('c', c)
hljs.registerLanguage('cpp', cpp)
hljs.registerLanguage('csharp', csharp)
hljs.registerLanguage('kotlin', kotlin)
hljs.registerLanguage('php', php)
hljs.registerLanguage('ruby', ruby)
hljs.registerLanguage('swift', swift)
hljs.registerLanguage('http', http)
hljs.registerLanguage('ini', ini)
hljs.registerLanguage('dockerfile', dockerfile)
hljs.registerLanguage('nginx', nginx)
hljs.registerLanguage('makefile', makefile)
hljs.registerLanguage('properties', properties)
hljs.registerLanguage('groovy', groovy)
hljs.registerLanguage('vbnet', vbnet)
hljs.registerLanguage('cmake', cmake)

/** 二进制/无法作为文本读取的扩展名清单。 */
const BINARY_EXT = new Set([
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'ico', 'bmp', 'svg',
  'pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx',
  'zip', 'rar', '7z', 'gz', 'tar', 'jar', 'war', 'class', 'exe', 'dll', 'bin', 'so', 'dylib',
  'mp3', 'mp4', 'wav', 'avi', 'mov', 'woff', 'woff2', 'ttf', 'otf', 'eot',
])

/** 文本文件无需高亮（作为纯文本预览 + markdown 语义说明）。 */
const TEXT_PLAIN_EXT = new Set(['txt', 'log', 'gitignore', 'gitattributes', 'editorconfig', 'npmrc', 'lock'])

interface LangInfo {
  /** highlight.js 语言别名；null 表示非代码文件。 */
  hl: string | null
  /** 展示用语言名/类型。 */
  label: string
  /** 是否为代码文件（决定是否走语法高亮）。 */
  code: boolean
}

function fromFilename(name: string): LangInfo {
  const base = (name || '').toLowerCase()
  // 优先精确文件名匹配
  if (base.endsWith('dockerfile')) return { hl: 'dockerfile', label: 'Dockerfile', code: true }
  if (base === 'makefile') return { hl: 'makefile', label: 'Makefile', code: true }
  if (base === 'cmakelists.txt') return { hl: 'cmake', label: 'CMake', code: true }
  if (base.endsWith('nginx.conf')) return { hl: 'nginx', label: 'nginx', code: true }

  const dot = base.lastIndexOf('.')
  if (dot < 0) {
    // 无扩展名：可能是可执行/脚本；按纯文本处理避免误判
    return { hl: null, label: '文件', code: false }
  }
  const ext = base.slice(dot + 1)
  if (BINARY_EXT.has(ext)) return { hl: null, label: ext.toUpperCase(), code: false }
  if (TEXT_PLAIN_EXT.has(ext)) return { hl: null, label: ext.toUpperCase(), code: false }

  const map: Record<string, LangInfo> = {
    java: { hl: 'java', label: 'Java', code: true },
    kt: { hl: 'kotlin', label: 'Kotlin', code: true },
    kts: { hl: 'kotlin', label: 'Kotlin', code: true },
    groovy: { hl: 'groovy', label: 'Groovy', code: true },
    gradle: { hl: 'groovy', label: 'Groovy', code: true },
    ts: { hl: 'typescript', label: 'TypeScript', code: true },
    tsx: { hl: 'typescript', label: 'TSX', code: true },
    js: { hl: 'javascript', label: 'JavaScript', code: true },
    jsx: { hl: 'javascript', label: 'JSX', code: true },
    mjs: { hl: 'javascript', label: 'JavaScript', code: true },
    cjs: { hl: 'javascript', label: 'JavaScript', code: true },
    py: { hl: 'python', label: 'Python', code: true },
    html: { hl: 'xml', label: 'HTML', code: true },
    htm: { hl: 'xml', label: 'HTML', code: true },
    vue: { hl: 'xml', label: 'Vue', code: true },
    xml: { hl: 'xml', label: 'XML', code: true },
    xhtml: { hl: 'xml', label: 'XHTML', code: true },
    svg: { hl: 'xml', label: 'SVG', code: true },
    css: { hl: 'css', label: 'CSS', code: true },
    scss: { hl: 'scss', label: 'SCSS', code: true },
    sass: { hl: 'scss', label: 'Sass', code: true },
    less: { hl: 'less', label: 'Less', code: true },
    json: { hl: 'json', label: 'JSON', code: true },
    jsonc: { hl: 'json', label: 'JSONC', code: true },
    md: { hl: 'markdown', label: 'Markdown', code: true },
    markdown: { hl: 'markdown', label: 'Markdown', code: true },
    yml: { hl: 'yaml', label: 'YAML', code: true },
    yaml: { hl: 'yaml', label: 'YAML', code: true },
    toml: { hl: 'ini', label: 'TOML', code: true },
    ini: { hl: 'ini', label: 'INI', code: true },
    cfg: { hl: 'ini', label: 'INI', code: true },
    conf: { hl: 'ini', label: 'INI', code: true },
    properties: { hl: 'properties', label: 'Properties', code: true },
    env: { hl: 'ini', label: 'ENV', code: true },
    sh: { hl: 'bash', label: 'Shell', code: true },
    bash: { hl: 'bash', label: 'Bash', code: true },
    zsh: { hl: 'bash', label: 'Zsh', code: true },
    bat: { hl: 'powershell', label: 'BAT', code: true },
    cmd: { hl: 'powershell', label: 'CMD', code: true },
    ps1: { hl: 'powershell', label: 'PowerShell', code: true },
    psd1: { hl: 'powershell', label: 'PowerShell', code: true },
    sql: { hl: 'sql', label: 'SQL', code: true },
    go: { hl: 'go', label: 'Go', code: true },
    rs: { hl: 'rust', label: 'Rust', code: true },
    c: { hl: 'c', label: 'C', code: true },
    h: { hl: 'c', label: 'C/C++ Header', code: true },
    cc: { hl: 'cpp', label: 'C++', code: true },
    cpp: { hl: 'cpp', label: 'C++', code: true },
    hpp: { hl: 'cpp', label: 'C++', code: true },
    cs: { hl: 'csharp', label: 'C#', code: true },
    php: { hl: 'php', label: 'PHP', code: true },
    rb: { hl: 'ruby', label: 'Ruby', code: true },
    swift: { hl: 'swift', label: 'Swift', code: true },
    http: { hl: 'http', label: 'HTTP', code: true },
  }
  // 未知扩展名但可能是代码：允许自动识别
  const hit = map[ext]
  if (hit) return hit
  return { hl: null, label: ext.toUpperCase() || '文件', code: false }
}

/** 根据文件路径/名称识别类型。 */
export function detectLanguage(pathOrName: string): LangInfo {
  const name = pathOrName.includes('/') || pathOrName.includes('\\')
    ? pathOrName.split(/[/\\]/).pop() ?? pathOrName
    : pathOrName
  return fromFilename(name)
}

export interface HighlightResult {
  /** 高亮后的 HTML（已由 hljs 转义，可安全 v-html）。 */
  html: string
  /** 是否识别为代码文件并成功高亮。 */
  isCode: boolean
  /** 语言/类型徽标文案。 */
  label: string
}

/** 供调用方显式指定语言（如聊天围栏代码块 ```java），优先级最高。 */
const FENCE_HINT: Record<string, string> = {
  java: 'java',
  kotlin: 'kotlin',
  kt: 'kotlin',
  groovy: 'groovy',
  gradle: 'groovy',
  ts: 'typescript',
  typescript: 'typescript',
  tsx: 'typescript',
  js: 'javascript',
  javascript: 'javascript',
  jsx: 'javascript',
  mjs: 'javascript',
  cjs: 'javascript',
  py: 'python',
  python: 'python',
  html: 'xml',
  htm: 'xml',
  xml: 'xml',
  vue: 'xml',
  css: 'css',
  scss: 'scss',
  less: 'less',
  json: 'json',
  jsonc: 'json',
  md: 'markdown',
  markdown: 'markdown',
  yaml: 'yaml',
  yml: 'yaml',
  toml: 'ini',
  ini: 'ini',
  properties: 'properties',
  sh: 'bash',
  bash: 'bash',
  shell: 'bash',
  zsh: 'bash',
  bat: 'powershell',
  cmd: 'powershell',
  ps1: 'powershell',
  powershell: 'powershell',
  sql: 'sql',
  go: 'go',
  golang: 'go',
  rs: 'rust',
  rust: 'rust',
  c: 'c',
  cpp: 'cpp',
  'c++': 'cpp',
  csharp: 'csharp',
  'c#': 'csharp',
  cs: 'csharp',
  php: 'php',
  rb: 'ruby',
  ruby: 'ruby',
  swift: 'swift',
  http: 'http',
  dockerfile: 'dockerfile',
  docker: 'dockerfile',
  nginx: 'nginx',
  makefile: 'makefile',
  cmake: 'cmake',
  xml_tag: 'xml',
}

/** 规范化围栏语言标签 → hljs 语言名；无法匹配返回 null。 */
function resolveFenceLang(tag: string | null | undefined): string | null {
  if (!tag) return null
  const t = tag.trim().toLowerCase()
  if (!t) return null
  return FENCE_HINT[t] ?? null
}

/**
 * 对文本做语法高亮。
 *
 * @param code       代码/文本
 * @param pathOrName 文件路径或名称（按扩展名判定语言；带语言标签时忽略）
 * @param fenceTag   围栏代码块语言标签（如 ```java 的 java）；命中时强制该语言
 */
export function highlightText(
  code: string,
  pathOrName: string,
  fenceTag?: string | null,
): HighlightResult {
  if (!code) {
    return { html: '', isCode: false, label: '代码' }
  }
  const fenceLang = resolveFenceLang(fenceTag)
  // 围栏显式指定语言 → 直接按它高亮
  if (fenceLang) {
    try {
      const res = hljs.highlight(code, { language: fenceLang, ignoreIllegals: true })
      const info = detectLanguage(fenceTag ?? 'file')
      return { html: res.value, isCode: true, label: info.label || fenceTag || '代码' }
    } catch {
      /* 落到扩展名/自动识别 */
    }
  }
  const info = detectLanguage(pathOrName || 'file')
  // 已识别语言 → 精确高亮
  if (info.code && info.hl) {
    try {
      const res = hljs.highlight(code, { language: info.hl!, ignoreIllegals: true })
      return { html: res.value, isCode: true, label: info.label }
    } catch {
      /* 落到自动识别 */
    }
  }
  // 兜底：自动识别（仅在注册的语言间判断），失败则按纯文本
  try {
    const res = hljs.highlightAuto(code)
    if (res.relevance > 0) {
      return { html: res.value, isCode: true, label: res.language || info.label }
    }
  } catch {
    /* 忽略，落到纯文本 */
  }
  return { html: '', isCode: false, label: info.label || '文本' }
}

export { hljs }
