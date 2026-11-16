import DOMPurify from 'dompurify'
import { marked } from 'marked'

/** All chat sources (user text, model output and restored history) are untrusted. */
export function renderMarkdown(text: string): string {
  return DOMPurify.sanitize(marked.parse(text, { async: false }), {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form', 'input', 'button'],
    FORBID_ATTR: ['style'],
  })
}
