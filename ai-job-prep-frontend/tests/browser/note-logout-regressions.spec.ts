import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page) {
  let logoutCalls = 0
  let failLogout = false
  let loggedIn = true
  let note = { id: 5, version: 0, title: '事务笔记', confusion: '', content: '数据库事务只覆盖数据库。', status: 'PENDING',
    sourceSessionId: 10, sourceMessageIds: '[1,2]', sourceSnapshot: '[]', updatedAt: '2026-11-13T10:00:00' }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return loggedIn ? ok({ id: 1, username: 'note-logout', role: 'user' }) : route.fulfill({ status: 401 })
    if (path === '/api/auth/logout') {
      logoutCalls++
      if (failLogout) return route.fulfill({ status: 503, json: { message: '退出暂不可用' } })
      loggedIn = false
      return ok(null)
    }
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/learning-notes/5') {
      if (route.request().method() === 'PUT') {
        expect(loggedIn).toBe(true)
        const body = route.request().postDataJSON()
        expect(body.expectedVersion).toBe(note.version)
        expect(body).not.toHaveProperty('sourceRanges')
        note = { ...note, ...body, version: note.version + 1 }
      }
      return ok(note)
    }
    if (path === '/api/learning-notes') return ok({ records: [note], total: 1 })
    return ok([])
  })
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '编辑笔记', exact: true }).click()
  await page.getByLabel('笔记正文', { exact: true }).fill('尚未保存的个人理解，不能丢失。')
  return { count: () => logoutCalls, fail: () => { failLogout = true } }
}

test('cancel logout before server invalidation keeps note draft and allows save', async ({ page }) => {
  const state = await setup(page)
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  expect(state.count()).toBe(0)
  await expect(page).toHaveURL(/\/notes\/5$/)
  await expect(page.getByLabel('笔记正文', { exact: true })).toHaveValue('尚未保存的个人理解，不能丢失。')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByText('尚未保存的个人理解，不能丢失。', { exact: true })).toBeVisible()
})

test('confirmed logout sends one request and navigates without a second discard dialog', async ({ page }) => {
  const state = await setup(page)
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(state.count()).toBe(1)
  await expect(page.locator('.el-message-box')).toHaveCount(0)
})

test('failed logout leaves authentication and draft intact and does not bypass later navigation guard', async ({ page }) => {
  const state = await setup(page)
  state.fail()
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page.locator('.el-message--error')).toBeVisible()
  await expect(page.getByLabel('笔记正文', { exact: true })).toHaveValue('尚未保存的个人理解，不能丢失。')
  expect(state.count()).toBe(1)
  await page.getByRole('link', { name: '学习笔记', exact: true }).first().click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/5$/)
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByText('尚未保存的个人理解，不能丢失。', { exact: true })).toBeVisible()
})
