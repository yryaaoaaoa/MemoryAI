import { test, expect, type Page, type Locator } from '@playwright/test'

type Message = { id: number; sessionId: number; role: string; content: string; metadata?: string }
const raw = (messages: Message[]) => messages.map(message => `### ${message.role === 'user' ? '问题' : '回答'}\n\n${message.content}`).join('\n\n')
const initialMessages: Message[] = [
  { id: 1, sessionId: 20, role: 'user', content: '较早的问题' },
  { id: 2, sessionId: 20, role: 'assistant', content: '较早回答。', metadata: '{"response_status":"completed"}' },
  { id: 3, sessionId: 20, role: 'user', content: '补偿任务要怎么记录？' },
  { id: 4, sessionId: 20, role: 'assistant', content: '需要理解：任务先持久化，失败后重试。', metadata: '{"response_status":"completed"}' },
]

async function setup(page: Page, options: { create?: boolean; messages?: Message[]; content?: string } = {}) {
  const messages = options.messages ?? initialMessages
  const state = {
    note: { id: 5, version: 0, title: '笔记操作', confusion: '', content: options.content ?? '保留原有理解。', status: 'PENDING',
      sourceSessionId: 20, sourceMessageIds: '[1,2]', sourceSnapshot: JSON.stringify(messages.slice(0, 2)) },
    creates: [] as any[], updates: [] as any[], drafts: [] as any[], failSave: false, failDraft: false,
    rewrittenBody: undefined as string | undefined,
  }
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'note-operations', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/sessions') return ok([{ id: 20, title: '学习对话', noteId: 5 }])
    if (path === '/api/sessions/20/messages') return ok(messages)
    if (path === '/api/learning-notes/draft') {
      const input = request.postDataJSON(); state.drafts.push(input)
      expect(input).not.toHaveProperty('includedSourceMessageIds')
      expect(input).not.toHaveProperty('sourceRanges')
      expect(['BODY', 'MERGE']).toContain(input.mode)
      if (input.mode === 'BODY') {
        expect(input).not.toHaveProperty('sourceSessionId')
        expect(input).not.toHaveProperty('sourceMessageIds')
      } else {
        expect(input.sourceSessionId).toBe(20)
        expect(input.sourceMessageIds.length).toBeGreaterThan(0)
        expect(input.sourceMessageIds.length).toBeLessThanOrEqual(40)
        expect(input.sourceMessageIds.every((id: number) => messages.some(message => message.id === id))).toBe(true)
        const sourceLength = messages.filter(message => input.sourceMessageIds.includes(message.id)).reduce((sum, message) => sum + message.content.length, 0)
        if (input.currentDraft.content.length + sourceLength > 40000) {
          return route.fulfill({ status: 400, json: { message: '正文与所选问答合计超过 40000 字，请减少选择或使用整理正文。' } })
        }
      }
      if (state.failDraft) return route.fulfill({ status: 503, json: { message: '模型暂不可用' } })
      return ok({ ...input.currentDraft, content: state.rewrittenBody ?? input.currentDraft.content + '\n\n整理后的补充。' })
    }
    if (path === '/api/learning-notes' && request.method() === 'GET') return ok({ records: [state.note], total: 1 })
    if ((path === '/api/learning-notes' && request.method() === 'POST') || (path === '/api/learning-notes/5' && request.method() === 'PUT')) {
      const input = request.postDataJSON(); const create = request.method() === 'POST'
      ;(create ? state.creates : state.updates).push(input)
      expect(input).not.toHaveProperty('sourceRanges')
      expect(input).not.toHaveProperty('includedSourceMessageIds')
      if (state.failSave) return route.fulfill({ status: 503, json: { message: '保存暂不可用' } })
      if (!create && input.expectedVersion !== state.note.version) return route.fulfill({ status: 409, json: { message: '笔记已有新版本' } })
      if (create || input.sourceMessageIds !== undefined) {
        expect(input.sourceSessionId).toBe(20)
        expect(input.sourceMessageIds.every((id: number) => messages.some(message => message.id === id))).toBe(true)
      } else expect(input).not.toHaveProperty('sourceSessionId')
      const ids = [...new Set<number>([...(create ? [] : JSON.parse(state.note.sourceMessageIds)), ...(input.sourceMessageIds ?? [])])]
      state.note = { ...state.note, ...input, sourceMessageIds: JSON.stringify(ids),
        sourceSnapshot: JSON.stringify(messages.filter(message => ids.includes(message.id))), version: create ? 0 : state.note.version + 1 }
      return ok(state.note)
    }
    if (path === '/api/learning-notes/5') return ok(state.note)
    return ok([])
  })
  const open = async () => {
    await page.goto('/ask?sessionId=20')
    await page.getByRole('button', { name: options.create ? '存为学习笔记' : '更新这条笔记', exact: true }).last().click()
    return page.getByRole('dialog', { name: options.create ? '存为学习笔记' : '更新学习笔记', exact: true })
  }
  return { state, messages, open, drawer: await open() }
}

