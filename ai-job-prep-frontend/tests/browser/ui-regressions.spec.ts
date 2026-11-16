import { test, expect, type Page, type Locator } from '@playwright/test'

async function mockUi(page: Page, theme: 'dark' | 'light', authenticated = true) {
  let loggedIn = authenticated
  await page.addInitScript(value => {
    localStorage.setItem('theme', value)
    localStorage.setItem('rag_session_id:1', '10')
  }, theme)
  await page.route('https://api.fontshare.com/**', route => route.abort())
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/features') { await route.fulfill({ json: { code: 200, data: { ragEnabled: true } } }); return }
    if (!path.startsWith('/api/')) { await route.continue(); return }
    let data: unknown = []
    if (path === '/api/auth/logout') loggedIn = false
    if (path === '/api/auth/me') {
      if (!loggedIn) {
        await route.fulfill({ status: 401, json: { code: 401, message: '未登录' } })
        return
      }
      data = { id: 1, username: 'ui-review', role: 'admin' }
    }
    if (path === '/api/chat/session') data = 10
    if (path === '/api/sessions') data = [{ id: 10, title: '界面验证会话', updatedAt: '2026-11-12' }]
    if (path === '/api/sessions/10/messages') data = [
      { role: 'user', content: '什么是 CAP 定理？' },
      { role: 'assistant', content: '## CAP 定理\n分区发生时，需要在一致性和可用性之间取舍。\n```java\nSystem.out.println("CAP");\n```' },
    ]
    if (path === '/api/resume/list') data = [{ id: '1', fileName: '工程师简历.md', status: 'READY', fileSize: 100,
      structuredJson: JSON.stringify({ sections: [{ type: 'skills', content: 'Vue、TypeScript', tags: ['Vue'] }] }) }]
    if (path === '/api/knowledge-bases') data = { records: [{ id: 1, name: 'Java 并发', description: '线程池与并发容器' }], total: 1 }
    if (path === '/api/quiz/generate') data = [{ id: 1, question: '选择正确的并发工具', options: { A: '线程池', B: '字符串' }, answer: 'A' }]
    if (path === '/api/agent/chat/stream') {
      await route.fulfill({ contentType: 'text/event-stream', body: 'event: token\ndata: {"content":"键盘发送成功"}\n\nevent: status\ndata: {"status":"completed"}\n\n' })
      return
    }
    await route.fulfill({ json: { code: 200, data } })
  })
}

async function fitsViewport(locator: Locator, page: Page) {
  await expect(locator).toBeVisible()
  const box = await locator.boundingBox()
  expect(box).not.toBeNull()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width).toBeLessThanOrEqual(page.viewportSize()!.width + 1)
}

function contrast(foreground: string, background: string) {
  const luminance = (color: string) => {
    const values = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(channel => {
      const value = channel / 255
      return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
    })
    return values[0]! * 0.2126 + values[1]! * 0.7152 + values[2]! * 0.0722
  }
  const a = luminance(foreground), b = luminance(background)
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
}

