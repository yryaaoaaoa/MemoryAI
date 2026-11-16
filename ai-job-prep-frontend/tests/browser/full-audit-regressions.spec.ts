import { test, expect, type Page, type Route } from '@playwright/test'

async function ok(route: Route, data: unknown) {
  await route.fulfill({ json: { code: 200, data } })
}

async function mock(page: Page, handler: (route: Route, path: string) => Promise<boolean>) {
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/features') { await route.fulfill({ json: { code: 200, data: { ragEnabled: true } } }); return }
    if (!path.startsWith('/api/')) return route.continue()
    if (await handler(route, path)) return
    await ok(route, path === '/api/auth/me' ? { id: 1, username: 'audit', role: 'admin' }
      : path === '/api/chat/session' ? 10 : [])
  })
}

test('legacy malformed profiles stay readable and a valid profile still renders', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', e => errors.push(e.message))
  let profileJson: unknown = null
  await mock(page, async (route, path) => {
    if (path !== '/api/user/profile') return false
    await ok(route, { techStack: [], hasResume: false, totalQuizzes: 5, correctRate: 80, streak: 2, wrongQuestionCount: 1,
      skillProfiles: [{ skillId: 'java-backend', skillName: 'Java', interviewCount: 2, profileJson }] })
    return true
  })
  for (const malformed of [null, [], { categories: [null] }]) {
    profileJson = malformed
    await page.goto('/profile')
    await expect(page.getByRole('alert')).toHaveText('画像暂不可用，完成一次面试后可重新生成。')
    await expect(page.locator('.pf-cat-card')).toHaveCount(0)
  }
  profileJson = { summary: '掌握 Java 基础', categories: [{ category: '并发', level: 'weak', text: '需要练习锁的选择' }], nextAdvice: '完成并发专题练习' }
  await page.reload()
  await expect(page.locator('.pf-summary')).toHaveText('掌握 Java 基础')
  await expect(page.locator('.pf-cat-card')).toContainText('需要练习锁的选择')
  await expect(page.locator('.pf-advice')).toContainText('完成并发专题练习')
  expect(errors).toEqual([])
})

test('restored stopped turn retries its own question even after another completed turn', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('rag_session_id:1', '10'))
  let sent = ''
  await mock(page, async (route, path) => {
    if (path === '/api/sessions/10/messages') {
      await ok(route, [
        { role: 'user', content: '第一个问题' },
        { role: 'assistant', content: '已停止生成', metadata: '{"response_status":"stopped"}' },
        { role: 'user', content: '第二个问题' },
        { role: 'assistant', content: '第二个回答', metadata: '{"response_status":"completed"}' },
      ])
      return true
    }
    if (path === '/api/agent/chat/stream') {
      sent = route.request().postDataJSON().message
      await route.fulfill({ contentType: 'text/event-stream', body: 'event: token\ndata: {"content":"重新回答第一个问题"}\n\nevent: status\ndata: {"status":"completed"}\n\n' })
      return true
    }
    return false
  })
  await page.goto('/ask')
  await page.getByRole('button', { name: '重新提问', exact: true }).click()
  await expect(page.getByText('重新回答第一个问题', { exact: true })).toBeVisible()
  expect(sent).toBe('第一个问题')
})

test('restored JSON arrays and interrupted response metadata stay visible', async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem('rag_session_id:1', '10'))
  await mock(page, async (route, path) => {
    if (path === '/api/sessions/10/messages') {
      await ok(route, [
        { role: 'user', content: '列出数组' },
        { role: 'assistant', content: '["Java","Redis"]' },
        { role: 'user', content: '继续说明' },
        { role: 'assistant', content: '已经生成的内容', metadata: '{"response_status":"stopped"}' },
      ])
      return true
    }
    return false
  })
  await page.goto('/ask')
  await expect(page.locator('.msg-bub').nth(1)).toHaveText('["Java","Redis"]')
  await expect(page.getByText('已经生成的内容', { exact: true })).toBeVisible()
  await expect(page.getByText('已停止生成', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '重新提问' })).toBeVisible()
})

