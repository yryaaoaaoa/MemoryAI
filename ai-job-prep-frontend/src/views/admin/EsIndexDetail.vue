<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import { esApi } from '@/api/es.api'
import type { EsIndexInfo, EsDocumentVO } from '@/types'

const props = defineProps<{ index: string }>()
const router = useRouter()

const indexName = decodeURIComponent(props.index)

// --- Index Info ---
const indexInfo = ref<EsIndexInfo | null>(null)
const infoLoading = ref(false)

// --- Mapping ---
const mapping = ref<Record<string, string>>({})
const mappingLoading = ref(false)

// --- Documents ---
const docs = ref<EsDocumentVO[]>([])
const docsTotal = ref(0)
const docsPage = ref(1)
const docsSize = ref(20)
const docsLoading = ref(false)
const searchQuery = ref('')

// --- Detail Dialog ---
const detailVisible = ref(false)
const detailDoc = ref<Record<string, unknown> | null>(null)
const detailSource = ref('')
const detailLoading = ref(false)

const healthColor: Record<string, string> = {
  green: 'success',
  yellow: 'warning',
  red: 'danger',
}
const healthLabel: Record<string, string> = {
  green: '健康',
  yellow: '警告',
  red: '异常',
}

function formatStorage(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

function getEmbeddingInfo(source: Record<string, unknown> | undefined): { dim: number; dimLabel: string; preview: string; valid: boolean } | null {
  const emb = source?.embedding
  if (!Array.isArray(emb)) return null
  const dim = emb.length
  const valid = dim > 0 && emb.some(v => Number(v) !== 0)
  const firstFew = emb.slice(0, 3).map(v => (Number(v)).toFixed(4))
  return { dim, dimLabel: `[${dim}]`, preview: `[${firstFew.join(', ')}, ...]`, valid }
}

function goBack() {
  router.push('/admin/es')
}

// --- Fetch Index Info ---
async function fetchIndexInfo() {
  infoLoading.value = true
  try {
    indexInfo.value = await esApi.getIndexInfo(indexName)
  } catch {
    indexInfo.value = null
  } finally {
    infoLoading.value = false
  }
}

// --- Fetch Mapping ---
async function fetchMapping() {
  mappingLoading.value = true
  try {
    mapping.value = await esApi.getMapping(indexName)
  } catch {
    mapping.value = {}
  } finally {
    mappingLoading.value = false
  }
}

// --- Fetch Documents ---
async function fetchDocs() {
  docsLoading.value = true
  try {
    const params: { page: number; size: number; query?: string } = {
      page: docsPage.value,
      size: docsSize.value,
    }
    if (searchQuery.value.trim()) {
      params.query = searchQuery.value.trim()
    }
    const result = await esApi.listDocs(indexName, params)
    docs.value = result.records
    docsTotal.value = result.total
  } catch {
    docs.value = []
    docsTotal.value = 0
  } finally {
    docsLoading.value = false
  }
}

function onPageChange(page: number) {
  docsPage.value = page
  fetchDocs()
}

function onSearch() {
  docsPage.value = 1
  fetchDocs()
}

// --- Detail Dialog ---
async function openDetail(doc: EsDocumentVO) {
  detailLoading.value = true
  detailVisible.value = true
  try {
    const full = await esApi.getDoc(indexName, doc.id)
    detailDoc.value = full
    detailSource.value = JSON.stringify(full, null, 2)
  } catch {
    detailDoc.value = null
    detailSource.value = '加载失败'
  } finally {
    detailLoading.value = false
  }
}

// --- Delete Document ---
async function handleDeleteDoc(doc: EsDocumentVO) {
  try {
    await ElMessageBox.confirm(
      `确定要删除文档「${doc.id}」吗？`,
      '删除文档',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await esApi.deleteDoc(indexName, doc.id)
    ElMessage.success('文档已删除')
    await fetchDocs()
  } catch {
    // cancelled
  }
}

// --- Clear All Documents ---
async function handleClearDocs() {
  try {
    await ElMessageBox.confirm(
      `确定要清空索引「${indexName}」的所有文档吗？\n索引本身会保留，但全部 ${docsTotal} 条文档将被删除且不可恢复。`,
      '清空文档',
      {
        confirmButtonText: '确认清空',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      },
    )
    const deleted = await esApi.deleteByQuery(indexName, '*')
    ElMessage.success(`已删除 ${deleted} 条文档`)
    await fetchDocs()
    await fetchIndexInfo()
  } catch {
    // cancelled or error
  }
}

// --- Delete Entire Index ---
async function handleDeleteIndex() {
  try {
    await ElMessageBox.confirm(
      `确定要删除整个索引「${indexName}」吗？\n该操作将删除索引内所有数据，且不可恢复。`,
      '删除索引',
      {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
        confirmButtonClass: 'el-button--danger',
      },
    )
    await esApi.deleteIndex(indexName)
    ElMessage.success('索引已删除')
    router.push('/admin/es')
  } catch {
    // cancelled
  }
}

onMounted(() => {
  fetchIndexInfo()
  fetchMapping()
  fetchDocs()
})
</script>

<template>
  <div class="admin-detail">
    <!-- Header -->
    <div class="page-header">
      <el-button text @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <h2>{{ indexName }}</h2>
      <el-tag
        v-if="indexInfo"
        :type="healthColor[indexInfo.health] ?? 'info'"
        effect="dark"
        size="small"
      >
        {{ healthLabel[indexInfo.health] ?? indexInfo.health }}
      </el-tag>
      <span v-if="indexInfo" class="stats-text">
        {{ indexInfo.docCount }} 文档 / {{ formatStorage(indexInfo.storageSizeBytes) }}
      </span>
      <div class="header-actions">
        <el-button @click="fetchDocs" :loading="docsLoading" size="small">
          <el-icon><Refresh /></el-icon>
        </el-button>
        <el-button type="warning" size="small" @click="handleClearDocs">
          清空文档
        </el-button>
        <el-button type="danger" size="small" @click="handleDeleteIndex">
          删除索引
        </el-button>
      </div>
    </div>

    <!-- Mapping Section -->
    <el-card class="section" shadow="never">
      <template #header>
        <span>Mapping</span>
      </template>
      <el-table :data="Object.entries(mapping)" v-loading="mappingLoading" stripe size="small">
        <el-table-column label="字段名" min-width="200">
          <template #default="{ row }">
            <code class="field-name">{{ row[0] }}</code>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="200">
          <template #default="{ row }">
            <el-tag type="info" size="small">{{ row[1] }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!mappingLoading && Object.keys(mapping).length === 0" description="无 mapping" />
    </el-card>

    <!-- Documents Section -->
    <el-card class="section" shadow="never">
      <template #header>
        <div class="doc-header">
          <span>文档（{{ docsTotal }}）</span>
          <div class="doc-search">
            <el-input
              v-model="searchQuery"
              placeholder="搜索文档内容..."
              size="small"
              clearable
              style="width: 260px"
              @keyup.enter="onSearch"
            >
              <template #prefix>
                <el-icon><Search /></el-icon>
              </template>
            </el-input>
            <el-button size="small" type="primary" @click="onSearch">搜索</el-button>
          </div>
        </div>
      </template>

      <el-table :data="docs" v-loading="docsLoading" stripe size="small" style="width: 100%">
        <el-table-column label="ID" min-width="200">
          <template #default="{ row }">
            <code class="doc-id">{{ row.id }}</code>
          </template>
        </el-table-column>
        <el-table-column prop="source.kb_id" label="知识库" width="70" />
        <el-table-column prop="source.doc_id" label="文档 ID" width="80" />
        <el-table-column prop="source.chunk_index" label="序号" width="60" />
        <el-table-column label="标题路径" width="160">
          <template #default="{ row }">
            <span class="heading-path">{{ row.source?.heading_path as string ?? '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内容预览" min-width="280">
          <template #default="{ row }">
            <div class="doc-preview">
              {{ (row.source?.content as string ?? JSON.stringify(row.source)).slice(0, 180) }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="向量" width="150">
          <template #default="{ row }">
            <div v-if="getEmbeddingInfo(row.source)" class="emb-info">
              <el-tag :type="getEmbeddingInfo(row.source)!.valid ? 'success' : 'danger'" size="small" effect="plain">
                {{ getEmbeddingInfo(row.source)!.dimLabel }}
              </el-tag>
              <span class="emb-preview">{{ getEmbeddingInfo(row.source)!.preview }}</span>
            </div>
            <el-tag v-else type="warning" size="small">无向量</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDetail(row)">详情</el-button>
            <el-button type="danger" link size="small" @click="handleDeleteDoc(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap" v-if="docsTotal > 0">
        <el-pagination
          v-model:current-page="docsPage"
          v-model:page-size="docsSize"
          :total="docsTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="onPageChange"
          @size-change="onPageChange"
        />
      </div>
    </el-card>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailVisible" title="文档详情" width="800px" top="5vh">
      <div v-loading="detailLoading">
        <pre class="json-viewer">{{ detailSource }}</pre>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-detail {
  max-width: 1200px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.page-header h2 {
  font-size: 18px;
  font-family: monospace;
}

.stats-text {
  color: #909399;
  font-size: 13px;
}

.header-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.section {
  margin-bottom: 16px;
}

.field-name {
  background: #f5f7fa;
  padding: 2px 6px;
  border-radius: 3px;
  font-size: 13px;
}

.doc-id {
  font-size: 12px;
  color: #606266;
}

.doc-preview {
  font-size: 13px;
  line-height: 1.5;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.doc-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.doc-search {
  display: flex;
  gap: 8px;
  align-items: center;
}

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.heading-path {
  font-size: 12px;
  color: #909399;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
  max-width: 160px;
}

.emb-info {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
}

.emb-preview {
  font-size: 11px;
  color: #909399;
  font-family: monospace;
}

.json-viewer {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 16px;
  border-radius: 6px;
  font-size: 13px;
  line-height: 1.5;
  overflow: auto;
  max-height: 70vh;
  margin: 0;
  white-space: pre;
}
</style>
