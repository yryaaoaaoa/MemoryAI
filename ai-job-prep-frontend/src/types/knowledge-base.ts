export interface KnowledgeBase {
  id: string
  name: string
  description?: string
  priority?: string
  documentCount: number
  chunkCount: number
  createdAt: string
  updatedAt: string
}

export interface Document {
  id: string
  kbId: string
  fileName: string
  fileType: string
  fileSize: number
  chunkCount: number
  status: DocumentStatus
  errorMessage?: string
  createdAt: string
}

export type DocumentStatus =
  | 'UPLOADING'
  | 'PARSING'
  | 'CHUNKING'
  | 'EMBEDDING'
  | 'READY'
  | 'FAILED'

export interface DocumentChunk {
  id: string
  docId: string
  content: string
  headingPath?: string
  chunkIndex: number
  sectionType?: string
  tags?: string
  metadata?: Record<string, unknown>
}
