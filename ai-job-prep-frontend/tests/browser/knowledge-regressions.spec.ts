import { test, expect, type Page, type Route } from '@playwright/test'

const kb = (id: number) => ({ id, name: `知识库${id}`, description: `说明${id}`, documentCount: 11,
  chunkCount: 11, createdAt: '2026-10-22T10:00:00', updatedAt: '2026-10-22T10:00:00' })
const doc = (kbId: number, index: number) => ({ id: kbId * 100 + index, kbId,
  fileName: `库${kbId}-文档${index}.md`, fileType: 'md', fileSize: 100, chunkCount: 1,
  status: 'READY', createdAt: '2026-10-22T10:00:00' })
const ok = (route: Route, data: unknown) => route.fulfill({ json: { code: 200, data } })

async function fixture(page: Page, override?: (route: Route, url: URL) => Promise<boolean>) {
  const calls: { path: string; method: string; page: number; size: number }[] = []
  let documents = Array.from({ length: 11 }, (_, index) => doc(1, index + 1))
  await page.route('https://api.fontshare.com/**', route => route.abort())
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/features') { await route.fulfill({ json: { code: 200, data: { ragEnabled: true } } }); return }
    if (!url.pathname.startsWith('/api/')) { await route.continue(); return }
    calls.push({ path: url.pathname, method: route.request().method(),
      page: Number(url.searchParams.get('page') || 1), size: Number(url.searchParams.get('size') || 10) })
    if (await override?.(route, url)) return
    const path = url.pathname
    if (path === '/api/auth/me' || path === '/api/auth/login') {
      await ok(route, { id: 1, username: 'browser-fixture', role: 'admin' }); return
    }
    if (path === '/api/auth/logout') { await ok(route, null); return }
    if (path === '/api/chat/session') { await ok(route, 1); return }
    if (path === '/api/knowledge-bases') {
      const records = Array.from({ length: 21 }, (_, index) => kb(index + 1))
      const pageNumber = Number(url.searchParams.get('page') || 1)
      const size = Number(url.searchParams.get('size') || 10)
      await ok(route, { records: records.slice((pageNumber - 1) * size, pageNumber * size), total: records.length }); return
    }
    const kbMatch = path.match(/^\/api\/knowledge-bases\/(\d+)$/)
    if (kbMatch) { await ok(route, kb(Number(kbMatch[1]))); return }
    const documentsMatch = path.match(/^\/api\/knowledge-bases\/(\d+)\/documents$/)
    if (documentsMatch) {
      const kbId = Number(documentsMatch[1])
      if (route.request().method() === 'POST') { await ok(route, doc(kbId, 99)); return }
      const records = kbId === 1 ? documents : [doc(kbId, 1)]
      const pageNumber = Number(url.searchParams.get('page') || 1)
      const size = Number(url.searchParams.get('size') || 10)
      await ok(route, { records: records.slice((pageNumber - 1) * size, pageNumber * size), total: records.length }); return
    }
    const deleteMatch = path.match(/^\/api\/documents\/(\d+)$/)
    if (deleteMatch && route.request().method() === 'DELETE') {
      documents = documents.filter(item => item.id !== Number(deleteMatch[1]))
      await ok(route, null); return
    }
    await ok(route, [])
  })
  return calls
}

async function enterKbFromList(page: Page, id: number) {
  const row = page.locator('.el-table__row').filter({ has: page.getByText(`知识库${id}`, { exact: true }) })
  await row.getByRole('button', { name: '管理', exact: true }).click()
}

