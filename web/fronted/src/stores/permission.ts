import { defineStore } from 'pinia'
import { ref } from 'vue'
import { permissionApi } from '@/api'
import type { PermissionRule } from '@/api/types'

export const usePermissionStore = defineStore('permission', () => {
  const rules = ref<PermissionRule[]>([])

  async function load() {
    rules.value = await permissionApi.rules()
  }

  async function save() {
    return permissionApi.saveRules(rules.value)
  }

  function addRule() {
    rules.value.push({
      id: `rule-${Date.now()}`,
      priority: 100,
      type: 'PATH',
      matcher: { pattern: '/**', anchor: 'project' },
      action: 'ASK',
    })
  }

  function removeRule(index: number) {
    rules.value.splice(index, 1)
  }

  return { rules, load, save, addRule, removeRule }
})
