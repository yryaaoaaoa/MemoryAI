import { ElMessage } from 'element-plus'

let handling = false
/** Coalesce concurrent expired requests into one login redirect. */
export function handleSessionExpiry() {
  if (handling || window.location.pathname === '/login') return
  handling = true
  window.setTimeout(() => {
    void recoverSession().finally(() => { handling = false })
  }, 0)
}

async function recoverSession() {
  if (window.location.pathname === '/login') return
  const [{ useAuthStore }, { default: router }] = await Promise.all([
    import('@/stores/auth.store'), import('@/router'),
  ])
  useAuthStore().clearSession()
  ElMessage.warning('登录已过期，请重新登录。')
  await router.replace('/login')
}
