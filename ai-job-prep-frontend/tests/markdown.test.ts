import assert from 'node:assert/strict'
import { test } from 'node:test'
import { JSDOM } from 'jsdom'

const dom = new JSDOM('<!doctype html><html><body></body></html>')
Object.assign(globalThis, { window: dom.window })
const { renderMarkdown } = await import('../src/utils/markdown.ts')

test('renders useful markdown and preserves Chinese text and code', () => {
  const html = renderMarkdown('## 说明\n\n**正文**\n\n```html\n<img onerror="alert(1)">\n```')
  assert.match(html, /<h2>说明<\/h2>/)
  assert.match(html, /<strong>正文<\/strong>/)
  assert.match(html, /&lt;img/)
})

for (const payload of [
  '<img src=x onerror="window.pwned=1">',
  '<svg onload="window.pwned=1"></svg>',
  '<a href="javascript:alert(1)">链接</a>',
  '<iframe srcdoc="<script>alert(1)</script>"></iframe>',
  '<math><mtext><img src=x onerror=alert(1)></mtext></math>',
  '<form><input autofocus onfocus=alert(1)></form>',
]) {
  test(`removes executable markup: ${payload}`, () => {
    const html = renderMarkdown(payload)
    const content = new JSDOM(html).window.document
    assert.equal(content.querySelector('script,iframe,svg,math,form,input'), null)
    for (const element of content.querySelectorAll('*')) {
      for (const attribute of element.attributes) {
        assert.ok(!attribute.name.startsWith('on'))
        assert.ok(!/^javascript:/i.test(attribute.value))
      }
    }
  })
}

test('sanitizes partial streamed HTML as well as complete restored messages', () => {
  const payload = '<img src=x onerror="alert(1)">'
  for (let end = 1; end <= payload.length; end++) {
    const output = new JSDOM(renderMarkdown(payload.slice(0, end))).window.document
    assert.equal(output.querySelector('[onerror]'), null)
  }
})
