import { h } from 'vue'
import { ElMessage, ElNotification } from 'element-plus'
import { confirmNoteLeave, withConfirmedNoteNavigation } from './note-leave-guard'

let handling = false
let notice: ReturnType<typeof ElNotification> | undefined

export function dismissSessionExpiryNotice() {
  notice?.close()
  notice = undefined
}

/** Let the failed operation release its busy state before checking unsaved edits. */
export function handleSessionExpiry() {
  if (handling || window.location.pathname === '/login') return
  handling = true
  window.setTimeout(() => {
    void recoverSession().finally(() => { handling = false })
  }, 0)
}

async function recoverSession() {
  if (window.location.pathname === '/login') return
  if (!await confirmNoteLeave()) {
    dismissSessionExpiryNotice()
    notice = ElNotification({
      title: '登录已过期',
      type: 'warning',
      duration: 0,
      message: h('div', [
        h('p', '当前编辑已保留。请在新标签页使用原账号登录，再回到此页重试。'),
        h('a', { href: '/login', target: '_blank', rel: 'noopener' }, '在新标签页登录'),
      ]),
    })
    return
  }
  const [{ useAuthStore }, { default: router }] = await Promise.all([
    import('@/stores/auth.store'), import('@/router'),
  ])
  dismissSessionExpiryNotice()
  useAuthStore().clearSession()
  ElMessage.warning('登录已过期，请重新登录。')
  await withConfirmedNoteNavigation(() => router.replace('/login'))
}