async function uncheckAll(drawer: Locator) {
  await expect(drawer.locator('label.el-checkbox').first()).toBeVisible()
  const checked = drawer.locator('label.el-checkbox:has(input:checked)')
  while (await checked.count()) await checked.first().click()
  await expect(drawer.locator('input[type=checkbox]:checked')).toHaveCount(0)
}

for (const item of [
  { name: 'identical short answers', oldQ: '能把密码写日志吗？', oldA: '不可以。', q: '能把访问令牌放 URL 吗？', a: '不可以。' },
  { name: 'a character prefix answer', oldQ: '是否可以读缓存？', oldA: '可以用于读缓存，但不能用于写缓存。', q: '是否允许写缓存？', a: '可以' },
  { name: 'a paragraph prefix of an answer to the same question', oldQ: '能使用缓存吗？', oldA: '可以。\n\n但不能保存敏感数据。', q: '能使用缓存吗？', a: '可以。' },
]) {
  test(`explicit copy preserves full ${item.name} without guessing identity`, async ({ page }) => {
    const messages = initialMessages.map((message, index) => ({ ...message, content: [item.oldQ, item.oldA, item.q, item.a][index]! }))
    const original = raw(messages.slice(0, 2))
    const { state, drawer } = await setup(page, { messages, content: original })
    await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
    const expected = original + '\n\n' + raw(messages.slice(2))
    await expect(drawer.getByLabel('笔记正文')).toHaveValue(expected)
    await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
    await expect(drawer).not.toBeVisible()
    expect(state.note.content).toBe(expected)
    expect(JSON.parse(state.note.sourceSnapshot).map((source: Message) => source.id)).toEqual([1, 2, 3, 4])
  })
}

for (const create of [true, false]) {
  test(`${create ? 'new' : 'existing'} note retains copied origins after deselection, save and reopening`, async ({ page }) => {
    const messages = initialMessages.map(message => message.id === 4 ? { ...message, content: '需要理解的长答案。'.repeat(2400) } : message)
    const { state, drawer } = await setup(page, { create, messages })
    if (!create) await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
    const body = drawer.getByLabel('笔记正文')
    const expected = await body.inputValue() + '\n\n手写尾注：保留完整来源。'
    await body.fill(expected)
    await drawer.locator('label.el-checkbox').nth(3).click()
    await expect(body).toHaveValue(expected)
    await drawer.getByRole('button', { name: create ? '保存笔记' : '保存更新', exact: true }).click()
    await expect(drawer).not.toBeVisible()
    expect(JSON.parse(state.note.sourceMessageIds)).toEqual(expect.arrayContaining([3, 4]))
    expect(JSON.parse(state.note.sourceSnapshot).find((message: Message) => message.id === 4).content).toBe(messages[3]!.content)
    await page.reload()
    await page.getByRole('button', { name: '更新这条笔记', exact: true }).last().click()
    const reopened = page.getByRole('dialog', { name: '更新学习笔记', exact: true })
    await expect(reopened.getByLabel('笔记正文')).toHaveValue(expected)
    await uncheckAll(reopened)
    await reopened.getByRole('button', { name: '整理正文', exact: true }).click()
    await expect(reopened.getByLabel('笔记正文')).toHaveValue(expected + '\n\n整理后的补充。')
    expect(state.drafts[0].mode).toBe('BODY')
    expect(state.drafts[0]).not.toHaveProperty('sourceMessageIds')
    await reopened.getByRole('button', { name: '保存更新', exact: true }).click()
    await expect(reopened).not.toBeVisible()
    expect(JSON.parse(state.note.sourceMessageIds)).toEqual(expect.arrayContaining([3, 4]))
  })
}

