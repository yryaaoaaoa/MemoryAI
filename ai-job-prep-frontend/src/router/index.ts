import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import DefaultLayout from '@/layouts/DefaultLayout.vue'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/Login.vue'),
    meta: { guest: true },
  },
  {
    path: '/',
    component: DefaultLayout,
    redirect: '/ask',
    children: [
      { path: 'ask', name: 'AskAI', component: () => import('@/views/ask/AskAI.vue'), meta: { title: 'AI 问答', icon: 'ChatDotRound' } },
      { path: 'quiz', name: 'Quiz', component: () => import('@/views/quiz/QuizPage.vue'), meta: { title: '刷题', icon: 'EditPen' } },
      { path: 'knowledge', name: 'KnowledgeBase', component: () => import('@/views/knowledge/KnowledgeBase.vue'), meta: { title: '知识库', icon: 'Reading' } },
      { path: 'interview', name: 'Interview', component: () => import('@/views/interview/InterviewPage.vue'), meta: { title: '模拟面试', icon: 'User' } },
      { path: 'interview/report/:sessionId', name: 'InterviewReport', component: () => import('@/views/interview/InterviewReport.vue'), meta: { title: '面试报告', hidden: true } },
      { path: 'resume', name: 'ResumeParser', component: () => import('@/views/resume/ResumeParser.vue'), meta: { title: '简历解析', icon: 'Document' } },
      { path: 'profile', name: 'UserProfile', component: () => import('@/views/ProfilePage.vue'), meta: { title: '用户画像', icon: 'User' } },
      { path: 'admin/knowledge-bases', name: 'AdminKnowledgeBases', component: () => import('@/views/admin/KnowledgeBaseList.vue'), meta: { title: '知识库管理', hidden: true } },
      { path: 'admin/knowledge-bases/:id', name: 'AdminKnowledgeBaseDetail', component: () => import('@/views/admin/KnowledgeBaseDetail.vue'), meta: { title: '知识库详情', hidden: true }, props: true },
      { path: 'admin/es', name: 'EsIndexList', component: () => import('@/views/admin/EsIndexList.vue'), meta: { title: 'ES 管理', hidden: true } },
      { path: 'admin/es/:index', name: 'EsIndexDetail', component: () => import('@/views/admin/EsIndexDetail.vue'), meta: { title: 'ES 详情', hidden: true }, props: true },
      { path: 'admin/settings', name: 'Settings', component: () => import('@/views/admin/SettingsPage.vue'), meta: { title: '检索配置' } },
    ],
  },
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(async (to) => {
  // Dynamic import to avoid circular dependency
  const { useAuthStore } = await import('@/stores/auth.store')
  const auth = useAuthStore()

  // Check auth on first navigation if not checked yet
  if (!auth.checked) {
    await auth.checkAuth()
  }

  // Redirect to login if not authenticated (and not already on login/guest page)
  if (!auth.isLoggedIn && !to.meta.guest) {
    return { name: 'Login' }
  }

  // Redirect to dashboard if already logged in and trying to access login page
  if (auth.isLoggedIn && to.meta.guest) {
    return { name: 'AskAI' }
  }
})

export default router
