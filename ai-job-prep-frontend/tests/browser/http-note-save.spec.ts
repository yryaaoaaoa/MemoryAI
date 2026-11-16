import { test, expect } from '@playwright/test'

test('HTTP origin without randomUUID can create notes, retry with the same request ID, and add tree nodes', async ({ page }) => {
  const errors: string[] = []
  const creates: any[] = []
  let failSave = true
  let stored: any
  const source = [{ id: 1, sessionId: 10, role: 'user', content: '线程池是怎么工作的？' },
    { id: 2, sessionId: 10, role: 'assistant', content: '先创建核心线程，再尝试入队。', metadata: '{"response_status":"completed"}' }]
  page.on('pageerror', error => errors.push(error.message))
  // Keep a real insecure origin in the browser, forwarding only the development assets.
  // localhost would be considered secure and would conceal the production regression.
  await page.route('http://memoryai.test/**', async route => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (!path.startsWith('/api/')) {
      const response = await route.fetch({ url: `http://127.0.0.1:4179${path}${url.search}` })
      return route.fulfill({ response })
    }
    if (path === '/api/auth/me') return ok({ id: 1, username: 'http-note-test', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled: true })
    if (path === '/api/sessions') return ok([{ id: 10, title: '线程池学习' }])
    if (path === '/api/sessions/10/messages') return ok(source)
    if (path === '/api/learning-notes' && route.request().method() === 'POST') {
      const body = route.request().postDataJSON(); creates.push(body)
      if (failSave) return route.fulfill({ status: 503, json: { code: 503, message: '测试重试' } })
      stored = { ...body, id: 5, version: 0, sourceSnapshot: JSON.stringify(source) }
      return ok(stored)
    }
    if (path === '/api/learning-notes/5' && route.request().method() === 'PUT') {
      stored = { ...stored, ...route.request().postDataJSON(), version: stored.version + 1 }
      return ok(stored)
    }
    if (path === '/api/learning-notes/5') return ok(stored)
    return ok([])
  })
  await page.goto('http://memoryai.test/ask?sessionId=10')
  expect(await page.evaluate(() => ({ secure: isSecureContext, uuid: typeof crypto.randomUUID, random: typeof crypto.getRandomValues })))
    .toEqual({ secure: false, uuid: 'undefined', random: 'function' })
  await page.getByRole('button', { name: '存为学习笔记', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '存为学习笔记', exact: true })
  await expect(drawer.getByLabel('笔记正文')).toHaveValue(/先创建核心线程/)
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer.getByRole('alert')).toContainText('保存失败')
  failSave = false
  await drawer.getByRole('button', { name: '保存笔记', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
  expect(creates[0].requestId).toMatch(uuid)
  expect(creates[1].requestId).toBe(creates[0].requestId)
  await page.goto('http://memoryai.test/notes/5')
  await page.getByRole('button', { name: '转为知识树', exact: true }).click()
  await page.getByRole('button', { name: '为 原笔记 添加子知识点', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill('队列容量')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
  const tree = JSON.parse(stored.treeJson)
  expect(tree.nodes[0].id).toMatch(uuid)
  expect(tree.nodes[0].children[0].id).toMatch(uuid)
  expect(tree.nodes[0].children[0].id).not.toBe(tree.nodes[0].id)
  await page.reload()
  await expect(page.getByRole('treeitem', { name: '队列容量', exact: true })).toBeVisible()
  expect(errors).toEqual([])
})
