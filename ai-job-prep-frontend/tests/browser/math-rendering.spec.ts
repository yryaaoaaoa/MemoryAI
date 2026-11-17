import { test, expect, type Page, type Locator } from '@playwright/test'

const formulas = [
  String.raw`行内公式：$E=mc^2$，勾股定理：\(a^2+b^2=c^2\)。`,
  '$$', String.raw`\int_0^1 x^2\,dx=\frac{1}{3}`, '$$',
  String.raw`\[`, String.raw`\sum_{i=1}^{n}i=\frac{n(n+1)}{2}`, String.raw`\]`,
  '行内代码：`$x$` 和 `\\(y\\)`。',
  '```tex', String.raw`$$z^2$$ \[w\]`, '```',
].join('\n\n')

async function setup(page: Page, content = formulas) {
  const messages = [
    { id: 1, sessionId: 10, role: 'user', content: '解释这些数学公式。' },
    { id: 2, sessionId: 10, role: 'assistant', content, metadata: '{"response_status":"completed"}' },
  ]
  const common = { version: 0, title: '数学公式与推导', confusion: '', status: 'REVIEW', sourceSessionId: 10,
    sourceMessageIds: '[1,2]', sourceSnapshot: JSON.stringify(messages), updatedAt: '2026-11-17T10:00:00' }
  const notes: Record<number, typeof common & { id: number; content: string; treeJson: string | null }> = {
    5: { ...common, id: 5, content, treeJson: null },
    6: { ...common, id: 6, content: `## 公式与代码\n\n${content}`, treeJson: JSON.stringify({ version: 1,
      nodes: [{ id: 'math-node', title: '公式与代码', body: content, children: [] }] }) },
  }
  let openedNote: number | null = null
  const writes: string[] = []
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'math-test', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/sessions') return ok([{ id: 10, title: '数学推导' }])
    if (path === '/api/chat/session') return ok(10)
    if (path === '/api/sessions/10/messages') return ok(messages)
    if (path === '/api/sessions/10/learning-tree') return ok({ note: openedNote ? notes[openedNote] : null, status: 'unchanged', nodeId: null, messageId: 4, error: null })
    const opening = path.match(/^\/api\/learning-notes\/(\d+)\/open$/)
    if (opening) { openedNote = Number(opening[1]); return ok(10) }
    if (path === '/api/sessions/10/learning-tree/nodes/math-node') return ok({ nodeId: 'math-node', title: '公式与代码', messages })
    if (path.endsWith('/suggestions')) return ok({ questions: [] })
    if (route.request().method() === 'PUT' || path.endsWith('/draft')) writes.push(path)
    if (path === '/api/learning-notes') return ok({ records: Object.values(notes), total: 2 })
    const id = Number(path.match(/^\/api\/learning-notes\/(\d+)$/)?.[1])
    if (notes[id]) {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON()
        notes[id] = { ...notes[id], ...body, version: notes[id].version + 1 }
      }
      return ok(notes[id])
    }
    return ok([])
  })
  return { notes, messages, writes }
}

async function expectFourMathForms(container: Locator) {
  await expect(container.locator('.katex')).toHaveCount(4)
  await expect(container.locator('.katex-display')).toHaveCount(2)
  await expect(container.locator('pre code')).toContainText(String.raw`$$z^2$$ \[w\]`)
  await expect(container.locator('code .katex')).toHaveCount(0)
  await expect(container.locator('code').filter({ hasText: '$x$' })).toHaveCount(1)
  await expect(container.locator('code').filter({ hasText: String.raw`\(y\)` })).toHaveCount(1)
}

