import DOMPurify from 'dompurify'
import { Marked } from 'marked'
import katex from 'katex'
import { createUuid } from './uuid'

function readMath(source: string) {
  const opener = ['$$', '\\[', '\\(', '$'].find(value => source.startsWith(value))
  if (!opener) return
  const closer = opener === '\\[' ? '\\]' : opener === '\\(' ? '\\)' : opener
  const display = opener === '$$' || opener === '\\['
  if (opener === '$' && /\s/.test(source[1] ?? '')) return
  let end = opener.length
  while ((end = source.indexOf(closer, end)) !== -1) {
    let backslashes = 0
    for (let i = end - 1; source[i] === '\\'; i--) backslashes++
    if (backslashes % 2 === 0) {
      const body = source.slice(opener.length, end)
      if (!body.trim() || (!display && body.includes('\n'))) return
      if (opener === '$' && (/\s/.test(body.at(-1)!) || /[\d$]/.test(source[end + 1] ?? ''))) return
      return { raw: source.slice(0, end + closer.length), body, display }
    }
    end += closer.length
  }
}

/** All chat sources (user text, model output and restored history) are untrusted. */
export function renderMarkdown(text: string): string {
  const formulas = new Map<string, string>()
  const markdown = new Marked({ extensions: [
    {
      name: 'math', level: 'inline',
      start: source => source.search(/\$|\\[([]/),
      tokenizer(source) {
        const math = readMath(source)
        if (math) return { type: 'math', ...math }
      },
      renderer(token) {
        const placeholder = `<span data-math-placeholder="${createUuid()}"></span>`
        formulas.set(placeholder, katex.renderToString(token.body, {
          displayMode: token.display, throwOnError: false, trust: false, strict: 'ignore',
          output: 'htmlAndMathml',
        }))
        return placeholder
      },
    },
    {
      name: 'math', level: 'block',
      start: source => source.search(/^ {0,3}(?:\$\$|\\\[)/m),
      tokenizer(source) {
        const indent = source.match(/^ {0,3}/)![0]
        const math = readMath(source.slice(indent.length))
        if (!math?.display) return
        const rest = source.slice(indent.length + math.raw.length)
        const lineEnd = rest.match(/^[ \t]*(?:\n|$)/)
        if (lineEnd) return { type: 'math', ...math, raw: indent + math.raw + lineEnd[0] }
      },
    },
  ] })
  const sanitized = DOMPurify.sanitize(markdown.parse(text, { async: false }), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button'],
    FORBID_ATTR: ['style'],
  })
  // Sanitize all source HTML first. Only KaTeX output with trust:false may keep
  // the inline styles and MathML needed for layout and accessible formulas.
  return sanitized.replace(/<span data-math-placeholder="[^"]+"><\/span>/g,
    placeholder => formulas.get(placeholder) ?? placeholder)
}
