import { test, expect, type Page, type Route } from '@playwright/test'

async function setup(page: Page) {
  const sources = [{ id: 1, sessionId: 99, role: 'user', content: 'Token 怎么变成向量？' },
    { id: 2, sessionId: 99, role: 'assistant', content: '查矩阵的一行。\n\n```python\nvector = table[42]\n```' }]
  const nodes = [{ id: 'token', title: sources[0]!.content, body: '过去整理的解释。', children: [
    { id: 'embedding', title: 'Embedding 怎么训练？', body: '', children: [] }] }]
  const note = { id: 5, version: 1, title: 'LLM 输入处理', confusion: '输入如何进入网络', status: 'REVIEW', content: '旧笔记的完整内容。',
    treeJson: JSON.stringify({ version: 1, nodes }), sourceSessionId: 99, sourceSnapshot: JSON.stringify(sources), updatedAt: '2026-11-17' }
  const state = { note, failSuggestions: false, holdSuggestions: false, pending: undefined as Route | undefined,
    suggestionCalls: [] as string[], sourceCalls: [] as string[], missingSource: false }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'tree-test', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/learning-notes/5/open') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, noteId: 5, title: note.title }])
    if (path === '/api/sessions/10/messages') return ok([])
    if (path === '/api/sessions/10/learning-tree') return ok({ note, status: 'idle', nodeId: null, messageId: null, error: null })
    if (path.endsWith('/suggestions')) {
      state.suggestionCalls.push(path)
      if (state.holdSuggestions && path.includes('/token/')) { state.pending = route; return }
      if (state.failSuggestions) return route.fulfill({ status: 503, json: { code: 503, message: '暂时无法推荐' } })
      return ok({ questions: path.includes('/token/') ? ['词表大小怎么选？'] : ['梯度怎么传回参数？'] })
    }
    if (path.includes('/learning-tree/nodes/')) {
      state.sourceCalls.push(path)
      const token = path.endsWith('/token')
      return ok({ nodeId: token ? 'token' : 'embedding', title: token ? sources[0]!.content : nodes[0]!.children[0]!.title,
        messages: state.missingSource ? [] : token ? sources : [{ id: 3, role: 'user', content: 'Embedding 怎么训练？' }, { id: 4, role: 'assistant', content: '用损失的梯度更新参数。' }] })
    }
    if (path.includes('/sessions/99/')) throw new Error('Saved originals must not depend on the deleted source session')
    return ok([])
  })
  return state
}

test('saved original QA remains readable, hierarchy collapses, and repeated selection reuses recommendations', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/notes/5')
  const tree = page.getByRole('complementary', { name: '问题目录' })
  await tree.locator('.tree-node').filter({ hasText: 'Token 怎么变成向量？' }).click()
  await expect(page.getByRole('region', { name: '原问答回看' }).locator('pre')).toContainText('vector = table[42]')
  await expect(tree.getByRole('button', { name: /词表大小怎么选/ })).toBeVisible()
  await tree.locator('.tree-node').filter({ hasText: 'Token 怎么变成向量？' }).click()
  expect(state.suggestionCalls).toHaveLength(1)
  await tree.getByRole('button', { name: '收起Token 怎么变成向量？', exact: true }).click()
  await expect(tree.locator('.tree-node')).toHaveCount(1)
  await tree.getByRole('button', { name: '展开Token 怎么变成向量？', exact: true }).click()
  await expect(tree.locator('.tree-node')).toHaveCount(2)
  await page.reload()
  await tree.locator('.tree-node').first().click()
  await expect(page.getByRole('region', { name: '原问答回看' }).locator('pre')).toContainText('vector = table[42]')
})

test('recommendation failure leaves originals visible and can retry independently', async ({ page }) => {
  const state = await setup(page); state.failSuggestions = true
  await page.goto('/notes/5')
  const tree = page.getByRole('complementary', { name: '问题目录' })
  await tree.locator('.tree-node').first().click()
  await expect(page.getByRole('region', { name: '原问答回看' }).locator('pre')).toBeVisible()
  await expect(tree.getByRole('button', { name: '重新推荐', exact: true })).toBeVisible()
  state.failSuggestions = false
  await tree.getByRole('button', { name: '重新推荐', exact: true }).click()
  await expect(tree.getByRole('button', { name: /词表大小怎么选/ })).toBeVisible()
  expect(state.sourceCalls).toHaveLength(1)
})

test('late recommendations cannot overwrite a different selected node', async ({ page }) => {
  const state = await setup(page); state.holdSuggestions = true
  await page.goto('/notes/5')
  const tree = page.getByRole('complementary', { name: '问题目录' })
  await tree.locator('.tree-node').first().click()
  await expect.poll(() => !!state.pending).toBe(true)
  await tree.locator('.tree-node').filter({ hasText: 'Embedding 怎么训练？' }).click()
  await expect(tree.getByRole('button', { name: /梯度怎么传回参数/ })).toBeVisible()
  await state.pending!.fulfill({ json: { code: 200, data: { questions: ['旧请求的问题'] } } })
  await expect(tree.getByRole('button', { name: /旧请求的问题/ })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '原问答回看' })).toContainText('用损失的梯度更新参数。')
})

test('legacy unlinked nodes show an honest empty state and readonly historical content on mobile', async ({ page }) => {
  const state = await setup(page); state.missingSource = true
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/notes/5')
  await page.getByRole('complementary', { name: '问题目录' }).locator('.tree-node').first().click()
  await expect(page.getByRole('region', { name: '原问答回看' })).toContainText('尚未关联原问答')
  await page.getByRole('button', { name: '查看历史笔记', exact: true }).click()
  const history = page.getByRole('region', { name: '历史笔记' })
  await expect(history.locator('.history-note-content')).toHaveText('旧笔记的完整内容。')
  await expect(history).toContainText('输入如何进入网络')
  await history.locator('summary').click()
  await expect(history.locator('.history-note-source').last().locator('pre')).toContainText('vector = table[42]')
  await expect(page.getByRole('button', { name: /编辑笔记|整理正文|保存修改/ })).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
})
