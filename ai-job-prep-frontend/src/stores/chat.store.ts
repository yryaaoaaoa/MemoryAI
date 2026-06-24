import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { ChatSession, ChatMessage, ToolCall } from '@/types'

export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSession[]>([])
  const activeSessionId = ref<string | null>(null)
  const messages = ref<ChatMessage[]>([])
  const isStreaming = ref(false)
  const streamingContent = ref('')
  const pendingToolCalls = ref<ToolCall[]>([])

  function addMessage(msg: ChatMessage) {
    messages.value.push(msg)
  }

  function appendStreamChunk(chunk: string) {
    streamingContent.value += chunk
  }

  function finalizeStreaming() {
    if (streamingContent.value) {
      messages.value.push({
        id: crypto.randomUUID(),
        role: 'assistant',
        content: streamingContent.value,
        createdAt: new Date().toISOString(),
      })
    }
    streamingContent.value = ''
    isStreaming.value = false
    pendingToolCalls.value = []
  }

  function resetStreaming() {
    streamingContent.value = ''
    isStreaming.value = false
    pendingToolCalls.value = []
  }

  return {
    sessions, activeSessionId, messages,
    isStreaming, streamingContent, pendingToolCalls,
    addMessage, appendStreamChunk, finalizeStreaming, resetStreaming,
  }
})
