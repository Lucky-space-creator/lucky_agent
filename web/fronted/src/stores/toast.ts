import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Toast {
  id: number
  type: 'success' | 'error' | 'info'
  message: string
}

let seq = 0

/**
 * 全局轻提示：屏幕中上方展示，约 1s 后自动消失。
 * 用于「操作成功/失败」这类瞬时反馈，替代原来的内联 spane__msg / 底部提示。
 */
export const useToastStore = defineStore('toast', () => {
  const toasts = ref<Toast[]>([])

  function push(type: Toast['type'], message: string) {
    const id = ++seq
    toasts.value.push({ id, type, message })
    setTimeout(() => dismiss(id), type === 'error' ? 2600 : 1200)
  }

  function dismiss(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  function success(message: string) {
    push('success', message)
  }

  function error(message: string) {
    push('error', message)
  }

  function info(message: string) {
    push('info', message)
  }

  return { toasts, success, error, info, dismiss }
})