for (const theme of ['dark', 'light'] as const) {
  test(`${theme}: readable chat, mobile reflow and keyboard session selection`, async ({ page }, testInfo) => {
    await mockUi(page, theme)
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/ask')
    await expect(page.locator('.msg.assistant')).toBeVisible()
    const colors = await page.evaluate(() => {
      const bubble = getComputedStyle(document.querySelector('.msg.user .msg-bub')!)
      return { text: bubble.color, bubble: bubble.backgroundColor,
        muted: getComputedStyle(document.querySelector('.sess-id')!).color,
        root: getComputedStyle(document.body).backgroundColor }
    })
    expect(contrast(colors.text, colors.bubble)).toBeGreaterThanOrEqual(4.5)
    expect(contrast(colors.muted, colors.root)).toBeGreaterThanOrEqual(4.5)
    await page.screenshot({ path: testInfo.outputPath('desktop-chat.png') })
    for (const width of [768, 390, 320]) {
      await page.setViewportSize({ width, height: 844 })
      await expect(page.locator('aside.sb')).toHaveClass(/sb-fold/)
      await fitsViewport(page.locator('.inp-pill'), page)
      await fitsViewport(page.locator('.msg.assistant .msg-bub'), page)
      expect((await page.locator('.inp-pill').boundingBox())!.width).toBeGreaterThan(width - 100)
      const overflow = await page.locator('.main').evaluate(el => el.scrollWidth > el.clientWidth + 1)
      expect(overflow).toBe(false)
    }
    await page.screenshot({ path: testInfo.outputPath('mobile-chat.png') })
    await expect(page.getByRole('link', { name: '模拟面试', exact: true })).toHaveAccessibleName('模拟面试')
    await page.getByRole('button', { name: '展开导航', exact: true }).click()
    await expect(page.getByRole('link', { name: 'AI 问答', exact: true })).toBeVisible()
    await page.locator('aside.sb').press('Escape')
    await expect(page.locator('aside.sb')).toHaveClass(/sb-fold/)
    await page.getByRole('button', { name: '打开简历侧栏' }).click()
    await fitsViewport(page.locator('.ask-sidebar'), page)
    const resume = page.getByRole('button', { name: '工程师简历.md' })
    await resume.focus()
    await page.keyboard.press('Enter')
    await expect(resume).toHaveAttribute('aria-pressed', 'true')
    await page.getByRole('button', { name: '收起简历' }).click()
    await page.getByRole('button', { name: '切换', exact: true }).click()
    await fitsViewport(page.locator('.el-dialog'), page)
    await page.getByRole('button', { name: /界面验证会话/ }).focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('.el-dialog')).toBeHidden()
    await page.getByRole('textbox', { name: '对话内容' }).fill('键盘测试')
    await page.keyboard.press('Tab')
    await expect(page.getByRole('button', { name: '发送消息' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.locator('.msg-bub').last()).toContainText('键盘发送成功')
  })

  test(`${theme}: mobile quiz configuration and answer work by keyboard`, async ({ page }, testInfo) => {
    await mockUi(page, theme)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/quiz')
    await page.locator('.kb-card').focus()
    await page.keyboard.press('Space')
    await expect(page.getByText(/已选 1 个知识库/)).toBeVisible()
    await fitsViewport(page.locator('.cfg'), page)
    await page.screenshot({ path: testInfo.outputPath('mobile-quiz.png'), fullPage: true })
    await page.getByRole('button', { name: /开始刷题/ }).click()
    await page.locator('.q-opt').first().focus()
    await page.keyboard.press('Space')
    await expect(page.locator('.q-opt').first()).toHaveAttribute('aria-pressed', 'true')
    await fitsViewport(page.locator('.q-opt').first(), page)
    expect(await page.locator('.main').evaluate(el => el.scrollWidth > el.clientWidth + 1)).toBe(false)
  })

  test(`${theme}: login retains theme after logout and direct refresh`, async ({ page }, testInfo) => {
    await mockUi(page, theme)
    await page.goto('/ask')
    await page.getByRole('button', { name: '退出', exact: true }).click()
    await expect(page).toHaveURL(/\/login$/)
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await page.reload()
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    await expect(page.getByLabel('用户名', { exact: true })).toBeVisible()
    const colors = await page.locator('.auth-card').evaluate(el => ({
      background: getComputedStyle(el).backgroundColor,
      text: getComputedStyle(el.querySelector('.auth-title')!).color,
    }))
    expect(contrast(colors.text, colors.background)).toBeGreaterThanOrEqual(4.5)
    await page.setViewportSize({ width: 320, height: 568 })
    await fitsViewport(page.locator('.auth-card'), page)
    await page.getByRole('button', { name: '登录', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('button', { name: '登录', exact: true })).toBeInViewport()
    await page.screenshot({ path: testInfo.outputPath('mobile-login.png') })
  })
}

test('reduced motion stops repeating animations and theme switch stays readable', async ({ page }) => {
  await mockUi(page, 'dark')
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/ask')
  await expect(page.locator('.msg-latest .av-ai')).toBeVisible()
  await expect(page.locator('.msg-latest .av-ai')).toHaveCSS('animation-iteration-count', '1')
  await page.getByRole('button', { name: '切换浅色主题' }).click()
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'light')
  expect(await page.evaluate(() => localStorage.getItem('theme'))).toBe('light')
  await page.getByRole('link', { name: '简历解析', exact: true }).click()
  await page.getByRole('button', { name: '查看解析' }).click()
  await expect(page.locator('.section-content')).toHaveCSS('color', 'rgb(15, 23, 42)')
})