test('out-of-order copies preserve body order while retaining each immutable source once', async ({ page }) => {
  const { state, drawer, messages } = await setup(page)
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  await uncheckAll(drawer)
  await drawer.locator('label.el-checkbox').nth(0).click()
  await drawer.locator('label.el-checkbox').nth(1).click()
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  const expected = '保留原有理解。\n\n' + raw(messages.slice(2)) + '\n\n' + raw(messages.slice(0, 2))
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(expected)
  await uncheckAll(drawer)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.note.content).toBe(expected)
  expect(JSON.parse(state.note.sourceSnapshot).map((message: Message) => message.id)).toEqual([1, 2, 3, 4])
})

test('captured sources may exceed the single-action selection limit when copied in separate batches', async ({ page }) => {
  test.setTimeout(60000)
  const messages: Message[] = Array.from({ length: 42 }, (_, index) => ({
    id: index + 1, sessionId: 20, role: index % 2 === 0 ? 'user' : 'assistant',
    content: `来源${index + 1}的完整内容。`, ...(index % 2 ? { metadata: '{"response_status":"completed"}' } : {}),
  }))
  const { state, drawer } = await setup(page, { create: true, messages })
  const original = raw(messages.slice(-2))
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(original)
  await uncheckAll(drawer)
  for (let index = 0; index < 40; index++) await drawer.locator('label.el-checkbox').nth(index).click()
  await expect(drawer.locator('input[type=checkbox]:checked')).toHaveCount(40)
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(original)
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  const expected = original + '\n\n' + raw(messages.slice(0, 40))
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(expected)
  await uncheckAll(drawer)
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.creates[0].sourceMessageIds).toHaveLength(42)
  expect(new Set(state.creates[0].sourceMessageIds).size).toBe(42)
  expect(state.note.content).toBe(expected)
  expect(JSON.parse(state.note.sourceSnapshot)).toEqual(messages)
})

test('native long-text undo and full redo restore only body and never discard retained origins', async ({ page }) => {
  test.setTimeout(60000)
  const { state, drawer, messages } = await setup(page)
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  const body = drawer.getByLabel('笔记正文')
  const original = await body.inputValue()
  await body.focus()
  await body.evaluate((element: HTMLTextAreaElement) => { const index = element.value.indexOf('需要理解') + 2; element.setSelectionRange(index, index) })
  await page.keyboard.type('X'.repeat(150))
  const edited = await body.inputValue()
  for (let i = 0; i < 150 && await body.inputValue() !== original; i++) await page.keyboard.press('Control+z')
  await expect(body).toHaveValue(original)
  for (let i = 0; i < 150 && await body.inputValue() !== edited; i++) await page.keyboard.press('Control+Shift+z')
  await expect(body).toHaveValue(edited)
  for (let i = 0; i < 150 && await body.inputValue() !== original; i++) await page.keyboard.press('Control+z')
  await expect(body).toHaveValue(original)
  await body.press('Control+a')
  await body.press('Backspace')
  await expect(body).toHaveValue('')
  await expect(drawer.getByRole('button', { name: '保存更新', exact: true })).toBeDisabled()
  await expect(drawer.getByText('保留的原始问答（4 条）', { exact: true })).toBeVisible()
  await body.press('Control+z')
  await expect(body).toHaveValue(original)
  await body.press('Control+Shift+z')
  await expect(body).toHaveValue('')
  await body.press('Control+z')
  await expect(body).toHaveValue(original)
  await uncheckAll(drawer)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.note.content).toBe(original)
  expect(JSON.parse(state.note.sourceSnapshot)).toEqual(messages)
})

test('body-only editing with no selection saves without adding origins or requiring old chat messages', async ({ page }) => {
  const { state, drawer } = await setup(page)
  await uncheckAll(drawer)
  await drawer.getByLabel('笔记正文').fill('仅修正自己的理解。')
  await expect(drawer.getByRole('button', { name: '结合所选问答整理', exact: true })).toBeDisabled()
  await expect(drawer.getByRole('button', { name: '复制所选原文到正文', exact: true })).toBeDisabled()
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('仅修正自己的理解。\n\n整理后的补充。')
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.updates[0]).not.toHaveProperty('sourceMessageIds')
  expect(state.updates[0]).not.toHaveProperty('sourceSessionId')
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2])
})

