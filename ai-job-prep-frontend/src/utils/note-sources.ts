import type { SourceMessage } from '@/api/learning-notes.api'

export const sourceText = (message: SourceMessage) => `### ${message.role === 'user' ? '问题' : '回答'}\n\n${message.content}`
export const sourceTranscript = (messages: SourceMessage[]) => messages.map(sourceText).join('\n\n')

/** Captured originals are facts. Selection and body edits never remove or rewrite them. */
export function retainSources(saved: SourceMessage[], incoming: SourceMessage[]): SourceMessage[] {
  const sources = new Map(saved.map(message => [message.id, message]))
  for (const message of incoming) if (!sources.has(message.id)) sources.set(message.id, message)
  return [...sources.values()]
}
