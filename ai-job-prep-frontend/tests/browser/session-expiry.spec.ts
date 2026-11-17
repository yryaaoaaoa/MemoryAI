import { test, expect, type Page } from '@playwright/test'

async function fixture(page: Page) {
  const state = { loggedIn: true, expireList: false, expireStream: false, expireLogout: false,
    logoutCalls: 0, streamCalls: 0, loginCalls: 0 }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    const expired = () => route.fulfill({ status: 401, json: { message: '登录已过期' } })
    if (path === '/api/auth/me') return state.loggedIn ? ok({ id: 1, username: 'expiry-user', role: 'user' }) : expired()
    if (path === '/api/auth/login') {
      state.loginCalls++
      return route.fulfill({ status: 401, json: { message: '用户名或密码错误' } })
    }
    if (path === '/api/auth/logout') {
      state.logoutCalls++; state.loggedIn = false
      return state.expireLogout ? expired() : ok(null)
    }
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/learning-notes') return state.expireList ? expired() : ok({ records: [], total: 0 })
    if (path === '/api/chat/session') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, title: '事务讨论' }])
    if (path === '/api/sessions/10/messages') return ok([{ id: 1, role: 'user', content: '事务如何工作？' }])
    if (path === '/api/sessions/10/learning-tree') return ok({ note: null, status: 'idle', nodeId: null, messageId: null, error: null })
    if (path === '/api/agent/chat/stream') {
      state.streamCalls++
      if (state.expireStream) { state.loggedIn = false; return expired() }
    }
    return ok([])
  })
  return state
}

test('an expired protected request navigates to login without reloading the document', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/ask?sessionId=10')
  await expect(page.getByRole('textbox', { name: '对话内容' })).toBeVisible()
  await page.evaluate(() => { document.documentElement.dataset.expiryDocument = 'original' })
  state.expireList = true
  await page.getByRole('link', { name: '学习笔记', exact: true }).first().click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  await expect(page.locator('html')).toHaveAttribute('data-expiry-document', 'original')
})

test('an unauthenticated initial visit does not show a session-expired notification', async ({ page }) => {
  const state = await fixture(page)
  state.loggedIn = false
  await page.goto('/notes/5')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  await expect(page.locator('.el-notification')).toHaveCount(0)
  await expect(page.locator('.el-message--error')).toHaveCount(0)
})

test('invalid login credentials show the server error and leave the login form available', async ({ page }) => {
  const state = await fixture(page)
  state.loggedIn = false
  await page.goto('/login')
  await page.getByLabel('用户名', { exact: true }).fill('expiry-user')
  await page.getByLabel('密码', { exact: true }).fill('wrong-password')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.el-message--error')).toContainText('用户名或密码错误')
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.locator('.el-notification')).toHaveCount(0)
  expect(state.loginCalls).toBe(1)
})

test('an expired SSE request navigates to login without starting another generation', async ({ page }) => {
  const state = await fixture(page)
  state.expireStream = true
  await page.goto('/ask?sessionId=10')
  await page.getByRole('textbox', { name: '对话内容' }).fill('继续解释事务边界')
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  expect(state.streamCalls).toBe(1)
})

test('expired logout clears the readonly learning page without an obsolete edit confirmation', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/notes')
  state.expireLogout = true
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  await expect(page.locator('.el-message-box')).toHaveCount(0)
  expect(state.logoutCalls).toBe(1)
})
