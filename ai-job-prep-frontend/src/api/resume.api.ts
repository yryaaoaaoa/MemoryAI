import request from './request'

export interface Resume {
  id: string
  fileName: string
  fileHash: string
  fileSize: number
  rawText?: string
  structuredJson?: string
  status: string
  errorMsg?: string
  retryCount: number
  createdAt: string
  updatedAt: string
}

export interface ResumeSection {
  type: string
  content: string
  tags: string[]
}

export function parseStructured(json?: string): ResumeSection[] {
  if (!json) return []
  try {
    const parsed = JSON.parse(json)
    return parsed.sections || []
  } catch {
    return []
  }
}

export const resumeApi = {
  upload(file: File, onProgress?: (percent: number) => void) {
    const formData = new FormData()
    formData.append('file', file)
    return request.post<never, Resume>('/api/resume/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress(e) {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
      timeout: 120000,
    })
  },

  list() {
    return request.get<never, Resume[]>('/api/resume/list')
  },

  getById(id: string) {
    return request.get<never, Resume>(`/api/resume/${id}`)
  },

  reanalyze(id: string, file: File) {
    const formData = new FormData()
    formData.append('file', file)
    return request.post<never, Resume>(`/api/resume/${id}/reanalyze`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    })
  },

  delete(id: string) {
    return request.delete<never, void>(`/api/resume/${id}`)
  },
}
