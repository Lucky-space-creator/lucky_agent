import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 全局 UI 状态：右侧工具栏的展开/收起（由顶部栏按钮控制，ChatView 渲染），
 * 以及「打开文件面板并定位某文件」的跨组件请求（供消息内的文件链接触发）。
 */
export const useUiStore = defineStore('ui', () => {
  /** 右侧工具栏是否展开。 */
  const rightRailOpen = ref(true)

  /** 请求打开「项目文件」面板的信号（自增序号，ChatView 消费后归零）。 */
  const filesPanelRequest = ref(0)
  /** 待定位并展示的文件相对路径。 */
  const pendingFilePath = ref('')

  function toggleRightRail() {
    rightRailOpen.value = !rightRailOpen.value
  }

  /** 确保右侧工具栏展开（不翻转当前状态）。 */
  function openRightRail() {
    rightRailOpen.value = true
  }

  /** 请求：打开项目文件面板并展示/定位指定文件。 */
  function openFilePanel(filePath: string) {
    pendingFilePath.value = filePath ?? ''
    filesPanelRequest.value += 1
  }

  /** ChatView 消费完一次打开文件请求后调用，重置信号。 */
  function consumeFilesRequest() {
    filesPanelRequest.value = 0
    pendingFilePath.value = ''
  }

  return {
    rightRailOpen,
    toggleRightRail,
    openRightRail,
    filesPanelRequest,
    pendingFilePath,
    openFilePanel,
    consumeFilesRequest,
  }
})
