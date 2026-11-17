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

test('renders inline and block math, including multiline derivations, inside Markdown', () => {
  const source = String.raw`## 公式 $E=mc^2$

- 勾股定理：\(a^2+b^2=c^2\)

$$
\begin{aligned}
f(x)&=x^2\\
f'(x)&=2x
\end{aligned}
$$
\[\frac{1}{2}+\sqrt{4}\]
`
  const doc = new JSDOM(renderMarkdown(source)).window.document
  assert.equal(doc.querySelectorAll('.katex').length, 4)
  assert.equal(doc.querySelectorAll('.katex-display').length, 2)
  assert.equal(doc.querySelectorAll('math').length, 4)
  assert.ok(doc.querySelector('h2 .katex'))
  assert.ok(doc.querySelector('li .katex'))
  assert.ok(doc.querySelector('.mfrac'))
})

test('leaves code, escaped dollars and ordinary prices unchanged', () => {
  const source = '价格 $5 和 $10；转义 \\$x\\$。\n\n`$x$` 和 `\\(y\\)`\n\n```tex\n$$z$$\n\\[w\\]\n```'
  const doc = new JSDOM(renderMarkdown(source)).window.document
  assert.equal(doc.querySelector('.katex'), null)
  assert.ok(doc.body.textContent.includes('价格 $5 和 $10；转义 $x$'))
  assert.ok(doc.querySelector('pre code')!.textContent.includes('$$z$$'))
})

test('supports every streamed prefix and keeps malformed formulas readable', () => {
  for (const source of [String.raw`$\frac{1}{2}$`, String.raw`\(x^2\)`, String.raw`$$\sum_{i=1}^n i$$`, String.raw`\[\int_0^1 x dx\]`]) {
    for (let end = 1; end < source.length; end++) {
      assert.doesNotThrow(() => renderMarkdown(source.slice(0, end)))
    }
    assert.match(renderMarkdown(source), /class="katex"/)
  }
  const invalid = new JSDOM(renderMarkdown(String.raw`$\frac{1}{$`)).window.document
  assert.equal(invalid.querySelector('.katex-error')!.textContent, String.raw`\frac{1}{`)
})

test('math cannot enable executable markup or bypass source HTML sanitization', () => {
  const source = String.raw`$\href{javascript:alert(1)}{link}$

$\htmlStyle{background:url(https://example.com/track)}{x}$

$\includegraphics{https://example.com/track}$

$\text{<img src=x onerror=alert(1)>}$

<span style="position:fixed" onclick="alert(1)">raw</span>`
  const doc = new JSDOM(renderMarkdown(source)).window.document
  assert.equal(doc.querySelector('a,img,script,iframe,[onclick],[onerror]'), null)
  assert.equal([...doc.querySelectorAll('span')].find(node => node.textContent === 'raw')!.getAttribute('style'), null)
  for (const element of doc.querySelectorAll('*')) {
    for (const attribute of element.attributes) {
      assert.ok(!attribute.name.startsWith('on'))
      assert.ok(!attribute.value.includes('background:url'))
      assert.ok(!/^javascript:/i.test(attribute.value))
    }
  }
})
