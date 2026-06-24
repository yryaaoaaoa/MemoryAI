import request from './request'
import type { KnowledgeBase, PageResult } from '@/types'

export const knowledgeBaseApi = {
  list(params?: { page?: number; size?: number }) {
    return request.get<never, PageResult<KnowledgeBase>>('/api/knowledge-bases', { params })
  },
  getById(id: string) {
    return request.get<never, KnowledgeBase>(`/api/knowledge-bases/${id}`)
  },
  create(data: { name: string; description?: string }) {
    return request.post<never, KnowledgeBase>('/api/knowledge-bases', data)
  },
  update(id: string, data: Partial<KnowledgeBase>) {
    return request.put<never, KnowledgeBase>(`/api/knowledge-bases/${id}`, data)
  },
  delete(id: string) {
    return request.delete<never, void>(`/api/knowledge-bases/${id}`)
  },
}
