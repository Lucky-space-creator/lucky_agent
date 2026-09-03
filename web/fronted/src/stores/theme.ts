import { defineStore } from 'pinia'
import { ref, onScopeDispose } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'lucky-theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

function loadMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'light' || saved === 'dark' || saved === 'system' ? saved : 'system'
}

function resolveTheme(mode: ThemeMode): 'light' | 'dark' {
  if (mode === 'system') {
    return window.matchMedia(DARK_QUERY).matches ? 'dark' : 'light'
  }
  return mode
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(loadMode())
  const resolved = ref<'light' | 'dark'>(resolveTheme(mode.value))

  let mediaQuery: MediaQueryList | null = null
  let mediaHandler: ((e: MediaQueryListEvent) => void) | null = null

  function apply() {
    resolved.value = resolveTheme(mode.value)
    document.documentElement.dataset.theme = resolved.value
    try {
      localStorage.setItem(STORAGE_KEY, mode.value)
    } catch {
      // 隐私模式等场景忽略
    }
    attachSystemListener()
  }

  function attachSystemListener() {
    if (mediaQuery) {
      mediaQuery.removeEventListener('change', mediaHandler!)
    }
    mediaHandler = () => {
      if (mode.value === 'system') {
        resolved.value = resolveTheme('system')
        document.documentElement.dataset.theme = resolved.value
      }
    }
    mediaQuery = window.matchMedia(DARK_QUERY)
    mediaQuery.addEventListener('change', mediaHandler)
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    apply()
  }

  apply()

  onScopeDispose(() => {
    if (mediaQuery && mediaHandler) {
      mediaQuery.removeEventListener('change', mediaHandler)
    }
  })

  return {
    mode,
    resolved,
    setMode,
  }
})