test('IME Enter does not send; partial SSE failure retains content and can be retried', async ({ page }) => {
  let calls = 0
  await mock(page, async (route, path) => {
    if (path !== '/api/agent/chat/stream') return false
    calls++
    await route.fulfill({ contentType: 'text/event-stream', body: calls === 1
      ? 'event: token\ndata: {"content":"部分答案"}\n\nevent: error\ndata: {"status":"truncated","message":"输出达到长度限制"}\n\n'
      : 'event: token\ndata: {"content":"完整答案"}\n\nevent: status\ndata: {"status":"completed"}\n\n' })
    return true
  })
  await page.goto('/ask')
  const input = page.locator('textarea')
  await expect(input).toBeEnabled()
  await input.fill('解释事务')
  await input.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, keyCode: 229 })
  await expect(input).toHaveValue('解释事务')
  expect(calls).toBe(0)
  await input.press('Enter')
  await expect(page.getByText('部分答案', { exact: true })).toBeVisible()
  await expect(page.locator('.response-status')).toContainText('输出达到长度限制')
  await page.getByRole('button', { name: '重新提问' }).click()
  await expect(page.getByText('完整答案', { exact: true })).toBeVisible()
  expect(calls).toBe(2)
})

test('EOF without completed status preserves partial output as failed and offers retry', async ({ page }) => {
  let calls = 0
  await mock(page, async (route, path) => {
    if (path !== '/api/agent/chat/stream') return false
    calls++
    await route.fulfill({ contentType: 'text/event-stream', body: calls === 1
      ? 'event: token\ndata: {"content":"意外断流前的答案"}\n\n'
      : 'event: token\ndata: {"content":"重新生成的完整答案"}\n\nevent: status\ndata: {"status":"completed"}\n\n' })
    return true
  })
  await page.goto('/ask')
  await expect(page.locator('textarea')).toBeEnabled()
  await page.locator('textarea').fill('解释事务')
  await page.locator('textarea').press('Enter')
  const interrupted = page.locator('.msg.assistant').first()
  await expect(interrupted.locator('.msg-bub')).toHaveText('意外断流前的答案')
  await expect(interrupted.locator('.response-status')).toHaveText('连接已中断，回答未完成，请重新提问')
  await expect(page.getByRole('button', { name: '停止生成' })).toHaveCount(0)
  await interrupted.getByRole('button', { name: '重新提问' }).click()
  await expect(page.locator('.msg.assistant').last().locator('.msg-bub')).toHaveText('重新生成的完整答案')
  await expect(page.locator('.msg.assistant').last().getByRole('button', { name: '重新提问' })).toHaveCount(0)
  expect(calls).toBe(2)
})

test('stopping a partial response retains its text and marks it stopped', async ({ page }) => {
  await mock(page, async () => false)
  await page.addInitScript(() => {
    const originalFetch = window.fetch.bind(window)
    window.fetch = (input, init) => {
      if (!String(input).endsWith('/api/agent/chat/stream')) return originalFetch(input, init)
      return Promise.resolve(new Response(new ReadableStream({
        start(controller) {
          controller.enqueue(new TextEncoder().encode('event: token\ndata: {"content":"停止前已收到的答案"}\n\n'))
          init?.signal?.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true })
        },
      }), { headers: { 'Content-Type': 'text/event-stream' } }))
    }
  })
  await page.goto('/ask')
  await expect(page.locator('textarea')).toBeEnabled()
  await page.locator('textarea').fill('解释事务')
  await page.locator('textarea').press('Enter')
  await expect(page.getByText('停止前已收到的答案', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '停止生成', exact: true }).click()
  await expect(page.getByText('停止前已收到的答案', { exact: true })).toBeVisible()
  await expect(page.getByText('已停止生成', { exact: true })).toBeVisible()
  await expect(page.locator('textarea')).toBeEnabled()
})

test('settings cannot save defaults after a failed read and can reload server values', async ({ page }) => {
  let reads = 0
  let saved: any
  const config = { rankConstant: 91, numCandidatesFactor: 2, rankWindowFactor: 2,
    bm25Fields: 'content^5', shortQueryMaxLen: 4, mediumQueryMaxLen: 12,
    shortQuery: { topK: 20, minScore: 0.25 }, mediumQuery: { topK: 12, minScore: 0.28 },
    longQuery: { topK: 8, minScore: 0.28 }, defaults: { topK: 3, minScore: 0.2 } }
  await mock(page, async (route, path) => {
    if (path !== '/api/settings/retrieval') return false
    if (route.request().method() === 'PUT') { saved = route.request().postDataJSON(); await ok(route, null) }
    else if (++reads === 1) await route.fulfill({ status: 503, body: 'Unavailable' })
    else await ok(route, config)
    return true
  })
  await page.goto('/admin/settings')
  await expect(page.getByRole('alert').filter({ hasText: '无法读取当前配置' })).toBeVisible()
  await expect(page.getByRole('button', { name: '保存配置' })).toBeDisabled()
  expect(saved).toBeUndefined()
  await page.getByRole('button', { name: '重试加载' }).click()
  await expect(page.getByRole('button', { name: '保存配置' })).toBeEnabled()
  await page.getByRole('button', { name: '保存配置' }).click()
  await expect.poll(() => saved).toEqual(config)
})

