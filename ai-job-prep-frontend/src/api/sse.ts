import type { ToolCall } from '@/types'

export interface SSECallbacks {
  onToken?: (content: string, traceId?: string) => void
  onToolCall?: (toolCall: ToolCall) => void
  onToolResult?: (toolName: string, result: unknown) => void
  onUsage?: (promptTokens: number, completionTokens: number, totalTokens: number) => void
  onDone?: () => void
  onError?: (error: Error) => void
}

export async function connectSSE(
  url: string,
  body: Record<string, unknown>,
  callbacks: SSECallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
  })

  if (!response.ok) {
    throw new Error(`SSE connection failed: ${response.status}`)
  }

  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let lastEventType = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    for (const rawEvent of events) {
      for (const line of rawEvent.split('\n')) {
        if (line.startsWith('event:')) {
          lastEventType = line.slice(6).trim()
        }
        if (line.startsWith('data:')) {
          const raw = line.slice(5).trim()
          try {
            const data = JSON.parse(raw)
            dispatchEvent(lastEventType, data, callbacks)
          } catch {
            // skip malformed JSON
          }
        }
      }
    }
  }

  callbacks.onDone?.()
}

function dispatchEvent(eventType: string, data: any, cb: SSECallbacks) {
  switch (eventType) {
    case 'token':
      cb.onToken?.(data.content, data.traceId)
      break
    case 'tool_call':
      cb.onToolCall?.(data.toolCall)
      break
    case 'tool_result':
      cb.onToolResult?.(data.toolName, data.result)
      break
    case 'usage':
      cb.onUsage?.(data.promptTokens, data.completionTokens, data.totalTokens)
      break
    case 'error':
      cb.onError?.(new Error(data.message))
      break
  }
}
