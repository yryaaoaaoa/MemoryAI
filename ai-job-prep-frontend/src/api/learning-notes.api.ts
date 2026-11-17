import request from './request'

export type NoteStatus = 'PENDING' | 'REVIEW' | 'UNDERSTOOD'
export const noteStatusLabels: Record<NoteStatus, string> = { PENDING: '待弄懂', REVIEW: '待复习', UNDERSTOOD: '已理解' }
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
export interface NoteSource { sourceSessionId: number; sourceMessageIds: number[] }
export type NoteDraft = Pick<NoteFields, 'title' | 'confusion' | 'content'>
export type DraftRequest = { noteId?: number; currentDraft: NoteDraft } &
  ({ mode: 'BODY' } | ({ mode: 'MERGE' } & NoteSource))
export const notesApi = {
  async list(params: { query?: string; status?: string; page: number; size: number }) {
    const result = await request.get<any, { records: StoredNote[]; total: number }>('/api/learning-notes', { params })
    return { ...result, records: result.records.map(readNote) }
  },
  async get(id: number) { return readNote(await request.get<any, StoredNote>(`/api/learning-notes/${id}`)) },
  async create(body: NoteFields & NoteSource & { requestId: string }) {
    return readNote(await request.post<any, StoredNote>('/api/learning-notes', body))
  },
  async update(id: number, body: NoteFields & Partial<NoteSource> & { expectedVersion: number }) {
    return readNote(await request.put<any, StoredNote>(`/api/learning-notes/${id}`, body))
  },
  remove(id: number) { return request.delete(`/api/learning-notes/${id}`) },
  draft(body: DraftRequest) { return request.post<any, NoteDraft>('/api/learning-notes/draft', body, { timeout: 180000 }) },
  continueChat(id: number) { return request.post<any, number>(`/api/learning-notes/${id}/sessions`) },
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
