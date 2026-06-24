import request from './request'
import type { Document, DocumentChunk, PageResult } from '@/types'

export const documentApi = {
  listByKB(kbId: string, params?: { page?: number; size?: number }) {
    return request.get<never, PageResult<Document>>(`/api/knowledge-bases/${kbId}/documents`, { params })
  },
  upload(kbId: string, file: File, onProgress?: (percent: number) => void) {
    const formData = new FormData()
    formData.append('file', file)
    return request.post<never, Document>(`/api/knowledge-bases/${kbId}/documents`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress(e) {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
      timeout: 120000,
    })
  },
  getStatus(docId: string) {
    return request.get<never, Document>(`/api/documents/${docId}/status`)
  },
  getChunks(docId: string) {
    return request.get<never, DocumentChunk[]>(`/api/documents/${docId}/chunks`)
  },
  delete(docId: string) {
    return request.delete<never, void>(`/api/documents/${docId}`)
  },
}
