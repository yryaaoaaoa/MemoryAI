import request from './request'

export type NoteStatus = 'PENDING' | 'REVIEW' | 'UNDERSTOOD'
export interface SourceMessage { id: number; sessionId?: number; role: string; content: string }
export interface NoteFields { title: string; confusion: string; content: string; status: NoteStatus; treeJson?: string | null }
export interface LearningNote extends NoteFields {
  id: number; version: number; sourceSessionId: number; sources: SourceMessage[]; createdAt: string; updatedAt: string
}
export interface LearningTreeState {
  note: LearningNote | null
  status: 'idle' | 'pending' | 'updated' | 'unchanged' | 'failed'
  nodeId: string | null
  messageId: number | null
  error: string | null
}
export interface LearningNodeConversation { nodeId: string; title: string; messages: SourceMessage[] }
interface StoredNote extends Omit<LearningNote, 'sources'> { sourceSnapshot: string }
interface StoredTreeState extends Omit<LearningTreeState, 'note'> { note: StoredNote | null }
function readNote({ sourceSnapshot, ...note }: StoredNote): LearningNote {
  return { ...note, sources: JSON.parse(sourceSnapshot) }
}
export const notesApi = {
  async list(params: { query?: string; status?: string; page: number; size: number }) {
    const result = await request.get<any, { records: StoredNote[]; total: number }>('/api/learning-notes', { params })
    return { ...result, records: result.records.map(readNote) }
  },
  async get(id: number) { return readNote(await request.get<any, StoredNote>(`/api/learning-notes/${id}`)) },
  remove(id: number) { return request.delete(`/api/learning-notes/${id}`) },
  openTree(id: number) { return request.post<any, number>(`/api/learning-notes/${id}/open`) },
  async learningTree(sessionId: number): Promise<LearningTreeState> {
    const state = await request.get<any, StoredTreeState>(`/api/sessions/${sessionId}/learning-tree`)
    return { ...state, note: state.note ? readNote(state.note) : null }
  },
  async retryLearningTree(sessionId: number): Promise<LearningTreeState> {
    const state = await request.post<any, StoredTreeState>(`/api/sessions/${sessionId}/learning-tree/retry`)
    return { ...state, note: state.note ? readNote(state.note) : null }
  },
  nodeConversation(sessionId: number, nodeId: string) {
    return request.get<any, LearningNodeConversation>(`/api/sessions/${sessionId}/learning-tree/nodes/${encodeURIComponent(nodeId)}`)
  },
  nodeSuggestions(sessionId: number, nodeId: string) {
    return request.post<any, { questions: string[] }>(`/api/sessions/${sessionId}/learning-tree/nodes/${encodeURIComponent(nodeId)}/suggestions`, {}, { timeout: 180000 })
  },
}
