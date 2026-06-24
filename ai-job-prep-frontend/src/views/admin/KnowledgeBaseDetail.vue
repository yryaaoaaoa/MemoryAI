<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { useKnowledgeBaseStore } from '@/stores/knowledge-base.store'
import { documentApi } from '@/api/document.api'
import type { Document, DocumentChunk } from '@/types'

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useKnowledgeBaseStore()

const uploadDialogVisible = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const selectedFile = ref<File | null>(null)

const chunksDialogVisible = ref(false)
const chunksLoading = ref(false)
const currentDocChunks = ref<DocumentChunk[]>([])
const currentDocTitle = ref('')

function goBack() {
  router.push('/admin/knowledge-bases')
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1024 / 1024).toFixed(1) + ' MB'
}

const statusMap: Record<string, { label: string; type: string }> = {
  UPLOADING: { label: '上传中', type: 'info' },
  PARSING: { label: '解析中', type: 'info' },
  CHUNKING: { label: '切片中', type: 'info' },
  EMBEDDING: { label: '向量化', type: 'info' },
  READY: { label: '就绪', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
}

// --- Upload ---
function openUpload() {
  selectedFile.value = null
  uploadProgress.value = 0
  uploadDialogVisible.value = true
}

function onFileSelect(file: File) {
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !['pdf', 'md', 'markdown'].includes(ext)) {
    ElMessage.warning('仅支持 PDF 和 Markdown 文件')
    return false
  }
  selectedFile.value = file
  return true
}

async function handleUpload() {
  if (!selectedFile.value) return
  uploading.value = true
  uploadProgress.value = 0
  try {
    await documentApi.upload(props.id, selectedFile.value, (p) => {
      uploadProgress.value = p
    })
    ElMessage.success('上传成功，后台正在处理')
    uploadDialogVisible.value = false
    store.fetchDocuments(props.id)
  } catch {
    // error toast by interceptor
  } finally {
    uploading.value = false
  }
}

// --- Chunks ---
async function handleViewChunks(doc: Document) {
  currentDocTitle.value = doc.fileName
  chunksLoading.value = true
  chunksDialogVisible.value = true
  try {
    currentDocChunks.value = await documentApi.getChunks(doc.id)
  } catch {
    currentDocChunks.value = []
  } finally {
    chunksLoading.value = false
  }
}

