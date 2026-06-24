export interface EsIndexInfo {
  name: string
  health: string
  docCount: number
  storageSizeBytes: number
  numOfShards: number
  numOfReplicas: number
}

export interface EsDocumentVO {
  id: string
  source: Record<string, unknown>
  score: number
}
