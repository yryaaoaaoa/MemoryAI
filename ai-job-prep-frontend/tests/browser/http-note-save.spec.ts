import { test, expect } from '@playwright/test'

test('HTTP learning tree opens original QA and sends a recommendation without manual note editing', async ({ page }) => {
  const errors: string[] = [], sent: any[] = [], writes: string[] = []
  let answered = false
  const question = '线程池是怎么工作的？', followup = '队列满了以后会怎样？'
  const source = [{ id: 1, sessionId: 10, role: 'user', content: question },
    { id: 2, sessionId: 10, role: 'assistant', content: '先创建核心线程，再尝试入队。\n\n```java\nexecutor.execute(task);\n```', metadata: '{"response_status":"completed"}' }]
  const tree = () => ({ version: 1, nodes: [{ id: 'pool', title: question, body: '', sourceMessageIds: [1, 2], children: answered
    ? [{ id: 'queue', title: followup, body: '', sourceMessageIds: [3, 4], children: [] }] : [] }] })
  const note = () => ({ id: 5, version: answered ? 1 : 0, title: '线程池学习', confusion: '', content: `## ${question}`, status: 'PENDING',
    treeJson: JSON.stringify(tree()), sourceSessionId: 10, sourceSnapshot: JSON.stringify(source), updatedAt: '2026-11-17T10:00:00' })
  page.on('pageerror', error => errors.push(error.message))
  // Exercise the same insecure HTTP environment as production, where randomUUID is unavailable.
  await page.route('http://memoryai.test/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (!path.startsWith('/api/')) return route.fulfill({ response: await route.fetch({ url: `http://127.0.0.1:4179${path}${url.search}` }) })
    if (route.request().method() !== 'GET' && path.startsWith('/api/learning-notes')) writes.push(path)
    if (path === '/api/auth/me') return ok({ id: 1, username: 'http-tree-test', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/learning-notes') return ok({ records: [note()], total: 1 })
    if (path === '/api/learning-notes/5/open') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, title: '线程池学习', noteId: 5 }])
    if (path === '/api/sessions/10/messages') return ok(answered ? [...source,
      { id: 3, role: 'user', content: followup }, { id: 4, role: 'assistant', content: '尝试新增线程；达到上限后执行拒绝策略。', metadata: '{"response_status":"completed"}' }] : source)
    if (path === '/api/sessions/10/learning-tree') return ok({ note: note(), status: 'updated', nodeId: answered ? 'queue' : 'pool', messageId: answered ? 4 : 2, error: null })
    if (path === '/api/sessions/10/learning-tree/nodes/pool') return ok({ nodeId: 'pool', title: question, messages: source })
    if (path.endsWith('/suggestions')) return ok({ questions: answered ? ['拒绝策略有哪些选择？'] : [followup] })
    if (path === '/api/agent/chat/stream') {
      sent.push(route.request().postDataJSON()); answered = true
      return route.fulfill({ contentType: 'text/event-stream', body: 'event: token\ndata: {"content":"尝试新增线程；达到上限后执行拒绝策略。"}\n\nevent: status\ndata: {"status":"completed","messageId":4}\n\n' })
    }
    return ok([])
  })
  await page.goto('http://memoryai.test/notes')
  expect(await page.evaluate(() => ({ secure: isSecureContext, uuid: typeof crypto.randomUUID }))).toEqual({ secure: false, uuid: 'undefined' })
  await page.getByRole('link', { name: /线程池学习/ }).click()
  await expect(page).toHaveURL(/\/ask\?sessionId=10$/)
  const panel = page.getByRole('complementary', { name: '问题目录' })
  await panel.getByRole('button', { name: question, exact: false }).click()
  await expect(page.getByRole('region', { name: '原问答回看' }).locator('pre')).toContainText('executor.execute(task);')
  await expect(panel.locator('.node-body')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /存为学习笔记|编辑笔记|整理正文|历史笔记/ })).toHaveCount(0)
  const recommendation = panel.getByRole('button', { name: followup, exact: false })
  await page.getByRole('textbox', { name: '对话内容' }).fill('暂存草稿')
  await expect(recommendation).toBeDisabled()
  await expect(page.getByRole('textbox', { name: '对话内容' })).toHaveValue('暂存草稿')
  await page.getByRole('textbox', { name: '对话内容' }).fill('')
  await recommendation.click()
  await expect(panel.locator('.tree-node').filter({ hasText: followup })).toBeVisible()
  await expect(page.getByRole('region', { name: '原问答回看' })).toHaveCount(0)
  await expect(panel.locator('.suggested-question').filter({ hasText: followup })).toHaveCount(0)
  expect(sent).toHaveLength(1)
  expect(sent[0]).toMatchObject({ message: followup, sessionId: 10, noteNodeId: 'pool', organizeNotes: true })
  expect(writes).toEqual(['/api/learning-notes/5/open'])
  await page.reload()
  await expect(panel.locator('.tree-node')).toHaveCount(2)
  expect(errors).toEqual([])
})