// --- Delete ---
async function handleDeleteDoc(doc: Document) {
  try {
    await ElMessageBox.confirm(
      `确定要删除文档「${doc.fileName}」吗？关联的切片和向量数据将被一并清除。`,
      '删除确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    await documentApi.delete(doc.id)
    ElMessage.success('文档已删除')
    store.fetchDocuments(props.id)
  } catch {
    // cancelled
  }
}

// --- Poll for status updates on processing documents ---
const PROCESSING_STATUSES = ['UPLOADING', 'PARSING', 'CHUNKING', 'EMBEDDING'] as const

function hasProcessingDocs(): boolean {
  return store.documents.some((d) =>
    (PROCESSING_STATUSES as readonly string[]).includes(d.status),
  )
}

let pollTimer: ReturnType<typeof setInterval> | null = null
let pollingRequesting = false

/**
 * Only poll status of documents that are still processing,
 * then merge results into the store without replacing the entire list.
 * Deduplicates concurrent requests — skips if a poll is already in flight.
 */
async function pollProcessingStatus() {
  if (pollingRequesting) return
  const processingDocs = store.documents.filter((d) =>
    (PROCESSING_STATUSES as readonly string[]).includes(d.status),
  )
  if (processingDocs.length === 0) {
    stopPolling()
    return
  }

  pollingRequesting = true
  try {
    const results = await Promise.allSettled(
      processingDocs.map((d) => documentApi.getStatus(d.id)),
    )
    let changed = false
    for (let i = 0; i < results.length; i++) {
      const r = results[i]
      if (r.status === 'fulfilled') {
        const updated = r.value
        const idx = store.documents.findIndex((d) => d.id === updated.id)
        if (idx !== -1) {
          const prev = store.documents[idx].status
          if (prev !== updated.status) {
            store.documents[idx] = updated
            changed = true
          }
        }
      }
    }
    if (changed) {
      // trigger reactivity by replacing the ref
      store.documents = [...store.documents] as typeof store.documents
    }
    // stop if nothing is processing anymore
    if (!hasProcessingDocs()) {
      stopPolling()
    }
  } finally {
    pollingRequesting = false
  }
}

function startPolling() {
  if (pollTimer) return
  // kick off an immediate check, then poll every 3s
  pollProcessingStatus()
  pollTimer = setInterval(pollProcessingStatus, 3000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  pollingRequesting = false
}

onMounted(() => {
  store.fetchKBById(props.id)
  store.fetchDocuments(props.id)
})

onBeforeUnmount(() => {
  stopPolling()
})

watch(() => props.id, (newId) => {
  stopPolling()
  store.fetchKBById(newId)
  store.fetchDocuments(newId)
})

watch(() => store.documents, (docs) => {
  if (docs.some((d) => (PROCESSING_STATUSES as readonly string[]).includes(d.status))) {
    startPolling()
  } else {
    stopPolling()
  }
}, { deep: true })
</script>

<template>
  <div class="admin-detail">
    <div class="page-header">
      <el-button text @click="goBack">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <h2>{{ store.currentKB?.name ?? '加载中...' }}</h2>
      <el-tag v-if="store.currentKB" type="info" effect="plain" class="kb-stats">
        {{ store.currentKB.documentCount }} 文档 / {{ store.currentKB.chunkCount }} 切片
      </el-tag>
      <el-button type="primary" @click="openUpload">
        <el-icon><Upload /></el-icon>
        上传文档
      </el-button>
    </div>

    <p v-if="store.currentKB?.description" class="kb-desc">{{ store.currentKB.description }}</p>

    <el-table :data="store.documents" v-loading="store.loading" stripe>
      <el-table-column prop="fileName" label="文件名" min-width="200" />
      <el-table-column prop="fileType" label="类型" width="70" />
      <el-table-column label="大小" width="100">
        <template #default="{ row }">{{ formatFileSize(row.fileSize) }}</template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切片数" width="80" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusMap[row.status]?.type ?? 'info'" size="small">
            {{ statusMap[row.status]?.label ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <el-button
            type="primary"
            link
            :disabled="row.status !== 'READY'"
            @click="handleViewChunks(row)"
          >
            切片
          </el-button>
          <el-button type="danger" link @click="handleDeleteDoc(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Upload Dialog -->
    <el-dialog v-model="uploadDialogVisible" title="上传文档" width="480px" destroy-on-close>
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="true"
        :limit="1"
        accept=".pdf,.md,.markdown"
        :on-change="(u: UploadFile) => u.raw && onFileSelect(u.raw)"
        :on-exceed="() => ElMessage.warning('每次只能上传一个文件')"
      >
        <el-icon class="upload-icon" :size="48"><UploadFilled /></el-icon>
        <div class="upload-text">将文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">支持 PDF、Markdown（.md）文件</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button @click="uploadDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="uploading"
          :disabled="!selectedFile"
          @click="handleUpload"
        >
          {{ uploading ? `上传中 ${uploadProgress}%` : '开始上传' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- Chunks Dialog -->
    <el-dialog v-model="chunksDialogVisible" :title="`切片 - ${currentDocTitle}`" width="800px">
      <div v-loading="chunksLoading">
        <el-empty v-if="!chunksLoading && currentDocChunks.length === 0" description="暂无切片" />
        <div v-for="(chunk, idx) in currentDocChunks" :key="chunk.id" class="chunk-card">
          <div class="chunk-header">
            <span class="chunk-index">#{{ idx + 1 }}</span>
            <el-tag v-if="chunk.headingPath" size="small" type="info">
              {{ chunk.headingPath }}
            </el-tag>
          </div>
          <pre class="chunk-content">{{ chunk.content }}</pre>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.admin-detail {
  max-width: 1000px;
  margin: 0 auto;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 8px;
}

.page-header h2 {
  flex: 1;
}

.kb-stats {
  font-size: 13px;
}

.kb-desc {
  color: #909399;
  margin-bottom: 16px;
  padding-left: 4px;
}

.upload-icon {
  margin-bottom: 12px;
}

.upload-text {
  color: #606266;
  font-size: 14px;
}

.upload-tip {
  color: #909399;
  font-size: 12px;
  margin-top: 8px;
}

.chunk-card {
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
  background: #fafafa;
}

.chunk-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.chunk-index {
  font-weight: 600;
  font-size: 13px;
  color: #409eff;
}

.chunk-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: #303133;
}
</style>
