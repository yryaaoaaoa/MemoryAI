<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { knowledgeBaseApi } from '@/api/knowledge-base.api'

const router = useRouter()
const kbs = ref<any[]>([])
const search = ref('')

onMounted(async () => {
  try { const res = await knowledgeBaseApi.list({ page: 1, size: 20 }); kbs.value = (res as any)?.records ?? [] } catch { /* ok */ }
})

const filtered = computed(() => {
  if (!search.value) return kbs.value
  const q = search.value.toLowerCase()
  return kbs.value.filter((kb: any) => kb.name?.toLowerCase().includes(q))
})
</script>

<template>
  <div class="kb">
    <div class="kb-top">
      <h1 class="kb-h1">知识库</h1>
      <el-button type="primary" @click="router.push('/admin/knowledge-bases')">管理后台</el-button>
    </div>

    <div v-if="!kbs.length" class="kb-empty">暂无知识库，前往后台创建</div>

    <div v-else class="kb-grid">
      <div v-for="kb in filtered" :key="kb.id" class="kb-card glass-card" @click="router.push(`/admin/knowledge-bases/${kb.id}`)">
        <h3>{{ kb.name }}</h3>
        <p v-if="kb.description">{{ kb.description }}</p>
        <div class="kb-meta">{{ kb.updatedAt?.slice(0, 10) || '' }}</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.kb { max-width: 800px; }
.kb-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.kb-h1 { font-family: var(--font-display); font-size: 24px; font-weight: 800; }
.kb-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.kb-card {
  padding: 20px 22px; cursor: pointer; transition: all 0.2s;
  border-radius: var(--rad); background: var(--bg-glass); border: 1px solid var(--border);
  backdrop-filter: blur(12px);
}
.kb-card:hover { border-color: var(--border-active); transform: translateY(-2px); }
.kb-card h3 { font-family: var(--font-display); font-size: 16px; margin-bottom: 4px; }
.kb-card p { font-size: 13px; color: var(--text-mut); margin-bottom: 8px; }
.kb-meta { font-size: 11px; color: var(--text-dim); font-family: var(--font-mono); }
.kb-empty { color: var(--text-dim); text-align: center; margin-top: 60px; }
</style>
