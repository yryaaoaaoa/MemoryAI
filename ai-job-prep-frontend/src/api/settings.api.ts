import request from './request'

export interface RetrievalTier {
  topK: number
  minScore: number
}

export interface RetrievalConfig {
  rankConstant: number
  numCandidatesFactor: number
  rankWindowFactor: number
  bm25Fields: string
  shortQueryMaxLen: number
  mediumQueryMaxLen: number
  shortQuery: RetrievalTier
  mediumQuery: RetrievalTier
  longQuery: RetrievalTier
  defaults: RetrievalTier
}

export const settingsApi = {
  getRetrievalConfig() {
    return request.get<never, RetrievalConfig>('/api/settings/retrieval')
  },
  saveRetrievalConfig(config: RetrievalConfig) {
    return request.put<RetrievalConfig, void>('/api/settings/retrieval', config)
  },
  resetRetrievalConfig() {
    return request.delete<never, void>('/api/settings/retrieval')
  },
}
