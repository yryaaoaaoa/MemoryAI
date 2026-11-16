// Uses only the disposable backend fixture produced by the local tree integration check.
// node tests/live/learning-note-tree.mjs [fixture.json] [frontend URL]
import { chromium } from '@playwright/test'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'

const fixture = JSON.parse(await readFile(process.argv[2] || '../ai-rag-agent/target/tree-live-fixture.json', 'utf8'))
const baseURL = process.argv[3] || 'http://127.0.0.1:5173'
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ baseURL, viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))
const output = '../outputs/tree-live-browser'
await mkdir(output, { recursive: true })
try {
  const login = await context.request.post('/api/auth/login', { data: { username: fixture.username, password: fixture.password } })
  assert.equal(login.status(), 200)
  assert.equal((await login.json()).code, 200)
  await page.goto(`/notes/${fixture.noteId}`)
  await page.getByRole('treeitem', { name: '执行顺序', exact: true }).waitFor()
  const responseBefore = await context.request.get(`/api/learning-notes/${fixture.legacyNoteId}`)
  const before = (await responseBefore.json()).data
  await page.goto(`/notes/${fixture.legacyNoteId}`)
  if (!before.treeJson) await page.getByRole('button', { name: '转为知识树', exact: true }).click()
  const name = `浏览器联调知识点 ${Date.now()}`
  await page.getByRole('button', { name: '添加一级知识点', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).fill(name)
  await page.getByRole('textbox', { name: '知识点标题', exact: true }).press('Enter')
  await page.getByRole('treeitem', { name, exact: true }).getByRole('button', { name: '添加解释、例子或代码', exact: true }).click()
  await page.getByRole('textbox', { name: '知识点正文', exact: true }).fill('真实浏览器 → Spring 接口 → MySQL。\n\n```java\nexecutor.execute(task);\n```')
  const savedResponse = page.waitForResponse(response => response.url().endsWith(`/api/learning-notes/${fixture.legacyNoteId}`) && response.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存修改', exact: true }).click()
  const saved = await (await savedResponse).json()
  assert.equal(saved.code, 200)
  const tree = JSON.parse(saved.data.treeJson)
  if (!before.treeJson) assert.equal(tree.nodes[0].body, before.content)
  const newNode = tree.nodes.find(node => node.title === name)
  assert.ok(newNode.body.includes('MySQL'))
  await page.reload()
  await page.getByRole('treeitem', { name, exact: true }).waitFor()
  const reread = (await (await context.request.get(`/api/learning-notes/${fixture.legacyNoteId}`)).json()).data
  assert.equal(JSON.parse(reread.treeJson).nodes.find(node => node.title === name).id, newNode.id)
  assert.equal(reread.version, before.version + 1)
  assert.equal(reread.sourceSnapshot, before.sourceSnapshot)
  const search = (await (await context.request.get('/api/learning-notes', { params: { query: name, page: 1, size: 20 } })).json()).data
  assert.ok(search.records.some(note => note.id === fixture.legacyNoteId))
  await page.getByRole('button', { name: '切换浅色主题', exact: true }).click()
  await page.screenshot({ path: `${output}/desktop.png`, fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  await page.getByRole('treeitem', { name, exact: true }).scrollIntoViewIfNeeded()
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: `${output}/mobile.png`, fullPage: true, animations: 'disabled' })
  await context.storageState({ path: `${output}/browser-state.json` })
  assert.deepEqual(errors, [])
  await writeFile(`${output}/result.json`, JSON.stringify({ passed: true, noteId: fixture.legacyNoteId, version: reread.version, checks: ['real login', 'tree from API', 'legacy conversion', 'inline edit', 'save to MySQL', 'reload stable IDs', 'source preservation', 'server search', 'desktop and mobile', 'no runtime errors'] }, null, 2))
  console.log('PASS real browser/API/MySQL tree flow; screenshots:', output)
} finally { await browser.close() }
