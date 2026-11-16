import { test, expect, type Page, type Route } from '@playwright/test'

const messages = [
  { id: 1, sessionId: 10, role: 'user', content: '为什么事务不能回滚 ES？' },
  { id: 2, sessionId: 10, role: 'assistant', content: '事务只覆盖数据库。', metadata: '{"response_status":"completed"}' },
  { id: 3, sessionId: 10, role: 'user', content: '补偿也失败怎么办？' },
  { id: 4, sessionId: 10, role: 'assistant', content: '记录补偿任务并重试。', metadata: '{"response_status":"completed"}' },
]
const makeNote = () => ({ id: 5, version: 0, title: '数据库事务', confusion: '为什么不能一起回滚', content: '最初的理解', status: 'PENDING',
  sourceSessionId: 10, sourceMessageIds: '[1,2]', sourceSnapshot: JSON.stringify(messages.slice(0, 2)), updatedAt: '2026-11-13T10:00:00' })

async function fixture(page: Page) {
  const state = { note: makeNote(), logout: 0, loggedIn: true, failDraft: false, holdDraft: false,
    pendingDraft: undefined as Route | undefined, loseCreateResponse: false,
    drafts: [] as any[], creates: [] as any[], updates: [] as any[], committed: new Map<string, any>() }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const method = route.request().method()
    const body = route.request().postDataJSON()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return state.loggedIn ? ok({ id: 1, username: 'notes-user', role: 'user' }) : route.fulfill({ status: 401, json: { message: '未登录' } })
    if (path === '/api/auth/logout') { state.logout++; state.loggedIn = false; return ok(null) }
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/chat/session') return ok(10)
    if (path === '/api/sessions') return ok([{ id: 10, title: '原问答' }, { id: 20, title: '继续学习', noteId: 5 }])
    if (path === '/api/sessions/10/messages' || path === '/api/sessions/20/messages') return ok(messages)
    if (path === '/api/learning-notes/5/sessions') return ok(20)
    if (path === '/api/learning-notes/draft') {
      state.drafts.push(body)
      if (state.holdDraft) { state.pendingDraft = route; return }
      if (state.failDraft) return route.fulfill({ status: 503, json: { message: '整理失败' } })
      return ok({ ...body.currentDraft, content: body.currentDraft.content + '\n整理补充：记录补偿任务。' })
    }
    if (path === '/api/learning-notes' && method === 'POST') {
      state.creates.push(body)
      expect(body).not.toHaveProperty('sourceRanges')
      if (!state.committed.has(body.requestId)) state.committed.set(body.requestId, { ...makeNote(), ...body, sourceMessageIds: JSON.stringify(body.sourceMessageIds), sourceSnapshot: JSON.stringify(messages.filter(message => body.sourceMessageIds.includes(message.id))), id: 40 + state.committed.size })
      if (state.loseCreateResponse) { state.loseCreateResponse = false; return route.abort('connectionreset') }
      return ok(state.committed.get(body.requestId))
    }
    if (path === '/api/learning-notes/5' && method === 'PUT') {
      state.updates.push(body)
      if (body.expectedVersion !== state.note.version) return route.fulfill({ status: 409, json: { message: '笔记已有新版本' } })
      expect(body).not.toHaveProperty('sourceRanges')
      const ids = [...new Set([...JSON.parse(state.note.sourceMessageIds), ...(body.sourceMessageIds || [])])]
      state.note = { ...state.note, ...body, sourceMessageIds: JSON.stringify(ids), sourceSnapshot: JSON.stringify(messages.filter(message => ids.includes(message.id))), version: state.note.version + 1 }; return ok(state.note)
    }
    if (path === '/api/learning-notes/5') return ok(state.note)
    if (path === '/api/learning-notes') return ok({ records: [state.note], total: 1 })
    return ok([])
  })
  return state
}

async function openCreate(page: Page) {
  await page.goto('/ask?sessionId=10')
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).first().click()
  return page.getByRole('dialog', { name: '存为学习笔记', exact: true })
}

test('update editor refreshes a linked note changed after opening the chat', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/ask?sessionId=20')
  await expect(page.locator('.linked-note')).toContainText('数据库事务')
  state.note = { ...state.note, version: 1, content: '另一页面已经保存的新理解' }
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('另一页面已经保存的新理解')
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  expect(state.updates[0].expectedVersion).toBe(1)
  expect(state.note.content).toBe('另一页面已经保存的新理解')
})

