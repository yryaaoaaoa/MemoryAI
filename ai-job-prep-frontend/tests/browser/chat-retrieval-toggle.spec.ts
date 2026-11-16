import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, ragEnabled = true) {
  const state = { requests: [] as any[], hold: false, release: () => {} }
  let sessionId = 10
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'retrieval-test', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled })
    if (path === '/api/chat/session') return ok(++sessionId)
    if (path === '/api/sessions') return ok([{ id: sessionId, title: '知识点学习' }])
    if (path === '/api/agent/chat/stream') {
      state.requests.push(route.request().postDataJSON())
      if (state.hold) await new Promise<void>(resolve => { state.release = resolve })
      return route.fulfill({ contentType: 'text/event-stream', body: 'event: token\ndata: {"content":"学习回答"}\n\nevent: status\ndata: {"status":"completed","messageId":2}\n\n' })
    }
    return ok([])
  })
  await page.goto('/ask')
  await expect(page.getByRole('textbox', { name: '对话内容' })).toBeEnabled()
  return state
}

async function send(page: Page, text: string) {
  await page.getByRole('textbox', { name: '对话内容' }).fill(text)
  await page.getByRole('button', { name: '发送消息', exact: true }).click()
}

test('ordinary questions default to direct answers and each request follows the switch', async ({ page }) => {
  const state = await setup(page)
  const toggle = page.getByRole('switch', { name: '知识库检索', exact: true })
  await expect(toggle).not.toBeChecked()
  await send(page, '什么是线程池？')
  await expect(toggle).toBeEnabled()
  expect(state.requests[0].useKnowledgeBase).toBe(false)
  await toggle.click()
  await expect(toggle).toBeChecked()
  state.hold = true
  await send(page, '结合知识库讲一下线程池')
  await expect(toggle).toBeDisabled()
  await expect.poll(() => state.requests.length).toBe(2)
  expect(state.requests[1].useKnowledgeBase).toBe(true)
  state.release(); state.hold = false
  await expect(toggle).toBeEnabled()
  await toggle.click()
  await send(page, '再解释一下概念')
  await expect(toggle).toBeEnabled()
  expect(state.requests[2].useKnowledgeBase).toBe(false)
})

test('new conversations and reloads start with retrieval off; phone layout and keyboard toggle work', async ({ page }) => {
  await setup(page)
  const toggle = page.getByRole('switch', { name: '知识库检索', exact: true })
  await toggle.focus()
  await page.keyboard.press('Space')
  await expect(toggle).toBeChecked()
  await page.getByRole('button', { name: '+ 新对话', exact: true }).click()
  await expect(toggle).toBeEnabled()
  await expect(toggle).not.toBeChecked()
  await toggle.click()
  await page.reload()
  await expect(toggle).not.toBeChecked()
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(toggle).toBeVisible()
  await expect(page.getByRole('textbox', { name: '对话内容' })).toBeVisible()
  expect(await page.locator('.main').evaluate(el => el.scrollWidth <= el.clientWidth + 1)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('retrieval-toggle-mobile.png'), animations: 'disabled' })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await toggle.click()
  await page.screenshot({ path: test.info().outputPath('retrieval-toggle-desktop.png'), animations: 'disabled' })
})

test('globally disabled knowledge retrieval hides the switch and still sends direct questions', async ({ page }) => {
  const state = await setup(page, false)
  await expect(page.getByRole('switch', { name: '知识库检索', exact: true })).toHaveCount(0)
  await send(page, '讲一下事务')
  await expect(page.getByRole('textbox', { name: '对话内容' })).toBeEnabled()
  expect(state.requests[0].useKnowledgeBase).toBe(false)
})
