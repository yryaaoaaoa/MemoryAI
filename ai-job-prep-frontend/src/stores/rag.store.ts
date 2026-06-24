import { ref } from 'vue'
import { defineStore } from 'pinia'
import type { RAGSearchResult, RAGSearchQuery } from '@/types'
import { ragApi } from '@/api/rag.api'

export const useRagStore = defineStore('rag', () => {
  const query = ref('')
  const results = ref<RAGSearchResult[]>([])
  const isSearching = ref(false)

  async function search(q: RAGSearchQuery) {
    isSearching.value = true
    try {
      results.value = await ragApi.search(q)
    } finally {
      isSearching.value = false
    }
  }

  function clearResults() {
    results.value = []
  }

  return { query, results, isSearching, search, clearResults }
})
