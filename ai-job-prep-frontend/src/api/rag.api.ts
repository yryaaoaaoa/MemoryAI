import request from './request'

// ========== RAG 问答 ==========

export interface Reference {
  content: string
  score: number
}

export interface ChatSessionDTO {
  id: number
  title: string
  kbIds: string
  userId: number
  resumeId: number | null
  totalPromptTokens: number
  totalCompletionTokens: number
  createdAt: string
  updatedAt: string
}

export const ragApi = {
  // ===== 会话管理 =====
  createSession() {
    return request.post<any, number>('/api/chat/session', [])
  },

  listSessions() {
    return request.get<any, any[]>('/api/sessions')
  },

  getMessages(sessionId: number) {
    return request.get<any, any[]>(`/api/sessions/${sessionId}/messages`)
  },

  deleteSession(sessionId: number) {
    return request.delete<any, void>(`/api/sessions/${sessionId}`)
  },

  setSessionResume(sessionId: number, resumeId: number | null) {
    return request.put<any, void>(`/api/sessions/${sessionId}/resume`, { resumeId })
  },
}
