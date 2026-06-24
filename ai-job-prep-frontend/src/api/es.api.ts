import request from './request'
import type { PageResult, EsIndexInfo, EsDocumentVO } from '@/types'

export const esApi = {
  listIndices() {
    return request.get<never, EsIndexInfo[]>('/api/admin/es/indices')
  },
  getIndexInfo(index: string) {
    return request.get<never, EsIndexInfo>(`/api/admin/es/indices/${encodeURIComponent(index)}`)
  },
  getMapping(index: string) {
    return request.get<never, Record<string, string>>(`/api/admin/es/indices/${encodeURIComponent(index)}/mapping`)
  },
  listDocs(index: string, params?: { page?: number; size?: number; query?: string }) {
    return request.get<never, PageResult<EsDocumentVO>>(
      `/api/admin/es/indices/${encodeURIComponent(index)}/docs`,
      { params },
    )
  },
  getDoc(index: string, id: string) {
    return request.get<never, Record<string, unknown>>(
      `/api/admin/es/indices/${encodeURIComponent(index)}/docs/${encodeURIComponent(id)}`,
    )
  },
  deleteIndex(index: string) {
    return request.delete<never, void>(`/api/admin/es/indices/${encodeURIComponent(index)}`)
  },
  deleteDoc(index: string, id: string) {
    return request.delete<never, void>(
      `/api/admin/es/indices/${encodeURIComponent(index)}/docs/${encodeURIComponent(id)}`,
    )
  },
  deleteByQuery(index: string, query: string) {
    return request.delete<never, number>(
      `/api/admin/es/indices/${encodeURIComponent(index)}/docs`,
      { data: { query } },
    )
  },
}
