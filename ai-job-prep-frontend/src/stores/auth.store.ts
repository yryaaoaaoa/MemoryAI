import { ref, computed } from 'vue'
import { defineStore } from 'pinia'
import { authApi } from '@/api/auth.api'
import type { UserVO } from '@/api/auth.api'
import router from '@/router'

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
    user.value = await authApi.login(username, password)
    router.push('/ask')
  }

  async function register(username: string, password: string) {
    user.value = await authApi.register(username, password)
    // 注册后自动登录 — 注册端点会设置会话 cookie
    // 但注册不设置会话，所以需要调用登录
    await authApi.login(username, password)
    user.value = await authApi.me()
    router.push('/ask')
  }

  async function logout() {
    try { await authApi.logout() } catch { /* 忽略 */ }
    user.value = null
    router.push('/login')
  }

  return { user, checked, userId, isLoggedIn, checkAuth, login, register, logout }
})