test('editor conflict preserves edits and only explicitly loading latest changes the version', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/ask?sessionId=20')
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  await drawer.getByLabel('笔记正文').fill('当前草稿必须保留')
  state.note = { ...state.note, version: 1, content: '已保存的新版本' }
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('新版本')
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('当前草稿必须保留')
  expect(state.note.content).toBe('已保存的新版本')
  await drawer.getByRole('button', { name: '载入最新版本', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('当前草稿必须保留')
  await drawer.getByRole('button', { name: '载入最新版本', exact: true }).click()
  await page.getByRole('dialog', { name: '载入最新笔记' }).getByRole('button', { name: '载入最新版本', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('已保存的新版本')
  await drawer.getByLabel('笔记正文').fill('已保存的新版本\n合并后的个人理解')
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  expect(state.updates.map(b => b.expectedVersion)).toEqual([0, 1])
  expect(state.note.content).toContain('合并后的个人理解')
  await expect(drawer).toHaveCount(0)
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '编辑笔记', exact: true }).click()
  await page.getByLabel('笔记正文').fill('详情页的编辑也必须保留')
  state.note = { ...state.note, version: 3, content: '其他页面的新内容' }
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.locator('.notes-page').getByRole('alert')).toContainText('新版本')
  await expect(page.getByLabel('笔记正文')).toHaveValue('详情页的编辑也必须保留')
  await page.getByRole('button', { name: '载入最新版本', exact: true }).click()
  await page.getByRole('dialog', { name: '载入最新笔记' }).getByRole('button', { name: '载入最新版本', exact: true }).click()
  await expect(page.getByLabel('笔记正文')).toHaveValue('其他页面的新内容')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
  expect(state.updates.map(b => b.expectedVersion)).toEqual([0, 1, 2, 3])
})

test('AI draft receives current edits and failure leaves them available for saving', async ({ page }) => {
  const state = await fixture(page)
  const drawer = await openCreate(page)
  await drawer.getByLabel('标题', { exact: true }).fill('我的项目问题')
  await drawer.getByLabel('我的困惑（选填）').fill('补偿流水号怎么保存')
  await drawer.getByLabel('笔记正文').fill('我自己的例子：业务流水号必须保留。')
  state.failDraft = true
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('原有内容已保留')
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('我自己的例子：业务流水号必须保留。')
  state.failDraft = false
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(/业务流水号必须保留。\n整理补充/)
  expect(state.drafts[1].currentDraft).toEqual({ title: '我的项目问题', confusion: '补偿流水号怎么保存', content: '我自己的例子：业务流水号必须保留。' })
  expect(state.creates).toHaveLength(0)
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  expect(state.creates[0].content).toContain('业务流水号必须保留')
})

test('selection only chooses sources for the next action and never rewrites the draft', async ({ page }) => {
  const state = await fixture(page)
  const drawer = await openCreate(page)
  const initial = await drawer.getByLabel('笔记正文').inputValue()
  await drawer.locator('label.el-checkbox').filter({ hasText: '补偿也失败' }).click()
  await drawer.locator('label.el-checkbox').filter({ hasText: '记录补偿任务' }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(initial)
  await drawer.locator('label.el-checkbox').filter({ hasText: '事务只覆盖数据库' }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(initial)
  await drawer.getByLabel('笔记正文').fill('手写理解不能被勾选动作覆盖')
  await drawer.locator('label.el-checkbox').filter({ hasText: '事务只覆盖数据库' }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('手写理解不能被勾选动作覆盖')
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  expect(state.creates[0].sourceMessageIds).toHaveLength(4)
  expect(state.creates[0].content).toBe('手写理解不能被勾选动作覆盖')
})

test('browser back and drawer close preserve unsaved edits until discard is confirmed', async ({ page }) => {
  await fixture(page)
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '继续问', exact: true }).click()
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  await drawer.getByLabel('笔记正文').fill('尚未保存的新理解')
  await page.goBack()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '笔记尚未保存' })).toHaveCount(0)
  await expect(page).toHaveURL(/sessionId=20/)
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('尚未保存的新理解')
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '笔记尚未保存' })).toHaveCount(0)
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('尚未保存的新理解')
  await page.goBack()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page).toHaveURL(/\/notes\/5$/)
})