test('successful AI merge retains full source snapshots even when output rewrites every word', async ({ page }) => {
  const { state, drawer, messages } = await setup(page)
  state.rewrittenBody = '新的总结没有沿用任何原文字句。'
  await drawer.getByRole('button', { name: '结合所选问答整理', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(state.rewrittenBody)
  await uncheckAll(drawer)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(JSON.parse(state.note.sourceSnapshot)).toEqual(messages)
  expect(state.drafts[0].sourceMessageIds).toEqual([3, 4])
  await page.goto('/notes/5')
  await page.getByText('保存时的原始问答（4 条）').click()
  await expect(page.locator('.note-source').last()).toContainText(messages[3]!.content)
})

test('failed merge does not silently capture unselected sources on a later save', async ({ page }) => {
  const { state, drawer } = await setup(page)
  state.failDraft = true
  await drawer.getByRole('button', { name: '结合所选问答整理', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('原有内容已保留')
  await uncheckAll(drawer)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.note.content).toBe('保留原有理解。')
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2])
})

test('save failure preserves copied origins and text for an unchanged retry', async ({ page }) => {
  const { state, drawer } = await setup(page)
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  await uncheckAll(drawer)
  const expected = await drawer.getByLabel('笔记正文').inputValue()
  state.failSave = true
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('保存失败')
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(expected)
  state.failSave = false
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.updates[0]).toEqual(state.updates[1])
  expect(state.note.content).toBe(expected)
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2, 3, 4])
})

test('failed source-only save protects pending origins on close and retains them when deselected before retry', async ({ page }) => {
  const { state, drawer } = await setup(page)
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('保留原有理解。')
  state.failSave = true
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('保存失败')
  expect(state.updates[0].sourceMessageIds).toEqual([3, 4])
  expect(state.note.content).toBe('保留原有理解。')
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2])
  await drawer.getByRole('button', { name: '取消', exact: true }).click()
  const confirmation = page.getByRole('dialog', { name: '笔记尚未保存', exact: true })
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '继续编辑', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('保留原有理解。')
  await uncheckAll(drawer)
  state.failSave = false
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.updates[1]).toEqual(state.updates[0])
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2, 3, 4])
})

test('loading latest discards unsaved captured origins together with the discarded draft', async ({ page }) => {
  const { state, drawer } = await setup(page)
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  await uncheckAll(drawer)
  state.note = { ...state.note, content: '其他页面最新正文。', version: 1 }
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('新版本')
  await drawer.getByRole('button', { name: '载入最新版本', exact: true }).click()
  await page.getByRole('dialog', { name: '载入最新笔记' }).getByRole('button', { name: '载入最新版本', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('其他页面最新正文。')
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(state.updates[1]).not.toHaveProperty('sourceMessageIds')
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2])
})

test('oversized source merge retains the body and can recover through body-only organization', async ({ page }) => {
  const messages = initialMessages.map(message => message.id === 4 ? { ...message, content: '完整的长解释。'.repeat(3000) } : message)
  const { state, drawer } = await setup(page, { messages, content: '手写笔记。'.repeat(4000) })
  const before = await drawer.getByLabel('笔记正文').inputValue()
  await drawer.getByRole('button', { name: '结合所选问答整理', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('40000')
  await expect(drawer.getByRole('alert')).toContainText('整理正文')
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(before)
  expect(state.updates).toHaveLength(0)
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(before + '\n\n整理后的补充。')
  expect(state.drafts.at(-1).mode).toBe('BODY')
})

test('replacing body requires explicit confirmation and preserves previously retained origins', async ({ page }) => {
  const { state, drawer, messages } = await setup(page)
  await drawer.getByRole('button', { name: '将所选原文填入正文', exact: true }).click()
  await page.getByRole('dialog', { name: '替换笔记正文' }).getByRole('button', { name: '保留当前正文', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue('保留原有理解。')
  await drawer.getByRole('button', { name: '将所选原文填入正文', exact: true }).click()
  await page.getByRole('dialog', { name: '替换笔记正文' }).getByRole('button', { name: '替换正文', exact: true }).click()
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(raw(messages.slice(2)))
  await uncheckAll(drawer)
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  expect(JSON.parse(state.note.sourceMessageIds)).toEqual([1, 2, 3, 4])
})
