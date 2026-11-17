import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, ragEnabled = false) {
  const note = { id: 5, version: 0, title: '数据库事务与 ES', confusion: '', content: '事务只覆盖数据库。', status: 'PENDING',
    treeJson: null, sourceSessionId: 10, sourceSnapshot: '[]', updatedAt: '2026-11-13T10:00:00' }
  const calls: { path: string; method: string; body: any }[] = []
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    calls.push({ path, method: route.request().method(), body: route.request().postDataJSON() })
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'notes-test', role: 'admin' })
    if (path === '/api/features') return ok({ ragEnabled })
    if (path === '/api/chat/session') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, title: '事务问题' }])
    if (path === '/api/sessions/10/messages') return ok([])
    if (path === '/api/sessions/10/learning-tree') return ok({ note: null, status: 'idle', nodeId: null, messageId: null, error: null })
    if (path === '/api/learning-notes') {
      const empty = url.searchParams.get('query') === '不存在'
      return ok({ records: empty ? [] : [note], total: empty ? 0 : 1 })
    }
    if (path === '/api/knowledge-bases') return ok({ records: [], total: 0 })
    if (path === '/api/quiz/generate') return ok([{ id: 1, question: '测试题', options: { A: '甲', B: '乙', C: '丙', D: '丁' }, answer: 'A', explanation: '解析' }])
    return ok([])
  })
  return { calls }
}

test('RAG off hides management and knowledge selection; normal topic quiz remains available', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/quiz')
  await expect(page.getByRole('link', { name: '学习笔记', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '知识库', exact: true })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'ES 管理', exact: true })).toHaveCount(0)
  await expect(page.locator('.kb-grid')).toHaveCount(0)
  await page.locator('#quiz-topic').fill('Java 事务')
  await page.getByRole('button', { name: /开始刷题/ }).click()
  await expect(page.getByText('测试题', { exact: true })).toBeVisible()
  expect(state.calls.find(c => c.path === '/api/quiz/generate')?.body.kbIds).toEqual([])
  expect(state.calls.some(c => c.path === '/api/knowledge-bases')).toBe(false)
  await page.goto('/admin/es')
  await expect(page).toHaveURL(/\/ask/)
})

test('RAG enabled keeps the existing knowledge navigation', async ({ page }) => {
  await setup(page, true)
  await page.goto('/ask')
  await expect(page.getByRole('link', { name: '知识库', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'ES 管理', exact: true })).toBeVisible()
})

test('search no-results can reset and mobile notes stay within viewport', async ({ page }) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/notes')
  await page.getByRole('textbox', { name: '搜索学习内容' }).fill('不存在')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('heading', { name: '没有找到相关内容' })).toBeVisible()
  await page.getByRole('button', { name: '清除搜索' }).click()
  await expect(page.getByRole('heading', { name: '数据库事务与 ES' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('deleting a learning record requires confirmation, preserves the row on failure, and can retry', async ({ page }) => {
  await setup(page)
  let calls = 0, failDelete = true, deleted = false
  await page.route('**/api/learning-notes/5', route => {
    expect(route.request().method()).toBe('DELETE'); calls++
    if (failDelete) return route.fulfill({ status: 503, json: { code: 503, message: '暂时无法删除' } })
    deleted = true
    return route.fulfill({ json: { code: 200, data: null } })
  })
  await page.route('**/api/learning-notes?*', route => deleted
    ? route.fulfill({ json: { code: 200, data: { records: [], total: 0 } } }) : route.fallback())
  await page.goto('/notes')
  const remove = page.getByRole('button', { name: '删除 数据库事务与 ES', exact: true })
  await remove.click()
  const confirmation = page.getByRole('dialog', { name: '删除这份学习记录？', exact: true })
  await expect(confirmation).toContainText('原问答快照会一并删除')
  await confirmation.getByRole('button', { name: '取消', exact: true }).click()
  expect(calls).toBe(0)
  await expect(remove).toBeVisible()
  await remove.click()
  await confirmation.getByRole('button', { name: '删除学习记录', exact: true }).click()
  await expect(page.getByRole('region', { name: '学习笔记' }).getByRole('alert')).toContainText('无法删除学习记录，请重试。')
  await expect(remove).toBeEnabled()
  expect(calls).toBe(1)
  failDelete = false
  await remove.click()
  await confirmation.getByRole('button', { name: '删除学习记录', exact: true }).click()
  await expect(page.getByRole('heading', { name: '从一个问题开始', exact: true })).toBeVisible()
  await expect(remove).toHaveCount(0)
  expect(calls).toBe(2)
})

test('starting a new topic creates one session instead of restoring the saved conversation', async ({ page }) => {
  const state = await setup(page)
  await page.addInitScript(() => localStorage.setItem('rag_session_id:1', '99'))
  await page.goto('/notes')
  await page.getByRole('link', { name: /开始新话题/ }).click()
  await expect(page).toHaveURL(/\/ask\?sessionId=10$/)
  await expect(page.getByRole('heading', { name: '今天想弄懂什么？', exact: true })).toBeVisible()
  expect(state.calls.filter(call => call.path === '/api/chat/session')).toHaveLength(1)
  expect(state.calls.some(call => call.path.includes('/sessions/99'))).toBe(false)
  expect(await page.evaluate(() => localStorage.getItem('rag_session_id:1'))).toBe('10')
})
