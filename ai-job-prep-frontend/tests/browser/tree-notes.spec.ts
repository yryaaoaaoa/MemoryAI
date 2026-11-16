import { test, expect, type Page } from '@playwright/test'

const title = '达到核心线程数，先尝试入队'
async function menu(page: Page, name: string) {
  await page.getByRole('button', { name: `${name} 的更多操作`, exact: true }).click()
}

test.beforeEach(async ({ page }) => {
  await page.goto('/tree-notes.html')
})

test('edit title and Markdown body, then save and restore from browser storage', async ({ page }) => {
  await page.getByRole('button', { name: title, exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill('先排队，再扩容')
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).press('Enter')
  await page.getByRole('button', { name: '编辑 先排队，再扩容 的正文', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点正文' }).fill('保存 **完整解释**，保留 `corePoolSize`。')
  await page.getByRole('button', { name: '保存草稿', exact: true }).click()
  await page.reload()
  await page.getByRole('button', { name: '全部展开', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '先排队，再扩容', exact: true })).toContainText('完整解释')
  await expect(page.getByText('已保存', { exact: true })).toBeVisible()
})

test('add children and siblings, change hierarchy, delete and undo a complete branch', async ({ page }) => {
  await page.getByRole('button', { name: `为 ${title} 添加子知识点`, exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill('队列容量')
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).press('Enter')
  await expect(page.getByRole('treeitem', { name: '队列容量', exact: true })).toHaveAttribute('aria-level', '3')
  await menu(page, '队列容量')
  await page.getByRole('button', { name: '添加同级知识点', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill('提交速率')
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).press('Enter')
  await menu(page, '提交速率')
  await page.getByRole('button', { name: '缩进为子知识点', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '提交速率', exact: true })).toHaveAttribute('aria-level', '4')
  await menu(page, '队列容量')
  await page.getByRole('button', { name: '删除整个分支', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '提交速率', exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: '撤销', exact: true }).first().click()
  await expect(page.getByRole('treeitem', { name: '提交速率', exact: true })).toBeVisible()
  await menu(page, '提交速率')
  await page.getByRole('button', { name: '提升一级', exact: true }).click()
  await expect(page.getByRole('treeitem', { name: '提交速率', exact: true })).toHaveAttribute('aria-level', '3')
})

test('collapsing commits an in-progress edit, and deep search expands the path', async ({ page }) => {
  await page.getByRole('button', { name: `编辑 ${title} 的正文`, exact: true }).click()
  await page.getByRole('textbox', { name: '知识点正文' }).fill('折叠不能丢失正在编辑的正文。')
  await page.getByRole('button', { name: `折叠 ${title}`, exact: true }).click()
  await expect(page.getByText('折叠不能丢失正在编辑的正文。', { exact: true })).toHaveCount(0)
  await page.getByRole('button', { name: `展开 ${title}`, exact: true }).click()
  await expect(page.getByText('折叠不能丢失正在编辑的正文。', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '全部折叠', exact: true }).click()
  await page.getByRole('textbox', { name: '搜索知识点' }).fill('无界队列')
  await expect(page.getByText('找到 1 个知识点', { exact: true })).toBeVisible()
  await expect(page.getByText(/任务持续积压还可能耗尽内存/)).toBeVisible()
  await page.getByRole('textbox', { name: '搜索知识点' }).fill('不存在的知识点XYZ')
  await expect(page.getByRole('heading', { name: '没有找到“ 不存在的知识点XYZ ”'.replaceAll(' ', '') })).toBeVisible()
  await page.getByRole('button', { name: '清除搜索', exact: true }).last().click()
  await expect(page.getByRole('treeitem', { name: '先理解任务的执行顺序', exact: true })).toBeVisible()
})

test('whole-note reading and Markdown export preserve code and tables', async ({ page }) => {
  await page.getByRole('button', { name: '全文', exact: true }).click()
  await expect(page.locator('.reading-view pre')).toContainText('ThreadPoolExecutor')
  await expect(page.locator('.reading-view table')).toContainText('触发拒绝策略')
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Markdown', exact: true }).click()
  expect((await download).suggestedFilename()).toBe('线程池，任务到底怎么跑？.md')
})

test('mobile layout stays within the viewport and supports node editing', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(page.getByRole('button', { name: `为 ${title} 添加子知识点`, exact: true })).toBeVisible()
  await page.getByRole('button', { name: `为 ${title} 添加子知识点`, exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill('手机上新增的知识点')
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).press('Enter')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('tree-notes-mobile.png'), fullPage: true })
})

test('preview loads without backend requests or runtime errors in both themes', async ({ page }) => {
  const errors: string[] = []
  const apiRequests: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => { if (new URL(request.url()).pathname.startsWith('/api/')) apiRequests.push(request.url()) })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.reload()
  await page.screenshot({ path: test.info().outputPath('tree-notes-desktop.png'), fullPage: true })
  await page.getByRole('button', { name: '切换深色主题', exact: true }).click()
  await page.screenshot({ path: test.info().outputPath('tree-notes-dark.png'), fullPage: true, animations: 'disabled' })
  await page.getByRole('button', { name: '查看示例问答', exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await page.getByRole('button', { name: '返回笔记', exact: true }).click()
  expect(errors).toEqual([])
  expect(apiRequests).toEqual([])
})
