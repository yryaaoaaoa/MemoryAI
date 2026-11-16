<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useKnowledgeBaseStore } from '@/stores/knowledge-base.store'

const router = useRouter()
const store = useKnowledgeBaseStore()
const page = ref(1)
const size = ref(20)

async function loadList() {
  try { await store.fetchKBList(page.value, size.value) } catch { /* interceptor reports errors */ }
}

function changePage(value: number) {
  page.value = value
  void loadList()
}

function changeSize(value: number) {
  size.value = value
  page.value = 1
  void loadList()
}

onMounted(loadList)
</script>

<template>
  <div class="kb">
    <div class="kb-top">
      <h1 class="kb-h1">知识库</h1>
      <el-button type="primary" @click="router.push('/admin/knowledge-bases')">管理后台</el-button>
    </div>

    <div v-if="store.kbListLoading" class="kb-empty">加载中...</div>
    <div v-else-if="!store.kbList.length" class="kb-empty">暂无知识库，前往后台创建</div>

    <div v-else class="kb-grid">
      <router-link v-for="kb in store.kbList" :key="kb.id" class="kb-card glass-card" :to="`/admin/knowledge-bases/${kb.id}`">
        <h3>{{ kb.name }}</h3>
        <p v-if="kb.description">{{ kb.description }}</p>
        <div class="kb-meta">{{ kb.updatedAt?.slice(0, 10) || '' }}</div>
      </router-link>
    </div>
    <el-pagination v-if="store.kbTotal > 0" class="pagination"
      :current-page="page" :page-size="size" :total="store.kbTotal"
      :page-sizes="[10, 20, 50]" :disabled="store.kbListLoading"
      layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeSize" />
  </div>
</template>

<style scoped>
.pagination { margin-top: 20px; justify-content: flex-end; }
.kb { max-width: 800px; }
.kb-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; }
.kb-h1 { font-family: var(--font-display); font-size: 24px; font-weight: 800; }
.kb-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.kb-card {
  min-width: 0; overflow-wrap: anywhere; color: var(--text); text-decoration: none;
  padding: 20px 22px; cursor: pointer; transition: all 0.2s;
  border-radius: var(--rad); background: var(--bg-glass); border: 1px solid var(--border);
  backdrop-filter: blur(12px);
}
.kb-card:hover { border-color: var(--border-active); transform: translateY(-2px); }
.kb-card h3 { font-family: var(--font-display); font-size: 16px; margin-bottom: 4px; }
.kb-card p { font-size: 13px; color: var(--text-mut); margin-bottom: 8px; }
.kb-meta { font-size: 11px; color: var(--text-dim); font-family: var(--font-mono); }
.kb-empty { color: var(--text-dim); text-align: center; margin-top: 60px; }
@media (max-width: 600px) {
  .kb-grid { grid-template-columns: minmax(0, 1fr); }
  .kb-top { gap: 12px; flex-wrap: wrap; }
}
</style>
