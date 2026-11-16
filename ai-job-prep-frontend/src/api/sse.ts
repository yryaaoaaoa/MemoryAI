import type { ToolCall } from '@/types'
import { dismissSessionExpiryNotice, handleSessionExpiry } from '@/utils/session-expiry'

export interface SSECallbacks {
  onToken?: (content: string, traceId?: string) => void
  onToolCall?: (toolCall: ToolCall) => void
  onToolResult?: (toolName: string, result: unknown) => void
  onUsage?: (promptTokens: number, completionTokens: number, totalTokens: number) => void
  onDone?: () => void
  onError?: (error: Error) => void
  onStatus?: (status: string, message?: string, messageId?: number) => void
}

export async function connectSSE(
  url: string,
  body: Record<string, unknown>,
  callbacks: SSECallbacks,
  signal?: AbortSignal,
): Promise<void> {
  const baseURL = import.meta.env.VITE_API_BASE_URL?.replace(/\/$/, '') || ''
  const endpoint = /^https?:\/\//i.test(url) ? url : baseURL + '/' + url.replace(/^\//, '')
  const response = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
    signal,
    credentials: 'include',
  })

  if (!response.ok) {
    if (response.status === 401) {
      handleSessionExpiry()
      throw new Error('登录已过期，请重新登录后重试。')
    }
    throw new Error(`SSE connection failed: ${response.status}`)
  }

  dismissSessionExpiryNotice()
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  while (true) {
    const { done, value } = await reader.read()
    if (done) break

    buffer += decoder.decode(value, { stream: true })
    const events = buffer.split('\n\n')
    buffer = events.pop() || ''

    for (const rawEvent of events) {
      let lastEventType = ''
      for (const line of rawEvent.split('\n')) {
        if (line.startsWith('event:')) {
          lastEventType = line.slice(6).trim()
        }
        if (line.startsWith('data:')) {
          const raw = line.slice(5).trim()
          let data: unknown
          try { data = JSON.parse(raw) } catch { continue }
          dispatchEvent(lastEventType, data, callbacks)
          if (lastEventType === 'error') {
            await reader.cancel()
            return // A failed stream must never be reported as successfully completed.
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
      cb.onStatus?.(data.status || 'failed', data.message)
      cb.onError?.(new Error(data.message))
      break
    case 'status':
      cb.onStatus?.(data.status, data.message, Number.isSafeInteger(data.messageId) && data.messageId > 0 ? data.messageId : undefined)
      break
  }
}