test('new KB clears old documents while loading and leaves no stale actions after a failed request', async ({ page }) => {
  let pending: Route | undefined
  const calls = await fixture(page, async (route, url) => {
    if (url.pathname === '/api/knowledge-bases/2/documents') { pending = route; return true }
    return false
  })
  await page.goto('/admin/knowledge-bases/1')
  await expect(page.getByText('库1-文档1.md', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await enterKbFromList(page, 2)
  await expect(page.getByRole('heading', { name: '知识库2', exact: true })).toBeVisible()
  await expect.poll(() => Boolean(pending)).toBe(true)
  await expect(page.getByText('库1-文档1.md', { exact: true })).toHaveCount(0)
  await expect(page.locator('.el-table .el-loading-mask')).toBeVisible()
  await pending!.fulfill({ status: 503, json: { message: 'fixture documents unavailable' } })
  await expect(page.getByRole('alert').filter({ hasText: '文档加载失败' })).toBeVisible()
  await expect(page.locator('.el-table').getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
  expect(calls.some(call => call.method === 'DELETE')).toBe(false)
})

test('late responses for the previous KB cannot overwrite the current title or rows', async ({ page }) => {
  let oldKb: Route | undefined
  let oldDocs: Route | undefined
  await fixture(page, async (route, url) => {
    if (url.pathname === '/api/knowledge-bases/1') { oldKb = route; return true }
    if (url.pathname === '/api/knowledge-bases/1/documents') { oldDocs = route; return true }
    return false
  })
  await page.goto('/admin/knowledge-bases/1')
  await expect.poll(() => Boolean(oldKb && oldDocs)).toBe(true)
  await page.getByRole('button', { name: '返回', exact: true }).click()
  await enterKbFromList(page, 2)
  await expect(page.getByText('库2-文档1.md', { exact: true })).toBeVisible()
  await ok(oldKb!, kb(1))
  await ok(oldDocs!, { records: [doc(1, 1)], total: 1 })
  await expect(page.getByRole('heading', { name: '知识库2', exact: true })).toBeVisible()
  await expect(page.getByText('库2-文档1.md', { exact: true })).toBeVisible()
  await expect(page.getByText('库1-文档1.md', { exact: true })).toHaveCount(0)
})

test('knowledge management loads later pages and resets to the first page on size change', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/admin/knowledge-bases')
  await expect(page.locator('.el-table__row')).toHaveCount(10)
  await page.locator('.el-pagination .btn-next').click()
  await expect(page.getByText('知识库11', { exact: true })).toBeVisible()
  await page.locator('.el-pagination .el-select').click()
  await page.getByRole('option', { name: '20/page', exact: true }).or(page.getByRole('option', { name: '20条/页', exact: true })).click()
  await expect(page.getByText('知识库1', { exact: true })).toBeVisible()
  await expect(page.locator('.el-table__row')).toHaveCount(20)
  expect(calls.some(call => call.path === '/api/knowledge-bases' && call.page === 2 && call.size === 10)).toBe(true)
  expect(calls.some(call => call.path === '/api/knowledge-bases' && call.page === 1 && call.size === 20)).toBe(true)
  expect(calls.some(call => call.path === '/api/knowledge-bases' && call.page === 20)).toBe(false)
})

test('knowledge cards expose libraries beyond the first twenty records', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/knowledge')
  await expect(page.locator('.kb-card')).toHaveCount(20)
  await page.locator('.el-pagination .btn-next').click()
  await expect(page.getByRole('heading', { name: '知识库21', exact: true })).toBeVisible()
  await expect(page.locator('.kb-card')).toHaveCount(1)
  expect(calls.some(call => call.path === '/api/knowledge-bases' && call.page === 2 && call.size === 20)).toBe(true)
})

test('documents paginate independently and deleting the final row returns to a valid page', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/admin/knowledge-bases/1')
  await expect(page.locator('.el-table__row')).toHaveCount(10)
  await expect(page.locator('.el-pagination')).toContainText('11')
  await page.locator('.el-pagination .btn-next').click()
  await expect(page.getByText('库1-文档11.md', { exact: true })).toBeVisible()
  await page.locator('.el-table__row').getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.getByText('库1-文档1.md', { exact: true })).toBeVisible()
  await expect(page.locator('.el-table__row')).toHaveCount(10)
  expect(calls.some(call => call.path === '/api/knowledge-bases/1/documents' && call.page === 2)).toBe(true)
  expect(calls.filter(call => call.method === 'DELETE').map(call => call.path)).toEqual(['/api/documents/111'])
})

test('removed, invalid, and cancelled upload selections cannot be submitted', async ({ page }) => {
  const calls = await fixture(page)
  await page.goto('/admin/knowledge-bases/1')
  await page.getByRole('button', { name: '上传文档', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '上传文档', exact: true })
  const file = { name: 'private.md', mimeType: 'text/markdown', buffer: Buffer.from('private fixture') }
  await dialog.locator('input[type="file"]').setInputFiles(file)
  await expect(dialog.getByRole('button', { name: '开始上传', exact: true })).toBeEnabled()
  await dialog.locator('.el-upload-list__item').hover()
  await dialog.locator('.el-upload-list__item .el-icon--close').click()
  await expect(dialog.locator('.el-upload-list__item')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '开始上传', exact: true })).toBeDisabled()
  await dialog.locator('input[type="file"]').setInputFiles({ ...file, name: 'invalid.txt' })
  await expect(dialog.locator('.el-upload-list__item')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '开始上传', exact: true })).toBeDisabled()
  await dialog.locator('input[type="file"]').setInputFiles(file)
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '上传文档', exact: true }).click()
  await expect(dialog.locator('.el-upload-list__item')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '开始上传', exact: true })).toBeDisabled()
  expect(calls.some(call => call.method === 'POST' && call.path.endsWith('/documents'))).toBe(false)
})

test('logout invalidates pending knowledge requests before another account signs in', async ({ page }) => {
  let oldRequest: Route | undefined
  let denyPreviousKb = false
  await fixture(page, async (route, url) => {
    if (denyPreviousKb && url.pathname === '/api/auth/login') {
      await ok(route, { id: 2, username: 'second-fixture', role: 'user' }); return true
    }
    if (url.pathname === '/api/knowledge-bases/1/documents' && !denyPreviousKb) { oldRequest = route; return true }
    if (denyPreviousKb && url.pathname.startsWith('/api/knowledge-bases/1')) {
      await route.fulfill({ status: 403, json: { message: 'Forbidden fixture' } }); return true
    }
    return false
  })
  await page.goto('/admin/knowledge-bases/1')
  await expect.poll(() => Boolean(oldRequest)).toBe(true)
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  await ok(oldRequest!, { records: [doc(1, 1)], total: 1 })
  denyPreviousKb = true
  await page.getByPlaceholder('输入用户名', { exact: true }).fill('second-fixture')
  await page.getByPlaceholder('输入密码', { exact: true }).fill('fixture-only')
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/\/ask(?:\?|$)/)
  await page.locator('a[href="/admin/knowledge-bases"]').click()
  await enterKbFromList(page, 1)
  await expect(page.getByRole('heading', { name: '知识库不可用', exact: true })).toBeVisible()
  await expect(page.getByText('库1-文档1.md', { exact: true })).toHaveCount(0)
})
