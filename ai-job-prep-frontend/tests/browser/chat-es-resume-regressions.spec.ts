import { test, expect, type Page, type Route } from '@playwright/test'

async function mock(page: Page, handler: (route: Route, path: string) => Promise<boolean>) {
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/features') { await route.fulfill({ json: { code: 200, data: { ragEnabled: true } } }); return }
    if (!path.startsWith('/api/')) return route.continue()
    if (await handler(route, path)) return
    const data = path === '/api/auth/me' ? { id: 1, username: 'regression', role: 'admin' }
      : path === '/api/chat/session' ? 10 : []
    await ok(route, data)
  })
  await page.route('https://api.fontshare.com/**', route => route.abort())
}
async function ok(route: Route, data: unknown) {
  await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
}

test('late session history cannot overwrite the newly selected session', async ({ page }) => {
  let release!: () => void
  const delayed = new Promise<void>(resolve => { release = resolve })
  await mock(page, async (route, path) => {
    if (path === '/api/sessions') {
      await ok(route, [{ id: 20, title: '慢会话 A', resumeId: 100 }, { id: 30, title: '快会话 B', resumeId: null }])
    } else if (path === '/api/sessions/20/messages') {
      await delayed
      await ok(route, [{ role: 'assistant', content: 'A 的旧历史' }])
    } else if (path === '/api/sessions/30/messages') {
      await ok(route, [{ role: 'assistant', content: 'B 的历史' }])
    } else return false
    return true
  })
  try {
    await page.goto('/ask')
    await expect(page.locator('textarea')).toBeEnabled()
    await page.getByRole('button', { name: '切换', exact: true }).click()
    await page.getByText('慢会话 A', { exact: true }).click()
    await expect(page.locator('textarea')).toBeDisabled()
    await expect(page.getByText('正在加载会话…', { exact: true })).toBeVisible()
    await page.getByRole('button', { name: '切换', exact: true }).click()
    await page.getByText('快会话 B', { exact: true }).click()
    await expect(page.locator('.msg-bub')).toHaveText('B 的历史')
    const oldResponse = page.waitForResponse('**/api/sessions/20/messages')
    release()
    await oldResponse
    await expect(page.locator('.sess-id')).toContainText('30')
    await expect(page.locator('.msg-bub')).toHaveText('B 的历史')
    await expect(page.locator('textarea')).toBeEnabled()
    expect(await page.evaluate(() => localStorage.getItem('rag_session_id:1'))).toBe('30')
  } finally { release() }
})

test('failed history load blocks sending and offers a working retry', async ({ page }) => {
  let attempts = 0
  await mock(page, async (route, path) => {
    if (path === '/api/sessions') await ok(route, [{ id: 20, title: '失败会话' }])
    else if (path === '/api/sessions/20/messages') {
      if (++attempts === 1) await route.fulfill({ status: 503, body: 'Unavailable' })
      else await ok(route, [{ role: 'assistant', content: '恢复的历史' }])
    } else return false
    return true
  })
  await page.goto('/ask')
  await expect(page.locator('textarea')).toBeEnabled()
  await page.getByRole('button', { name: '切换', exact: true }).click()
  await page.getByText('失败会话', { exact: true }).click()
  await expect(page.getByText('无法加载会话，请重试或新建对话。')).toBeVisible()
  await expect(page.locator('textarea')).toBeDisabled()
  await page.getByRole('button', { name: '重试', exact: true }).click()
  await expect(page.locator('.msg-bub')).toHaveText('恢复的历史')
  await expect(page.locator('textarea')).toBeEnabled()
})