test('quiz includes knowledge bases beyond the first page', async ({ page }) => {
  const pages: number[] = []
  await mock(page, async (route, path) => {
    if (path !== '/api/knowledge-bases') return false
    const number = Number(new URL(route.request().url()).searchParams.get('page'))
    pages.push(number)
    const records = number === 1 ? Array.from({ length: 100 }, (_, i) => ({ id: i + 1, name: `知识库 ${i + 1}` }))
      : [{ id: 101, name: '最后一页知识库' }]
    await ok(route, { records, total: 101 })
    return true
  })
  await page.goto('/quiz')
  await expect(page.getByText('最后一页知识库', { exact: true })).toBeVisible()
  expect(pages).toEqual([1, 2])
})

test('failed logout preserves the local session and retry can log out', async ({ page }) => {
  let attempts = 0
  await mock(page, async (route, path) => {
    if (path !== '/api/auth/logout') return false
    if (++attempts === 1) await route.fulfill({ status: 503, body: 'Unavailable' })
    else await ok(route, null)
    return true
  })
  await page.goto('/ask')
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page.locator('.el-message--error')).toBeVisible()
  await expect(page).toHaveURL(/\/ask(?:\?sessionId=\d+)?$/)
  await expect(page.getByText('audit', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '退出', exact: true }).click()
  await expect(page).toHaveURL(/\/login$/)
})

test('registration followed by failed automatic login leaves protected pages unauthenticated', async ({ page }) => {
  await mock(page, async (route, path) => {
    if (path === '/api/auth/me') await route.fulfill({ status: 401, json: { code: 401, message: '未登录' } })
    else if (path === '/api/auth/register') await ok(route, { id: 9, username: 'newaccount', role: 'user' })
    else if (path === '/api/auth/login') await route.fulfill({ status: 503, json: { code: 503, message: '登录服务暂不可用' } })
    else return false
    return true
  })
  await page.goto('/login')
  await page.getByRole('button', { name: '立即注册' }).click()
  await page.getByLabel('用户名').fill('newaccount')
  await page.getByLabel('密码').fill('test-password')
  await page.getByRole('button', { name: '创建账号', exact: true }).click()
  await expect(page.getByRole('button', { name: '创建账号', exact: true })).toBeEnabled()
  // Navigate through the live router so an incorrectly cached logged-in user is observable.
  await page.evaluate(async () => { await (document.querySelector('#app') as any).__vue_app__.config.globalProperties.$router.push('/ask') })
  await expect(page).toHaveURL(/\/login$/)
})

test('failed report evaluation retries once and pending evaluation polls to completion', async ({ page }) => {
  let retries = 0
  let pendingReads = 0
  await mock(page, async (route, path) => {
    if (path.endsWith('/retry-evaluation')) { retries++; await ok(route, null) }
    else if (path.endsWith('/report')) await ok(route, retries === 0
      ? { ready: false, status: 'FAILED', error: '上次评估因服务重启中断，请重新评估' }
      : ++pendingReads === 1 ? { ready: false, status: 'PENDING' }
        : { sessionId: 'test', overallScore: 80, totalQuestions: 1, overallFeedback: '恢复完成', strengths: [], improvements: [], categoryScores: [], questionDetails: [], referenceAnswers: [] })
    else return false
    return true
  })
  await page.goto('/interview/report/test')
  await expect(page.getByText('上次评估因服务重启中断，请重新评估')).toBeVisible()
  await page.getByRole('button', { name: '重新评估', exact: true }).click()
  await expect(page.getByText('评估进行中，自动刷新中...')).toBeVisible()
  await expect(page.getByRole('button', { name: '重新评估', exact: true })).toHaveCount(0)
  await expect(page.locator('.rpt-num')).toHaveText('80', { timeout: 8000 })
  expect(retries).toBe(1)
})

