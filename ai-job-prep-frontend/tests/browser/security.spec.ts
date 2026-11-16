import { test, expect, type Page } from '@playwright/test'

async function mockApi(page: Page, role = 'user') {
  const requests: { path: string; body: any }[] = []
  await page.route('**/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/features') { await route.fulfill({ json: { code: 200, data: { ragEnabled: true } } }); return }
    if (!path.startsWith('/api/')) { await route.continue(); return }
    let body: any = null
    try { body = request.postDataJSON() } catch { /* no JSON body */ }
    requests.push({ path, body })
    let data: unknown = []
    if (path === '/api/auth/me') data = { id: 1, username: 'test-user', role }
    if (path === '/api/chat/session') data = 10
    if (path === '/api/sessions') data = [{ id: 10, title: '测试会话', resumeId: null }]
    if (path === '/api/sessions/10/messages') data = [
      { role: 'user', content: '<img src=x onerror="window.xssFired=true">历史消息' },
      { role: 'assistant', content: '## 回答\n**安全内容**' },
    ]
    if (path === '/api/knowledge-bases') data = { records: [{ id: 7, name: 'Java知识库', description: '测试知识库' }], total: 1 }
    if (path === '/api/quiz/generate') data = [{ id: 8, question: 'Java测试题', options: { A: '正确', B: '错误' }, answer: 'A', explanation: '说明' }]
    if (path === '/api/agent/chat/stream') {
      await route.fulfill({ contentType: 'text/event-stream', body:
        'event: token\ndata: {"content":"<img src=x onerror=window.xssFired=true>流式回答"}\n\nevent: status\ndata: {"status":"completed"}\n\n' })
      return
    }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify({ code: 200, data }) })
  })
  // Tests never request remote fonts or external application services.
  await page.route('https://api.fontshare.com/**', route => route.abort())
  return requests
}

test('normal users see private knowledge management but not global admin tools', async ({ page }) => {
  await mockApi(page)
  await page.goto('/ask')
  await expect(page.getByRole('link', { name: '知识库管理' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'ES 管理' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '检索配置' })).toHaveCount(0)
})

test('administrators retain global admin navigation', async ({ page }) => {
  await mockApi(page, 'admin')
  await page.goto('/ask')
  await expect(page.getByRole('link', { name: 'ES 管理' })).toBeVisible()
  await expect(page.getByRole('link', { name: '检索配置' })).toBeVisible()
})

test('restored, user-entered and streamed chat HTML cannot execute script', async ({ page }) => {
  await mockApi(page)
  await page.addInitScript(() => localStorage.setItem('rag_session_id:1', '10'))
  await page.goto('/ask')
  await expect(page.locator('.msg-bub').first()).toContainText('历史消息')
  await expect(page.locator('.msg-bub h2')).toHaveText('回答')
  await page.locator('textarea').fill('<img src=x onerror="window.xssFired=true">新消息')
  await page.locator('textarea').press('Enter')
  await expect(page.locator('.msg-bub').last()).toContainText('流式回答')
  expect(await page.evaluate(() => (window as any).xssFired)).toBeUndefined()
  await expect(page.locator('.msg-bub [onerror]')).toHaveCount(0)
})

test('legacy shared session storage is not reused across accounts', async ({ page }) => {
  const requests = await mockApi(page)
  await page.addInitScript(() => localStorage.setItem('rag_session_id', '999'))
  await page.goto('/ask')
  await expect(page.locator('.sess-id')).toContainText('10')
  expect(requests.some(request => request.path === '/api/sessions/999/messages')).toBe(false)
  expect(await page.evaluate(() => localStorage.getItem('rag_session_id:1'))).toBe('10')
})

test('knowledge selection submits numeric IDs and proceeds to quiz', async ({ page }) => {
  const requests = await mockApi(page)
  await page.goto('/quiz')
  await page.getByText('Java知识库', { exact: true }).click()
  await expect(page.getByText(/已选 1 个知识库/)).toBeVisible()
  await page.locator('button').filter({ hasText: /开始/ }).click()
  await expect(page.getByText('Java测试题', { exact: true })).toBeVisible()
  expect(requests.find(request => request.path === '/api/quiz/generate')?.body.kbIds).toEqual([7])
})

async function openControlledChat(page: Page) {
  await mockApi(page)
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window)
    window.fetch = async (input, init) => {
      if (String(input) !== '/api/agent/chat/stream') return originalFetch(input, init)
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          ;(window as any).chatStream = controller
          init?.signal?.addEventListener('abort', () => controller.error(new DOMException('Stopped', 'AbortError')))
        },
      })
      return new Response(stream, { headers: { 'Content-Type': 'text/event-stream' } })
    }
  })
  await page.goto('/ask')
  await expect(page.locator('.sess-id')).toContainText('10')
  await page.locator('textarea').fill('什么是 CAP 定理')
  await page.locator('textarea').press('Enter')
  await expect(page.locator('.think-dots')).toBeVisible()
}

test('one assistant row transitions from waiting to streaming and completion', async ({ page }, testInfo) => {
  await openControlledChat(page)
  const assistant = page.locator('.msg.assistant')
  await expect(assistant).toHaveCount(1)
  await expect(assistant.locator('.msg-av')).toHaveCount(1)
  await expect(assistant.locator('.msg-bub')).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('waiting.png'), animations: 'disabled' })

  await page.evaluate(() => (window as any).chatStream.enqueue(new TextEncoder().encode(
    'event: token\ndata: {"content":"CAP 定理回答"}\n\n')))
  await expect(assistant.locator('.msg-bub')).toHaveText('CAP 定理回答')
  await expect(assistant).toHaveCount(1)
  await expect(page.locator('.think-dots')).toHaveCount(0)
  await expect(assistant.getByRole('button', { name: '停止生成' })).toBeVisible()
  await page.evaluate(() => {
    ;(window as any).chatStream.enqueue(new TextEncoder().encode('event: status\ndata: {"status":"completed"}\n\n'))
    ;(window as any).chatStream.close()
  })
  await expect(page.getByRole('button', { name: '停止生成' })).toHaveCount(0)
  await expect(assistant).toHaveCount(1)
  await expect(assistant.locator('.msg-bub')).toHaveText('CAP 定理回答')
  await expect(assistant.getByRole('button', { name: '重新提问' })).toHaveCount(0)
})

test('stopping before the first token leaves one nonempty assistant row', async ({ page }) => {
  await openControlledChat(page)
  await page.getByRole('button', { name: '停止生成' }).click()
  await expect(page.locator('.msg.assistant')).toHaveCount(1)
  await expect(page.locator('.msg.assistant .msg-bub')).toHaveText('已停止生成')
  await expect(page.locator('.think-dots')).toHaveCount(0)
  await expect(page.locator('textarea')).toBeEnabled()
})

test('a failed SSE connection replaces the pending reply instead of appending another', async ({ page }) => {
  await mockApi(page)
  await page.route('**/api/agent/chat/stream', route => route.fulfill({ status: 503, body: 'Unavailable' }))
  await page.goto('/ask')
  await expect(page.locator('.sess-id')).toContainText('10')
  await page.locator('textarea').fill('连接失败测试')
  await page.locator('textarea').press('Enter')
  await expect(page.locator('.msg.assistant')).toHaveCount(1)
  await expect(page.locator('.msg.assistant .msg-bub')).toContainText('503')
  await expect(page.locator('.think-dots')).toHaveCount(0)
})
