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
  const responseBefore = await context.request.get(`/api/learning-notes/${fixture.legacyNoteId}`)
  const before = (await responseBefore.json()).data
  await page.goto(`/notes/${fixture.noteId}`)
  await page.waitForURL(/sessionId=/)
  const sessionId = new URL(page.url()).searchParams.get('sessionId')
  const panel = page.getByRole('complementary', { name: '问题目录' })
  await panel.locator('.tree-node').first().waitFor()
  assert.equal(await page.getByRole('button', { name: /编辑笔记|存为学习笔记|整理正文/ }).count(), 0)
  await page.reload()
  await panel.locator('.tree-node').first().waitFor()
  assert.equal(new URL(page.url()).searchParams.get('sessionId'), sessionId)
  await page.goto(`/notes/${fixture.legacyNoteId}`)
  await page.getByRole('button', { name: '历史笔记', exact: true }).click()
  const history = page.getByRole('region', { name: '历史笔记' })
  await history.locator('.history-note-content').waitFor()
  await history.locator('summary').click()
  assert.equal(await history.locator('.history-note-source').count(), JSON.parse(before.sourceSnapshot).length)
  await page.screenshot({ path: `${output}/desktop.png`, fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true)
  await page.screenshot({ path: `${output}/mobile.png`, fullPage: true, animations: 'disabled' })
  const reread = (await (await context.request.get(`/api/learning-notes/${fixture.legacyNoteId}`)).json()).data
  assert.equal(reread.content, before.content)
  assert.equal(reread.treeJson, before.treeJson)
  assert.equal(reread.version, before.version)
  assert.equal(reread.sourceSnapshot, before.sourceSnapshot)
  assert.deepEqual(errors, [])
  await writeFile(`${output}/result.json`, JSON.stringify({ passed: true, checks: ['real login', 'open saved tree', 'reload session', 'legacy readonly content', 'source preservation', 'desktop and mobile', 'no runtime errors'] }, null, 2))
  console.log('PASS real browser/API/MySQL readonly tree flow; screenshots:', output)
} finally { await browser.close() }
