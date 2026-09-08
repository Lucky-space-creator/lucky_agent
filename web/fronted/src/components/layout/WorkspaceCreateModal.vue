<script setup lang="ts">
import { ref } from 'vue'
import Icon from '@/components/common/Icon.vue'
import { workspaceApi, systemApi } from '@/api'
import { useWorkspaceStore } from '@/stores/workspace'
import { useToastStore } from '@/stores/toast'

const emit = defineEmits<{ close: [] }>()

const workspace = useWorkspaceStore()
const toast = useToastStore()

const name = ref('')
const path = ref('')
const level = ref<'READ_ONLY' | 'MODIFY' | 'FULL'>('MODIFY')
const creating = ref(false)
const error = ref('')

/** 唤起本机原生目录选择框：路径一律由系统对话框选取，避免手输路径出错。 */
async function pickDirectory() {
  try {
    const r = await systemApi.pickDirectory(path.value || undefined, '选择工作空间目录')
    if (r.cancelled || !r.path) return
    path.value = r.path
    // 名称未填时用目录名回填，减少一次手工输入
    if (!name.value.trim()) {
      name.value = r.path.split(/[\\/]/).filter(Boolean).pop() ?? ''
    }
  } catch (e) {
    error.value = (e as Error).message
  }
}

async function doCreate() {
  if (!name.value || !path.value) {
    toast.error('请填写名称与路径')
    return
  }
  creating.value = true
  try {
    await workspaceApi.create({ name: name.value, path: path.value, permissionLevel: level.value })
    await workspace.load()
    emit('close')
    toast.success('工作空间已创建')
  } catch (e) {
    error.value = (e as Error).message
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div class="overlay" @click.self="emit('close')">
        <div class="mini" role="dialog" aria-label="新建工作空间">
          <header class="head">
            <div class="head__title">
              <span class="head__icon"><Icon name="folderPlus" :size="15" /></span>
              <span>新建工作空间</span>
            </div>
            <button class="head__close" aria-label="关闭" @click="emit('close')"><Icon name="x" :size="15" /></button>
          </header>
          <div class="mini__body">
            <div class="form">
              <label class="field">
                <span class="field__label">名称</span>
                <input v-model="name" class="input" placeholder="如 demo-project" />
              </label>
              <label class="field">
                <span class="field__label">本机路径</span>
                <div class="path-row">
                  <input v-model="path" class="input mono" placeholder="点击「浏览」选择本机目录" />
                  <button class="btn btn--ghost btn--sm path-row__btn" @click="pickDirectory">
                    <Icon name="folder" :size="12" />
                    <span>浏览</span>
                  </button>
                </div>
              </label>
              <label class="field">
                <span class="field__label">权限级别</span>
                <select v-model="level" class="input input--select">
                  <option value="READ_ONLY">只读</option>
                  <option value="MODIFY">修改文件</option>
                  <option value="FULL">全部权限</option>
                </select>
              </label>
              <p class="pane-hint text-2">
                路径从本机目录选择框选取；该路径下会自动生成 skills / mcp / memory / config 目录与项目规则 LUCKY.md。
              </p>
              <p v-if="error" class="form__error">{{ error }}</p>
              <div class="form__actions">
                <button class="btn btn--ghost" @click="emit('close')">取消</button>
                <button class="btn btn--primary" :disabled="creating" @click="doCreate">
                  {{ creating ? '创建中…' : '创建' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 95;
  background: rgba(15, 23, 42, 0.28);
  -webkit-backdrop-filter: blur(4px);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 5vh 16px;
}
.mini {
  width: 460px;
  max-width: 94vw;
  background: var(--bg-0);
  border: 1px solid var(--border-strong);
  border-radius: var(--r-12);
  box-shadow: var(--shadow-2);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 13px 16px;
  border-bottom: 1px solid var(--border);
}
.head__title {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: var(--fs-15);
  font-weight: 600;
  color: var(--text-0);
}
.head__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: var(--r-8);
  background: var(--accent-dim);
  border: 1px solid var(--accent-border);
  color: var(--accent);
}
.head__close {
  display: flex;
  padding: 5px;
  background: none;
  border: none;
  color: var(--text-3);
  border-radius: var(--r-6);
}
.head__close:hover {
  color: var(--text-1);
  background: var(--bg-2);
}
.mini__body {
  padding: 18px 20px;
  max-height: 68vh;
  overflow-y: auto;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 11px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.field__label {
  font-size: var(--fs-12);
  color: var(--text-2);
}
.input {
  background: var(--bg-1);
  border: 1px solid var(--border-strong);
  color: var(--text-0);
  border-radius: var(--r-6);
  padding: 8px 10px;
  font-size: var(--fs-13);
}
.input:focus {
  outline: none;
  border-color: var(--accent-border);
  box-shadow: var(--glow-accent);
}
.input--select {
  -webkit-appearance: none;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--text-3) 50%),
    linear-gradient(135deg, var(--text-3) 50%, transparent 50%);
  background-position: calc(100% - 16px) 50%, calc(100% - 11px) 50%;
  background-size: 5px 5px;
  background-repeat: no-repeat;
  padding-right: 28px;
  cursor: pointer;
}
.path-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.path-row .input {
  flex: 1;
  min-width: 0;
}
.path-row__btn {
  flex-shrink: 0;
  white-space: nowrap;
}
.pane-hint {
  font-size: var(--fs-12);
  color: var(--text-3);
}
.form__error {
  color: var(--danger-text);
  font-size: var(--fs-12);
}
.form__actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}
.btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 13px;
  border-radius: var(--r-6);
  font-size: var(--fs-13);
  font-weight: 500;
  border: 1px solid transparent;
  transition: background var(--dur-fast) var(--ease), border-color var(--dur-fast) var(--ease);
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn--sm {
  padding: 5px 10px;
  font-size: var(--fs-12);
}
.btn--primary {
  background: var(--accent);
  color: #fff;
}
.btn--primary:hover:not(:disabled) {
  background: var(--accent-strong);
}
.btn--ghost {
  background: var(--bg-1);
  border-color: var(--border);
  color: var(--text-2);
}
.btn--ghost:hover:not(:disabled) {
  border-color: var(--border-strong);
  color: var(--text-1);
}
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--dur-med) var(--ease);
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