test('changing ES page size requests page one once rather than page fifty', async ({ page }) => {
  const queries: { page: number; size: number }[] = []
  await mock(page, async (route, path) => {
    if (path.endsWith('/mapping')) await ok(route, { content: 'text' })
    else if (path.endsWith('/docs')) {
      const params = new URL(route.request().url()).searchParams
      const query = { page: Number(params.get('page')), size: Number(params.get('size')) }
      queries.push(query)
      await ok(route, { records: [{ id: `page-${query.page}-size-${query.size}`, source: { content: '测试切片' } }], total: 404 })
    } else if (path === '/api/admin/es/indices/knowledge_chunk') {
      await ok(route, { health: 'green', docCount: 404, storageSizeBytes: 1234 })
    } else return false
    return true
  })
  await page.goto('/admin/es/knowledge_chunk')
  await expect(page.getByText('page-1-size-20', { exact: true })).toBeVisible()
  await page.locator('.el-pagination .btn-next').click()
  await expect(page.getByText('page-2-size-20', { exact: true })).toBeVisible()
  const start = queries.length
  await page.locator('.el-pagination .el-select').click()
  await page.getByRole('option', { name: '50/page', exact: true }).or(page.getByRole('option', { name: '50条/页', exact: true })).click()
  await expect(page.getByText('page-1-size-50', { exact: true })).toBeVisible()
  expect(queries.slice(start)).toEqual([{ page: 1, size: 50 }])
})

const resume = (id: string, status: string) => ({ id, fileName: `resume-${id}.md`, status, errorMsg: '模型请求超时', fileSize: 100, retryCount: 1, createdAt: '2026-10-22' })

test('failed and pending-retry resumes expose errors and retry retains the file after failure', async ({ page }, testInfo) => {
  let list = [resume('1', 'FAILED'), resume('2', 'PENDING_RETRY'), resume('3', 'ANALYZING')]
  let listReads = 0
  let attempts = 0
  let submitted = ''
  await mock(page, async (route, path) => {
    if (path === '/api/resume/list') { listReads++; await ok(route, list) }
    else if (path === '/api/resume/2/reanalyze') {
      submitted = route.request().postData() ?? ''
      if (++attempts === 1) await route.fulfill({ status: 503, body: 'Unavailable' })
      else { list = [list[0]!, resume('2', 'READY')]; await ok(route, list[1]) }
    } else return false
    return true
  })
  await page.goto('/resume')
  const row = page.locator('.el-table__row').filter({ hasText: 'resume-2.md' })
  await expect(row).toContainText('模型请求超时')
  await expect(row.getByRole('button', { name: '重新解析' })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('resume-recovery.png'), animations: 'disabled' })
  await row.getByRole('button', { name: '重新解析' }).click()
  const dialog = page.getByRole('dialog', { name: '重新解析简历' })
  await dialog.locator('input[type=file]').setInputFiles({ name: 'resume-2.md', mimeType: 'text/markdown', buffer: Buffer.from('# Synthetic resume') })
  await dialog.getByRole('button', { name: '重新解析', exact: true }).click()
  await expect(dialog.getByRole('button', { name: '重新解析', exact: true })).toBeEnabled()
  await expect(dialog.getByText('resume-2.md', { exact: true })).toBeVisible()
  expect(submitted).toContain('name="file"')
  expect(submitted).toContain('Synthetic resume')
  const readsAfterFailure = listReads
  await expect.poll(() => listReads, { timeout: 6000 }).toBeGreaterThan(readsAfterFailure)
  await dialog.getByRole('button', { name: '重新解析', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(row).toContainText('解析完成')
  await expect(row.getByRole('button', { name: '查看解析' })).toBeEnabled()
})

test('failed resume deletion keeps the record and a successful retry removes it', async ({ page }) => {
  let list = [resume('1', 'FAILED')]
  let attempts = 0
  await mock(page, async (route, path) => {
    if (path === '/api/resume/list') await ok(route, list)
    else if (path === '/api/resume/1' && route.request().method() === 'DELETE') {
      if (++attempts === 1) await route.fulfill({ status: 503, body: 'Unavailable' })
      else { list = []; await ok(route, null) }
    } else return false
    return true
  })
  await page.goto('/resume')
  const row = page.locator('.el-table__row').filter({ hasText: 'resume-1.md' })
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('button', { name: '删除简历', exact: true }).click()
  await expect(row.getByRole('button', { name: '删除', exact: true })).toBeEnabled()
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('button', { name: '删除简历', exact: true }).click()
  await expect(row).toHaveCount(0)
})
