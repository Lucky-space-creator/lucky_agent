import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
    },
    {
      path: '/files',
      name: 'files',
      component: () => import('@/views/FilesView.vue'),
    },
    {
      path: '/plugins',
      name: 'plugins',
      component: () => import('@/views/PluginsView.vue'),
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/TasksView.vue'),
    },
    {
      path: '/workflows',
      name: 'workflows',
      component: () => import('@/views/WorkflowView.vue'),
    },
  ],
})

export default router
