import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { isAxiosError } from 'axios'
import { authApi } from '@/api/auth.api'
import type { UserVO } from '@/api/auth.api'
import router from '@/router'
import { useKnowledgeBaseStore } from './knowledge-base.store'
import { confirmNoteLeave, withConfirmedNoteNavigation } from '@/utils/note-leave-guard'

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserVO | null>(null)
  const checked = ref(false)

  const userId = computed(() => user.value?.id ?? null)
  const isLoggedIn = computed(() => user.value !== null)

  async function checkAuth() {
    try {
      user.value = await authApi.me()
    } catch {
      user.value = null
    } finally {
      checked.value = true
    }
  }

  async function login(username: string, password: string) {
    const loggedIn = await authApi.login(username, password)
    useKnowledgeBaseStore().reset()
    user.value = loggedIn
    router.push('/ask')
  }

  async function register(username: string, password: string) {
    await authApi.register(username, password)
    // Registering an account does not authenticate it.
    await authApi.login(username, password)
    user.value = await authApi.me()
    useKnowledgeBaseStore().reset()
    router.push('/ask')
  }

  function clearSession() {
    useKnowledgeBaseStore().reset()
    user.value = null
    checked.value = true
  }

  async function logout() {
    if (!(await confirmNoteLeave())) return
    try { await authApi.logout() } catch (error) {
      // An expired session is already logged out; other failures must keep the page intact.
      if (!isAxiosError(error) || error.response?.status !== 401) return
    }
    clearSession()
    await withConfirmedNoteNavigation(() => router.push('/login'))
  }

  return { user, checked, userId, isLoggedIn, checkAuth, login, register, logout, clearSession }
})