test('chat history and incremental completion render all four math forms while incomplete input stays readable', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await setup(page)
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window)
    window.fetch = async (input, init) => {
      if (String(input) !== '/api/agent/chat/stream') return originalFetch(input, init)
      const stream = new ReadableStream<Uint8Array>({
        start(controller) { (window as any).mathStream = controller },
      })
      return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
    }
  })
  await page.goto('/ask?sessionId=10')
  await expectFourMathForms(page.locator('.msg.assistant .msg-bub').first())
  await page.getByRole('textbox', { name: '对话内容' }).fill('继续推导。')
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
  await expect(page.getByRole('button', { name: '停止生成', exact: true })).toBeVisible()
  const split = formulas.indexOf('mc') + 1
  await page.evaluate(part => (window as any).mathStream.enqueue(new TextEncoder().encode(
    `event: token\ndata: ${JSON.stringify({ content: part })}\n\n`)), formulas.slice(0, split))
  const streamed = page.locator('.msg.assistant .msg-bub').last()
  await expect(streamed).toContainText('$E=m')
  await expect(streamed.locator('.katex')).toHaveCount(0)
  const suffix = formulas.slice(split) + '\n\n' + String.raw`无效公式：$\frac{1}{$`
  await page.evaluate(part => {
    ;(window as any).mathStream.enqueue(new TextEncoder().encode(
      `event: token\ndata: ${JSON.stringify({ content: part })}\n\nevent: status\ndata: {"status":"completed","messageId":4}\n\n`))
    ;(window as any).mathStream.close()
  }, suffix)
  await expect(page.getByRole('button', { name: '停止生成', exact: true })).toHaveCount(0)
  await expectFourMathForms(streamed)
  await expect(streamed).toContainText(String.raw`\frac{1}{`)
  expect(errors).toEqual([])
})

test('legacy notes and original question nodes render math without rewriting saved content', async ({ page }) => {
  const { notes, writes } = await setup(page)
  const before = JSON.stringify(notes)
  for (const id of [5, 6]) {
    await page.goto(`/notes/${id}`)
    await expect(page).toHaveURL(/sessionId=10/)
    await page.getByRole('button', { name: '历史笔记', exact: true }).click()
    await expectFourMathForms(page.locator('.history-note-content'))
    await expect(page.getByRole('button', { name: /编辑笔记|保存修改|整理正文/ })).toHaveCount(0)
  }
  await page.getByRole('complementary', { name: '问题目录' }).locator('.tree-node').click()
  await expectFourMathForms(page.getByRole('region', { name: '原问答回看' }).locator('.source-message.assistant'))
  await expect(page.getByRole('region', { name: '历史笔记' })).toHaveCount(0)
  expect(JSON.stringify(notes)).toBe(before)
  expect(writes).toEqual([])
})

test('long display math scrolls within chat and note content on mobile with loaded KaTeX fonts', async ({ page }) => {
  const longFormula = '$$\n' + Array.from({ length: 20 }, (_, index) =>
    String.raw`\frac{a_{${index + 1}}+b_{${index + 1}}}{\sqrt{c_{${index + 1}}^2+d_{${index + 1}}^2}}`).join('+') + '\n$$'
  await setup(page, formulas + '\n\n长公式：\n\n' + longFormula)
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/notes/6')
  await page.getByRole('button', { name: '历史笔记', exact: true }).click()
  await expect(page.locator('.history-note-content .katex')).toHaveCount(5)
  await page.evaluate(() => document.fonts.ready)
  expect(await page.evaluate(() => Array.from(document.fonts).some(font =>
    font.family.replace(/["']/g, '') === 'KaTeX_Main' && font.status === 'loaded'))).toBe(true)
  await page.screenshot({ path: test.info().outputPath('math-desktop.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  for (const [path, selector] of [
    ['/ask?sessionId=10', '.msg.assistant .msg-bub'],
    ['/notes/5', '.history-note-content'],
    ['/notes/6', '.history-note-content'],
  ]) {
    await page.goto(path!)
    if (path!.startsWith('/notes')) await page.getByRole('button', { name: '历史笔记', exact: true }).click()
    const display = page.locator(selector!).locator('.katex-display').last()
    await expect(display).toBeVisible()
    await page.evaluate(() => document.fonts.ready)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), path).toBe(true)
    expect(await display.evaluate(element => {
      // Allow a dedicated math wrapper, but not a page-level scroll container.
      const boundary = element.closest('.msg-bub, .history-note-content')
      for (let node: HTMLElement | null = element as HTMLElement; node && node !== boundary; node = node.parentElement) {
        if (node.scrollWidth > node.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(node).overflowX)) {
          node.scrollLeft = 40
          const scrolls = node.scrollLeft > 0
          node.scrollLeft = 0
          return scrolls
        }
      }
      return false
    }), path).toBe(true)
  }
  await page.screenshot({ path: test.info().outputPath('math-mobile.png'), fullPage: true, animations: 'disabled' })
})
