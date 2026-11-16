import { test, expect, type Page, type Route } from '@playwright/test'

const draft = '必须保留的个人理解：数据库事务不能回滚 ES。'
const messages = [
  { id: 1, sessionId: 10, role: 'user', content: '为什么不能一起回滚？' },
  { id: 2, sessionId: 10, role: 'assistant', content: '事务只覆盖数据库。', metadata: '{"response_status":"completed"}' },
]

async function fixture(page: Page) {
  const state = {
    loggedIn: true, expireSave: false, expireList: false, expireStream: false,
    expireLogout: false, holdResumes: false, pendingResume: undefined as Route | undefined,
    logoutCalls: 0, streamCalls: 0, loginCalls: 0, updates: [] as Record<string, unknown>[],
    creates: [] as Record<string, unknown>[],
    note: { id: 5, version: 0, title: '事务笔记', confusion: '', content: '最初的理解', status: 'PENDING',
      sourceSessionId: 10, sourceMessageIds: '[1,2]', sourceSnapshot: JSON.stringify(messages), updatedAt: '2026-11-13T10:00:00' },
  }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const method = route.request().method()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    const expired = () => route.fulfill({ status: 401, json: { message: '登录已过期' } })
    if (path === '/api/auth/me') return state.loggedIn ? ok({ id: 1, username: 'expiry-user', role: 'user' }) : expired()
    if (path === '/api/auth/login') {
      state.loginCalls++
      return route.fulfill({ status: 401, json: { message: '用户名或密码错误' } })
    }
    if (path === '/api/auth/logout') {
      state.logoutCalls++
      state.loggedIn = false
      return state.expireLogout ? expired() : ok(null)
    }
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/learning-notes/5') {
      if (method === 'PUT') {
        const body = route.request().postDataJSON()
        state.updates.push(body)
        if (state.expireSave) { state.loggedIn = false; return expired() }
        expect(body.expectedVersion).toBe(state.note.version)
        state.note = { ...state.note, ...body, version: state.note.version + 1 }
      }
      return ok(state.note)
    }
    if (path === '/api/learning-notes' && method === 'POST') {
      state.creates.push(route.request().postDataJSON())
      if (state.expireSave) { state.loggedIn = false; return expired() }
      return ok(state.note)
    }
    if (path === '/api/learning-notes') return state.expireList ? expired() : ok({ records: [state.note], total: 1 })
    if (path === '/api/chat/session') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, title: '事务讨论' }])
    if (path === '/api/sessions/10/messages') return ok(messages)
    if (path === '/api/resume/list' && state.holdResumes) { state.pendingResume = route; return }
    if (path === '/api/agent/chat/stream') {
      state.streamCalls++
      if (state.expireStream) { state.loggedIn = false; return expired() }
    }
    return ok([])
  })
  return state
}

async function editNote(page: Page) {
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '编辑笔记', exact: true }).click()
  await page.getByLabel('笔记正文', { exact: true }).fill(draft)
}

test('an expired protected request navigates to login without reloading the document', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/notes/5')
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
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

test('expired save waits for busy to clear, preserves edits, and only retries on an explicit save', async ({ page }) => {
  const state = await fixture(page)
  await editNote(page)
  state.expireSave = true
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '笔记尚未保存' })).toBeVisible()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByLabel('笔记正文', { exact: true })).toHaveValue(draft)
  await expect(page.getByRole('button', { name: '保存修改', exact: true })).toBeEnabled()
  await expect(page).toHaveURL(/\/notes\/5$/)
  const notification = page.locator('.el-notification')
  await expect(notification).toContainText('登录已过期')
  const login = notification.getByRole('link', { name: '在新标签页登录' })
  await expect(login).toHaveAttribute('href', '/login')
  await expect(login).toHaveAttribute('target', '_blank')
  expect(state.updates).toHaveLength(1)
  expect(state.note.content).toBe('最初的理解')
  // Model the original account signing in in another tab; the failed PUT is never replayed automatically.
  state.loggedIn = true
  state.expireSave = false
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
  expect(state.updates).toHaveLength(2)
  expect(state.updates[1]).toEqual(state.updates[0])
  expect(state.note.content).toBe(draft)
  await expect(page.locator('.el-notification')).toHaveCount(0)
})

test('discarding an expired save navigates once without another unsaved-note prompt', async ({ page }) => {
  const state = await fixture(page)
  await editNote(page)
  state.expireSave = true
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  await expect(page.locator('.el-message-box')).toHaveCount(0)
  expect(state.updates).toHaveLength(1)
  expect(state.note.content).toBe('最初的理解')
})

test('two expired requests share one leave prompt and preserve a new note draft', async ({ page }) => {
  const state = await fixture(page)
  state.holdResumes = true
  await page.goto('/ask?sessionId=10')
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).first().click()
  const editor = page.getByRole('dialog', { name: '存为学习笔记', exact: true })
  await editor.getByLabel('标题', { exact: true }).fill('过期时的新笔记')
  await editor.getByLabel('笔记正文', { exact: true }).fill(draft)
  state.expireSave = true
  await editor.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '笔记尚未保存' })).toBeVisible()
  await expect.poll(() => Boolean(state.pendingResume)).toBe(true)
  const resumeResponse = page.waitForResponse(response => response.url().endsWith('/api/resume/list'))
  await state.pendingResume!.fulfill({ status: 401, json: { message: '登录已过期' } })
  await resumeResponse
  await expect(page.locator('.el-message-box')).toHaveCount(1)
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.locator('.el-notification')).toHaveCount(1)
  await expect(page.locator('.el-message-box')).toHaveCount(0)
  await expect(editor.getByLabel('笔记正文', { exact: true })).toHaveValue(draft)
  expect(state.creates).toHaveLength(1)
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

test('an already expired logout still clears the session after one discard confirmation', async ({ page }) => {
  const state = await fixture(page)
  await editNote(page)
  state.expireLogout = true
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '笔记尚未保存' })).toBeVisible()
  expect(state.logoutCalls).toBe(0)
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
  await expect(page.locator('.el-message-box')).toHaveCount(0)
  await expect(page.locator('.el-notification')).toHaveCount(0)
  expect(state.logoutCalls).toBe(1)
})
