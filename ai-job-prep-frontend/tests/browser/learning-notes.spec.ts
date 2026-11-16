import { test, expect, type Page } from '@playwright/test'

const messages = [
  { id: 1, sessionId: 10, role: 'user', content: '为什么事务不能回滚 ES？' },
  { id: 2, sessionId: 10, role: 'assistant', content: '数据库事务只管理数据库，ES 请求独立执行。', metadata: '{"response_status":"completed"}' },
  { id: 3, sessionId: 10, role: 'user', content: '补偿操作也失败怎么办？' },
  { id: 4, sessionId: 10, role: 'assistant', content: '记录待补偿任务，再进行重试。', metadata: '{"response_status":"completed"}' },
]
const makeNote = () => ({ id: 5, version: 0, title: '数据库事务与 ES', confusion: '为什么不能一起回滚', content: '事务只覆盖数据库。', status: 'PENDING', sourceSessionId: 10,
  sourceMessageIds: '[1,2]', sourceSnapshot: JSON.stringify(messages.slice(0, 2)), updatedAt: '2026-11-13T10:00:00' })
async function setup(page: Page, ragEnabled = false) {
  let note = makeNote()
  const calls: { path: string; method: string; body: any }[] = []
  let failDraft = false
  let failSave = false
  let deletedSource = false
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    if (!path.startsWith('/api/')) return route.continue()
    const method = route.request().method()
    const body = route.request().postDataJSON()
    calls.push({ path, method, body })
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'notes-test', role: 'admin' })
    if (path === '/api/features') return ok({ ragEnabled })
    if (path === '/api/chat/session') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, title: '事务问题' }, { id: 20, title: '继续学习', noteId: 5 }])
    if (path === '/api/sessions/10/messages') {
      if (deletedSource) return route.fulfill({ status: 404, json: { message: '会话不存在' } })
      return ok(messages)
    }
    if (path === '/api/sessions/20/messages') return ok(messages.slice(2).map(m => ({ ...m, sessionId: 20 })))
    if (path === '/api/learning-notes/draft') {
      if (failDraft) return route.fulfill({ status: 503, json: { message: '模型不可用' } })
      return ok({ title: '事务与补偿', confusion: '补偿失败如何恢复', content: '数据库与 ES 分别处理，通过任务记录进行补偿。' })
    }
    if (path === '/api/learning-notes/5/sessions') return ok(20)
    if (path === '/api/learning-notes' && method === 'GET') {
      return ok({ records: url.searchParams.get('query') === '不存在' ? [] : [note], total: url.searchParams.get('query') === '不存在' ? 0 : 1 })
    }
    if (path === '/api/learning-notes' && method === 'POST') {
      if (failSave) return route.fulfill({ status: 503, json: { message: '保存暂不可用' } })
      expect(body).not.toHaveProperty('sourceRanges')
      const sources = messages.filter(message => body.sourceMessageIds.includes(message.id))
      note = { ...note, ...body, sourceMessageIds: JSON.stringify(body.sourceMessageIds), sourceSnapshot: JSON.stringify(sources) }; return ok(note)
    }
    if (path === '/api/learning-notes/5' && method === 'PUT') {
      expect(body.expectedVersion).toBe(note.version)
      expect(body).not.toHaveProperty('sourceRanges')
      const ids = [...new Set([...JSON.parse(note.sourceMessageIds), ...(body.sourceMessageIds || [])])]
      note = { ...note, ...body, sourceMessageIds: JSON.stringify(ids), sourceSnapshot: JSON.stringify(messages.filter(message => ids.includes(message.id))), version: note.version + 1 }
      return ok(note)
    }
    if (path === '/api/learning-notes/5') return ok(note)
    if (path === '/api/knowledge-bases') return ok({ records: [], total: 0 })
    if (path === '/api/quiz/generate') return ok([{ id: 1, question: '测试题', options: { A: '甲', B: '乙', C: '丙', D: '丁' }, answer: 'A', explanation: '解析' }])
    return ok([])
  })
  return { calls, failDraft: () => { failDraft = true }, failSave: (value: boolean) => { failSave = value }, deleteSource: () => { deletedSource = true } }
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

test('save selected conversation without AI; draft and save failures preserve edits', async ({ page }) => {
  const state = await setup(page)
  state.failDraft(); state.failSave(true)
  await page.goto('/ask?sessionId=10')
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '存为学习笔记' })
  await expect(drawer.locator('input[type=checkbox]:checked')).toHaveCount(2)
  const content = drawer.getByLabel('笔记正文')
  await expect(content).toHaveValue(/数据库事务只管理数据库/)
  await content.fill('我自己的理解，不能丢失。')
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('原有内容已保留')
  await expect(content).toHaveValue('我自己的理解，不能丢失。')
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('保存失败')
  await expect(content).toHaveValue('我自己的理解，不能丢失。')
  state.failSave(false)
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer).toHaveCount(0)
  const saved = state.calls.filter(c => c.path === '/api/learning-notes' && c.method === 'POST').at(-1)!.body
  expect(saved.sourceMessageIds).toEqual([1, 2])
  expect(saved.content).toBe('我自己的理解，不能丢失。')
})

