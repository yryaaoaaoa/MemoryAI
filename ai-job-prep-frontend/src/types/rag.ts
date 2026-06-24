export interface RAGSearchQuery {
  query: string
  kbIds?: string[]
  topK?: number
  hybrid?: boolean
}

export interface RAGSearchResult {
  chunkId: string
  content: string
  documentName: string
  score: number
  vectorScore?: number
  bm25Score?: number
  pageNumber?: number
}