test('late interview session load does not overwrite the chosen session or typed answer', async ({ page }) => {
  let release!: () => void
  const delayed = new Promise<void>(resolve => { release = resolve })
  await mock(page, async (route, path) => {
    if (path === '/api/interview/sessions') await ok(route, ['A', 'B'].map(sessionId => ({ sessionId, status: 'IN_PROGRESS', skillId: sessionId, totalQuestions: 1, difficulty: 'mid' })))
    else if (/\/api\/interview\/sessions\/[AB]$/.test(path)) {
      const id = path.slice(-1)
      if (id === 'A') await delayed
      await ok(route, { sessionId: id, status: 'IN_PROGRESS', totalQuestions: 1, currentQuestionIndex: 0,
        questions: [{ questionIndex: 0, question: `问题 ${id}`, category: 'Java', type: 'JAVA', userAnswer: null }] })
    } else return false
    return true
  })
  try {
    await page.goto('/interview')
    await page.getByRole('button', { name: '继续', exact: true }).nth(0).click()
    await page.getByRole('button', { name: '继续', exact: true }).nth(1).click()
    await expect(page.locator('.ans-ta')).toBeVisible()
    await page.locator('.ans-ta').fill('B 会话的新答案')
    const response = page.waitForResponse('**/api/interview/sessions/A')
    release()
    await response
    await expect(page.locator('.ans-ta')).toHaveValue('B 会话的新答案')
    await expect(page.getByText('问题 B', { exact: true })).toBeVisible()
  } finally { release() }
})

for (const type of ['chunks', 'es'] as const) {
  test(`${type} detail ignores an old response after closing and opening another document`, async ({ page }) => {
    let release!: () => void
    const delayed = new Promise<void>(resolve => { release = resolve })
    const oldPath = type === 'chunks' ? '/api/documents/A/chunks' : '/api/admin/es/indices/test/docs/A'
    await mock(page, async (route, path) => {
      if (path === oldPath || path === oldPath.replace('/A', '/B')) {
        const old = path === oldPath
        if (old) await delayed
        await ok(route, type === 'chunks' ? [{ id: old ? 'A' : 'B', content: old ? '过期内容 A' : '最新内容 B' }]
          : { content: old ? '过期内容 A' : '最新内容 B' })
      } else if (path.endsWith('/documents')) await ok(route, { records: ['A', 'B'].map(id => ({ id, fileName: `文档 ${id}`, title: `文档 ${id}`, status: 'READY', fileSize: 100, chunkCount: 1 })), total: 2 })
      else if (path.endsWith('/docs')) await ok(route, { records: ['A', 'B'].map(id => ({ id, source: { content: `文档 ${id}` } })), total: 2 })
      else if (path.endsWith('/mapping')) await ok(route, {})
      else if (path === '/api/knowledge-bases/1') await ok(route, { id: 1, name: '测试库' })
      else if (path === '/api/admin/es/indices/test') await ok(route, { health: 'green', docCount: 2, storageSizeBytes: 100 })
      else return false
      return true
    })
    try {
      await page.goto(type === 'chunks' ? '/admin/knowledge-bases/1' : '/admin/es/test')
      const buttons = page.getByRole('button', { name: type === 'chunks' ? '切片' : '详情', exact: true })
      await buttons.nth(0).click()
      await page.getByRole('dialog').locator('.el-dialog__headerbtn').click()
      await buttons.nth(1).click()
      await expect(page.getByRole('dialog')).toContainText('最新内容 B')
      const response = page.waitForResponse(`**${oldPath}`)
      release()
      await response
      await expect(page.getByRole('dialog')).toContainText('最新内容 B')
      await expect(page.getByRole('dialog')).not.toContainText('过期内容 A')
    } finally { release() }
  })
}

test('ES index route changes refresh the same component and clear confirmation uses a number', async ({ page }) => {
  await mock(page, async (route, path) => {
    if (path.endsWith('/docs')) await ok(route, { records: [{ id: path.includes('/second/') ? 'second-doc' : 'first-doc', source: {}, score: null }], total: 7 })
    else if (path.endsWith('/mapping')) await ok(route, {})
    else if (path.startsWith('/api/admin/es/indices/')) await ok(route, { health: 'green', docCount: 7, storageSizeBytes: 100 })
    else return false
    return true
  })
  await page.goto('/admin/es/first')
  await expect(page.getByText('first-doc', { exact: true })).toBeVisible()
  await page.evaluate(async () => { await (document.querySelector('#app') as any).__vue_app__.config.globalProperties.$router.push('/admin/es/second') })
  await expect(page.getByRole('heading', { name: 'second', exact: true })).toBeVisible()
  await expect(page.getByText('second-doc', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '清空文档', exact: true }).click()
  await expect(page.locator('.el-message-box')).toContainText('全部 7 条文档')
  await expect(page.locator('.el-message-box')).not.toContainText('[object Object]')
})