test('notes persist on reload, allow editing and keep snapshots after source deletion', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '编辑笔记' }).click()
  await page.getByLabel('标题', { exact: true }).fill('我理解的事务边界')
  await page.getByRole('button', { name: '保存修改' }).click()
  await page.reload()
  await expect(page.getByRole('heading', { name: '我理解的事务边界' })).toBeVisible()
  state.deleteSource()
  await page.getByText('保存时的原始问答（2 条）').click()
  await expect(page.locator('.note-source').last()).toContainText('ES 请求独立执行')
  await page.getByRole('link', { name: '查看来源会话' }).first().click()
  await expect(page.getByText('无法加载会话，请重试或新建对话。')).toBeVisible()
  await page.goBack()
  await page.getByText('保存时的原始问答（2 条）').click()
  await expect(page.locator('.note-source').last()).toContainText('ES 请求独立执行')
})

test('continue from note and preview update before saving it', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '继续问', exact: true }).click()
  await expect(page).toHaveURL(/sessionId=20/)
  await expect(page.locator('.linked-note')).toContainText('数据库事务与 ES')
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  await drawer.getByRole('button', { name: '结合所选问答整理', exact: true }).click()
  await expect(drawer.getByLabel('标题', { exact: true })).toHaveValue('事务与补偿')
  expect(state.calls.filter(c => c.method === 'PUT')).toHaveLength(0)
  await drawer.getByRole('button', { name: '保存更新' }).click()
  const saved = state.calls.find(c => c.path === '/api/learning-notes/5' && c.method === 'PUT')!.body
  expect(saved.sourceSessionId).toBe(20)
  expect(saved.sourceMessageIds).toEqual([3, 4])
  await expect(page.locator('.linked-note')).toContainText('事务与补偿')
})

test('search no-results can reset and mobile notes stay within viewport', async ({ page }) => {
  await setup(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/notes')
  await page.getByRole('textbox', { name: '搜索学习笔记' }).fill('不存在')
  await page.getByRole('button', { name: '搜索', exact: true }).click()
  await expect(page.getByRole('heading', { name: '没有符合条件的笔记' })).toBeVisible()
  await page.getByRole('button', { name: '清除筛选' }).click()
  await expect(page.getByRole('heading', { name: '数据库事务与 ES' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('streamed message IDs distinguish repeated identical answers', async ({ page }) => {
  const state = await setup(page)
  let streamed = false
  const repeated = [messages[0], messages[1]]
  await page.route('**/api/sessions/10/messages', route => route.fulfill({ json: { code: 200, data: streamed
    ? [...repeated, { ...messages[0], id: 5 }, { ...messages[1], id: 6 }] : repeated } }))
  await page.route('**/api/agent/chat/stream', route => {
    streamed = true
    return route.fulfill({ contentType: 'text/event-stream', body: `event: token\ndata: ${JSON.stringify({ content: messages[1]!.content })}\n\nevent: status\ndata: ${JSON.stringify({ status: 'completed', messageId: 6 })}\n\n` })
  })
  await page.goto('/ask?sessionId=10')
  await page.getByRole('textbox', { name: '对话内容' }).fill(messages[0]!.content)
  await page.getByRole('button', { name: '发送消息' }).click()
  await expect(page.getByRole('button', { name: '存为学习笔记', exact: true })).toHaveCount(2)
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).last().click()
  await page.getByRole('button', { name: '保存笔记', exact: true }).click()
  expect(state.calls.find(c => c.path === '/api/learning-notes' && c.method === 'POST')?.body.sourceMessageIds).toEqual([5, 6])
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).first().click()
  await page.getByRole('button', { name: '保存笔记', exact: true }).click()
  expect(state.calls.filter(c => c.path === '/api/learning-notes' && c.method === 'POST').at(-1)?.body.sourceMessageIds).toEqual([1, 2])
})

test('multiple turns can be selected while interrupted answers are excluded', async ({ page }) => {
  const state = await setup(page)
  await page.route('**/api/sessions/10/messages', route => route.fulfill({ json: { code: 200, data: [
    ...messages, { id: 5, role: 'user', content: '继续解释' }, { id: 6, role: 'assistant', content: '尚未完成的解释', metadata: '{"response_status":"truncated"}' },
  ] } }))
  await page.goto('/ask?sessionId=10')
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '存为学习笔记' })
  await expect(drawer.getByRole('checkbox', { name: /尚未完成的解释/ })).toHaveCount(0)
  await drawer.locator('label.el-checkbox').filter({ hasText: '补偿操作也失败' }).click()
  await drawer.locator('label.el-checkbox').filter({ hasText: '记录待补偿任务' }).click()
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  expect(state.calls.find(c => c.path === '/api/learning-notes' && c.method === 'POST')?.body.sourceMessageIds).toEqual([1, 2, 3, 4])
})