test('logout checks edits before sending request and confirms discard only once', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '编辑笔记', exact: true }).click()
  await page.getByLabel('笔记正文').fill('退出前的个人理解')
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await page.getByRole('button', { name: '继续编辑', exact: true }).click()
  expect(state.logout).toBe(0)
  await expect(page.getByLabel('笔记正文')).toHaveValue('退出前的个人理解')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('button', { name: '编辑笔记', exact: true }).click()
  await page.getByLabel('笔记正文').fill('决定放弃的下一次修改')
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await page.getByRole('button', { name: '放弃修改', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
  expect(state.logout).toBe(1)
  await expect(page.getByRole('dialog', { name: '笔记尚未保存' })).toHaveCount(0)
})

test('a committed create with lost response retries the same request identifier', async ({ page }) => {
  const state = await fixture(page)
  state.loseCreateResponse = true
  const drawer = await openCreate(page)
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('保存失败')
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer).toHaveCount(0)
  expect(state.creates).toHaveLength(2)
  expect(state.creates[0].requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(state.creates[0]).toEqual(state.creates[1])
  expect(state.committed.size).toBe(1)
})

test('draft in progress blocks leaving without losing the current editor', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '继续问', exact: true }).click()
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).first().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  await drawer.getByLabel('笔记正文').fill('模型调用期间保留')
  state.holdDraft = true
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect.poll(() => !!state.pendingDraft).toBe(true)
  await page.goBack()
  await expect(page).toHaveURL(/sessionId=20/)
  await expect(drawer).toBeVisible()
  await expect(page.locator('.el-message--warning')).toContainText('等待完成')
  await state.pendingDraft!.fulfill({ status: 503, json: { message: '整理失败' } })
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('模型调用期间保留')
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  expect(state.note.content).toBe('模型调用期间保留')
})

test('copying selected sources is an explicit repeatable text operation while snapshots are unique', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/ask?sessionId=20')
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).last().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  const content = drawer.getByLabel('笔记正文')
  await expect(content).toHaveValue('最初的理解')
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  const merged = '最初的理解\n\n### 问题\n\n补偿也失败怎么办？\n\n### 回答\n\n记录补偿任务并重试。'
  await expect(content).toHaveValue(merged)
  await expect(drawer.getByRole('button', { name: '复制所选原文到正文', exact: true })).toBeEnabled()
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  const twice = merged + '\n\n### 问题\n\n补偿也失败怎么办？\n\n### 回答\n\n记录补偿任务并重试。'
  await expect(content).toHaveValue(twice)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  expect(state.note.content).toBe(twice)
  expect(state.updates[0].sourceMessageIds).toEqual([3, 4])
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2, 3, 4])
  expect(state.note.version).toBe(1)
})

test('merge receives current body and selected sources without saving until requested', async ({ page }) => {
  const state = await fixture(page)
  await page.goto('/ask?sessionId=20')
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).last().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  state.failDraft = true
  await drawer.getByRole('button', { name: '结合所选问答整理', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('原有内容已保留')
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('最初的理解')
  state.failDraft = false
  await drawer.getByRole('button', { name: '结合所选问答整理', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('最初的理解\n整理补充：记录补偿任务。')
  expect(state.updates).toHaveLength(0)
  expect(state.drafts[1].mode).toBe('MERGE')
  expect(state.drafts[1].currentDraft.content).toBe('最初的理解')
  expect(state.drafts[1].sourceMessageIds).toEqual([3, 4])
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  expect(state.note.content).toContain('最初的理解\n整理补充：记录补偿任务。')
})

test('copy beyond note length limit preserves existing edits and can recover with fewer characters', async ({ page }) => {
  const state = await fixture(page)
  state.note.content = '原'.repeat(39995)
  await page.goto('/ask?sessionId=20')
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).last().click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  const content = drawer.getByLabel('笔记正文')
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('40000')
  await expect(content).toHaveValue(state.note.content)
  expect(state.updates).toHaveLength(0)
  await content.fill('精简后的原理解')
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  await expect(drawer.getByRole('alert')).toHaveCount(0)
  await expect(content).toHaveValue(/精简后的原理解[\s\S]*记录补偿任务并重试/)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  expect(state.note.content).toContain('精简后的原理解')
  expect(state.note.content).toContain('记录补偿任务并重试')
})
