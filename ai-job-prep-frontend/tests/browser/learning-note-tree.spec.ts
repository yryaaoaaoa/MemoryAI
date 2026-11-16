import { test, expect, type Page } from '@playwright/test'

const legacy = '原始解释。\n\n```java\nSystem.out.println("保留代码");\n```\n\n| 参数 | 值 |\n| --- | --- |\n| core | 2 |'
async function setup(page: Page) {
  const messages = [{ id: 1, sessionId: 10, role: 'user', content: '队列为什么要有限制？' }, { id: 2, sessionId: 10, role: 'assistant', content: '队列容量用于限制等待任务。', metadata: '{"response_status":"completed"}' }]
  const state = { note: { id: 5, version: 0, title: '线程池笔记', confusion: '', content: legacy, treeJson: null as string | null, status: 'PENDING', sourceSessionId: 10, sourceMessageIds: '[1,2]', sourceSnapshot: JSON.stringify(messages) }, updates: [] as any[], fail: 0 }
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (!path.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (path === '/api/auth/me') return ok({ id: 1, username: 'tree-test', role: 'user' })
    if (path === '/api/features') return ok({ ragEnabled: false })
    if (path === '/api/sessions') return ok([{ id: 10, title: '继续学习', noteId: 5 }])
    if (path === '/api/sessions/10/messages') return ok(messages)
    if (path === '/api/learning-notes/draft') return ok({ title: 'AI 标题', confusion: '', content: '整理后的解释，保留供对照。' })
    if (path === '/api/learning-notes/5/sessions') return ok(10)
    if (path === '/api/learning-notes/5' && route.request().method() === 'PUT') {
      const body = route.request().postDataJSON(); state.updates.push(body)
      if (state.fail) return route.fulfill({ status: state.fail, json: { code: state.fail, message: '测试保存失败' } })
      expect(body.expectedVersion).toBe(state.note.version)
      state.note = { ...state.note, ...body, version: state.note.version + 1 }; return ok(state.note)
    }
    if (path === '/api/learning-notes/5') return ok(state.note)
    if (path === '/api/learning-notes') return ok({ records: [state.note], total: 1 })
    return ok([])
  })
  return state
}
async function convert(page: Page) {
  await page.goto('/notes/5')
  await page.getByRole('button', { name: '转为知识树', exact: true }).click()
}
async function addChild(page: Page, parent: string, title: string, body: string) {
  await page.getByRole('button', { name: `为 ${parent} 添加子知识点`, exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill(title)
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).press('Enter')
  await page.getByRole('treeitem', { name: title, exact: true }).getByRole('button', { name: '添加解释、例子或代码', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点正文', exact: true }).fill(body)
}

test('legacy conversion preserves full original, saves nested edits and restores IDs after reload', async ({ page }) => {
  const state = await setup(page)
  await convert(page)
  await addChild(page, '原笔记', '队列容量', '需要 **有限队列**。')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
  const tree = JSON.parse(state.note.treeJson!)
  expect(tree.nodes[0].body).toBe(legacy)
  expect(tree.nodes[0].children[0].body).toContain('有限队列')
  const id = tree.nodes[0].children[0].id
  await page.reload()
  await expect(page.getByRole('treeitem', { name: '队列容量', exact: true })).toHaveAttribute('aria-level', '2')
  await page.getByRole('button', { name: '队列容量', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill('容量与等待时间')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  expect(JSON.parse(state.note.treeJson!).nodes[0].children[0].id).toBe(id)
  expect(state.note.content).toContain('### 容量与等待时间')
  await page.getByRole('button', { name: '全文', exact: true }).click()
  await expect(page.locator('.tree-reading pre')).toContainText('保留代码')
  await expect(page.locator('.tree-reading table')).toContainText('core')
})

test('branch edits support hierarchy, search, delete undo and save failures retain input', async ({ page }) => {
  const state = await setup(page)
  await convert(page)
  await addChild(page, '原笔记', '容量', '容量正文')
  await page.getByRole('button', { name: '完成编辑', exact: true }).click()
  await addChild(page, '容量', '等待时间', '独特搜索词')
  await page.getByRole('button', { name: '完成编辑', exact: true }).click()
  await page.getByRole('button', { name: '等待时间 的更多操作', exact: true }).click()
  await page.getByRole('button', { name: '提升一级', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '等待时间', exact: true })).toHaveAttribute('aria-level', '2')
  await page.getByRole('button', { name: '等待时间 的更多操作', exact: true }).click()
  await page.getByRole('button', { name: '缩进为子知识点', exact: true }).click()
  await page.getByRole('button', { name: '容量 的更多操作', exact: true }).click()
  await page.getByRole('button', { name: '删除整个分支', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '等待时间', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '撤销', exact: true }).click()
  await page.getByRole('button', { name: '全部折叠', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索知识点', exact: true }).fill('独特搜索词')
  await expect(page.getByText('独特搜索词', { exact: true })).toBeVisible()
  state.fail = 409
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByText(/笔记已有新版本，当前修改已保留/)).toBeVisible()
  await expect(page.getByText('独特搜索词', { exact: true })).toBeVisible()
  state.fail = 0
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
  expect(JSON.parse(state.note.treeJson!).nodes[0].children[0].children[0].title).toBe('等待时间')
  await expect(page.getByRole('link', { name: '在新标签页查看最新笔记', exact: true })).toHaveCount(0)
})

test('conversation updates preserve branches and add copied or AI content as new nodes', async ({ page }) => {
  const state = await setup(page)
  await convert(page)
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('button', { name: '继续问', exact: true }).click()
  await page.getByRole('button', { name: '更新这条笔记', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: '更新学习笔记' })
  await drawer.getByRole('button', { name: '复制所选原文到正文', exact: true }).click()
  await drawer.getByRole('button', { name: '整理正文', exact: true }).click()
  await expect(drawer.getByRole('treeitem', { name: 'AI 整理结果', exact: true })).toBeVisible()
  await drawer.getByRole('button', { name: '保存更新', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  const nodes = JSON.parse(state.note.treeJson!).nodes
  expect(nodes[0].body).toBe(legacy)
  expect(nodes[1].title).toBe('补充问答')
  expect(nodes[2].body).toContain('整理后的解释')
})

test('editing a search result keeps its editor visible after the search word changes', async ({ page }) => {
  await setup(page)
  await convert(page)
  await addChild(page, '原笔记', '队列容量', '独特搜索词')
  await page.getByRole('button', { name: '完成编辑', exact: true }).click()
  await page.getByRole('button', { name: '全部折叠', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索知识点', exact: true }).fill('独特搜索词')
  await page.getByRole('button', { name: '编辑 队列容量 的正文', exact: true }).click()
  const editor = page.getByRole('textbox', { name: '知识点正文', exact: true })
  await editor.fill('新的')
  await editor.pressSequentially('正文')
  await expect(editor).toHaveValue('新的正文')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.reload()
  await expect(page.getByRole('treeitem', { name: '队列容量', exact: true })).toContainText('新的正文')
})

test('Chinese composition does not finish the title; a cancelled edit cannot mask a later server version', async ({ page }) => {
  const state = await setup(page)
  await convert(page)
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await expect(page.getByRole('button', { name: '编辑笔记', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '原笔记', exact: true }).click()
  const editor = page.getByRole('textbox', { name: '知识点标题', exact: true })
  await editor.fill('另一页面的标题')
  await editor.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true })
  await expect(editor).toBeVisible()
  await editor.press('Enter')
  await page.getByRole('button', { name: '取消编辑', exact: true }).click()
  const updatedTree = JSON.parse(state.note.treeJson!)
  updatedTree.nodes[0].title = '另一页面的标题'
  state.note.treeJson = JSON.stringify(updatedTree)
  state.note.content = state.note.content.replace('## 原笔记', '## 另一页面的标题')
  state.note.version++
  state.fail = 409
  await page.getByRole('button', { name: '编辑笔记', exact: true }).click()
  await page.getByLabel('标题', { exact: true }).fill('尚未保存的标题')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.getByRole('button', { name: '载入最新版本', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '载入最新版本', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '另一页面的标题', exact: true })).toBeVisible()
  await expect(page.getByRole('treeitem', { name: '原笔记', exact: true })).toHaveCount(0)
})

test('cancel conversion restores legacy content; tree editing fits a phone and has no runtime errors', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message))
  await setup(page)
  await convert(page)
  await page.getByRole('button', { name: '取消编辑', exact: true }).click()
  await expect(page.getByRole('tree', { name: '笔记知识树' })).toHaveCount(0)
  await expect(page.locator('.note-body pre')).toContainText('保留代码')
  await page.getByRole('button', { name: '转为知识树', exact: true }).click()
  await addChild(page, '原笔记', '子知识点', '手机端也能编辑。')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.screenshot({ path: test.info().outputPath('integrated-tree-desktop.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.getByRole('button', { name: '编辑 子知识点 的正文', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点正文', exact: true }).fill('手机修改已写入草稿。')
  await page.screenshot({ path: test.info().outputPath('integrated-tree-mobile.png'), fullPage: true, animations: 'disabled' })
  expect(errors).toEqual([])
})
