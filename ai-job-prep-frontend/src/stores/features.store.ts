import { ref } from 'vue'
import { defineStore } from 'pinia'
import request from '@/api/request'

export const useFeaturesStore = defineStore('features', () => {
  const ragEnabled = ref(false)
  let loaded = false
  let pending: Promise<void> | undefined
  function load() {
    if (loaded) return Promise.resolve()
    if (!pending) pending = request.get<any, { ragEnabled: boolean }>('/api/features')
      .then(data => { ragEnabled.value = data.ragEnabled === true; loaded = true })
      .catch(() => { ragEnabled.value = false })
      .finally(() => { pending = undefined })
    return pending
  }
  return { ragEnabled, load }
})
