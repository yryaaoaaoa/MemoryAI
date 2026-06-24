import { ref } from 'vue'
import { defineStore } from 'pinia'
import { ElMessage } from 'element-plus'
import type { KnowledgeBase, Document } from '@/types'
import { knowledgeBaseApi } from '@/api/knowledge-base.api'
import { documentApi } from '@/api/document.api'

export const useKnowledgeBaseStore = defineStore('knowledgeBase', () => {
  const kbList = ref<KnowledgeBase[]>([])
  const currentKB = ref<KnowledgeBase | null>(null)
  const documents = ref<Document[]>([])
  const loading = ref(false)
  const total = ref(0)

  async function fetchKBList(page = 1, size = 10) {
    loading.value = true
    try {
      const res = await knowledgeBaseApi.list({ page, size })
      kbList.value = res.records
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  async function fetchKBById(id: string) {
    loading.value = true
    try {
      currentKB.value = await knowledgeBaseApi.getById(id)
    } finally {
      loading.value = false
    }
  }

  async function createKB(data: { name: string; description?: string }) {
    const kb = await knowledgeBaseApi.create(data)
    ElMessage.success('知识库创建成功')
    return kb
  }

  async function updateKB(id: string, data: { name: string; description?: string }) {
    const kb = await knowledgeBaseApi.update(id, data)
    ElMessage.success('知识库更新成功')
    return kb
  }

  async function deleteKB(id: string) {
    await knowledgeBaseApi.delete(id)
    ElMessage.success('知识库已删除')
  }

  async function fetchDocuments(kbId: string, page = 1, size = 10) {
    loading.value = true
    try {
      const res = await documentApi.listByKB(kbId, { page, size })
      documents.value = res.records
      total.value = res.total
    } finally {
      loading.value = false
    }
  }

  /**
   * 去重轮询：仅获取正在处理的特定文档的状态，
   * 然后将变更合并到现有列表中，
   * 而不会触发完整刷新或显示加载中提示。
   * 如果任何文档状态发生变化，则返回 true。
   */
  async function pollDocumentStatus(ids: string[]): Promise<boolean> {
    if (ids.length === 0) return false
    const results = await Promise.allSettled(
      ids.map((id) => documentApi.getStatus(id)),
    )
    let changed = false
    for (const r of results) {
      if (r.status !== 'fulfilled') continue
      const updated = r.value
      const idx = documents.value.findIndex((d) => d.id === updated.id)
      if (idx !== -1 && documents.value[idx].status !== updated.status) {
        changed = true
        documents.value[idx] = updated
      }
    }
    if (changed) {
      // 通过新的数组引用触发响应式更新
      documents.value = [...documents.value]
    }
    return changed
  }

  return {
    kbList,
    currentKB,
    documents,
    loading,
    total,
    fetchKBList,
    fetchKBById,
    createKB,
    updateKB,
    deleteKB,
    fetchDocuments,
    pollDocumentStatus,
  }
})
