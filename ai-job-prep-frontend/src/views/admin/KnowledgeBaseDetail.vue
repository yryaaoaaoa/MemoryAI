<script setup lang="ts">
import { ref, computed, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox, ElMessage } from 'element-plus'
import type { UploadFile, UploadUserFile } from 'element-plus'
import { useKnowledgeBaseStore } from '@/stores/knowledge-base.store'
import { documentApi } from '@/api/document.api'
import type { Document, DocumentChunk } from '@/types'

const props = defineProps<{ id: string }>()
const router = useRouter()
const store = useKnowledgeBaseStore()

const uploadDialogVisible = ref(false)
const uploading = ref(false)
const uploadProgress = ref(0)
const uploadFiles = ref<UploadUserFile[]>([])
const selectedFile = computed(() => uploadFiles.value[0]?.raw ?? null)
const page = ref(1)
const size = ref(10)
const documentsError = ref(false)
let mounted = true
let documentLoadVersion = 0

async function loadDocuments() {
  const version = ++documentLoadVersion
  const kbId = props.id
  documentsError.value = false
  try {
    const accepted = await store.fetchDocuments(kbId, page.value, size.value)
    if (mounted && version === documentLoadVersion && props.id === kbId && accepted && page.value > 1 && !store.documents.length && store.documentsTotal > 0) {
      page.value = Math.ceil(store.documentsTotal / size.value)
      await store.fetchDocuments(kbId, page.value, size.value)
    }
  } catch {
    if (mounted && version === documentLoadVersion && props.id === kbId) documentsError.value = true
  }
}

function changePage(value: number) {
  page.value = value
  void loadDocuments()
}

function changeSize(value: number) {
  size.value = value
  page.value = 1
  void loadDocuments()
}

async function refreshKB() {
  try { await store.fetchKBById(props.id) } catch { /* interceptor reports the error */ }
}

const chunksDialogVisible = ref(false)
const chunksLoading = ref(false)
const currentDocChunks = ref<DocumentChunk[]>([])
const currentDocTitle = ref('')
let chunksRequest = 0
watch(chunksDialogVisible, visible => { if (!visible) ++chunksRequest })

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
  uploadFiles.value = []
  uploadProgress.value = 0
  uploadDialogVisible.value = true
}

function onFileSelect(upload: UploadFile) {
  const file = upload.raw
  if (!file) return
  const ext = file.name.split('.').pop()?.toLowerCase()
  if (!ext || !['pdf', 'md', 'markdown'].includes(ext)) {
    ElMessage.warning('仅支持 PDF 和 Markdown 文件')
    uploadFiles.value = []
    return
  }
  uploadFiles.value = [upload]
}

function clearUpload() {
  uploadFiles.value = []
  uploadProgress.value = 0
}

watch(uploadDialogVisible, (visible) => { if (!visible) clearUpload() })

async function handleUpload() {
  if (!selectedFile.value || uploading.value || !store.currentKB) return
  const kbId = props.id
  const file = selectedFile.value
  uploading.value = true
  uploadProgress.value = 0
  try {
    await documentApi.upload(kbId, file, (p) => {
      uploadProgress.value = p
    })
    if (!mounted || props.id !== kbId) return
    ElMessage.success('上传成功，后台正在处理')
    uploadDialogVisible.value = false
    page.value = 1
    await Promise.all([loadDocuments(), refreshKB()])
  } catch {
    // error toast by interceptor
  } finally {
    uploading.value = false
  }
}

// --- Chunks ---
async function handleViewChunks(doc: Document) {
  const request = ++chunksRequest
  currentDocChunks.value = []
  currentDocTitle.value = doc.fileName
  chunksLoading.value = true
  chunksDialogVisible.value = true
  try {
    const chunks = await documentApi.getChunks(doc.id)
    if (request !== chunksRequest || !mounted) return
    currentDocChunks.value = chunks
  } catch {
    if (request === chunksRequest) currentDocChunks.value = []
  } finally {
    if (request === chunksRequest) chunksLoading.value = false
  }
}

// --- Delete ---
async function handleDeleteDoc(doc: Document) {
  const kbId = props.id
  if (store.documentsLoading || String(doc.kbId) !== String(kbId)) return
  try {
    await ElMessageBox.confirm(
      `确定要删除文档「${doc.fileName}」吗？关联的切片和向量数据将被一并清除。`,
      '删除确认',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' },
    )
    if (!mounted || props.id !== kbId || !store.documents.some(item => item.id === doc.id)) return
    await documentApi.delete(doc.id)
    ElMessage.success('文档已删除')
    if (!mounted || props.id !== kbId) return
    if (store.documents.length === 1 && page.value > 1) page.value--
    await Promise.all([loadDocuments(), refreshKB()])
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
    const changed = await store.pollDocumentStatus(processingDocs.map(doc => doc.id))
    if (changed && mounted) await refreshKB()
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
  pollTimer = setInterval(pollProcessingStatus, 3000)
  void pollProcessingStatus()
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
  pollingRequesting = false
}

onBeforeUnmount(() => {
  chunksRequest++
  mounted = false
  stopPolling()
  store.clearDetail()
})

watch(() => props.id, () => {
  chunksRequest++
  stopPolling()
  store.clearDetail()
  page.value = 1
  uploadDialogVisible.value = false
  chunksDialogVisible.value = false
  currentDocChunks.value = []
  void refreshKB()
  void loadDocuments()
}, { immediate: true })

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
      <h2>{{ store.currentKB?.name ?? (store.kbLoading ? '加载中...' : '知识库不可用') }}</h2>
      <el-tag v-if="store.currentKB" type="info" effect="plain" class="kb-stats">
        {{ store.currentKB.documentCount }} 文档 / {{ store.currentKB.chunkCount }} 切片
      </el-tag>
      <el-button type="primary" :disabled="!store.currentKB || store.kbLoading" @click="openUpload">
        <el-icon><Upload /></el-icon>
        上传文档
      </el-button>
    </div>

    <p v-if="store.currentKB?.description" class="kb-desc">{{ store.currentKB.description }}</p>

    <p v-if="documentsError" role="alert">文档加载失败。<el-button text @click="loadDocuments">重新加载</el-button></p>
    <el-table :data="store.documents" v-loading="store.documentsLoading" stripe>
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

    <el-pagination v-if="store.documentsTotal > 0" class="pagination"
      :current-page="page" :page-size="size" :total="store.documentsTotal"
      :page-sizes="[10, 20, 50]" :disabled="store.documentsLoading"
      layout="total, sizes, prev, pager, next" @current-change="changePage" @size-change="changeSize" />

    <!-- Upload Dialog -->
    <el-dialog v-model="uploadDialogVisible" title="上传文档" width="480px" destroy-on-close
      :show-close="!uploading" :close-on-click-modal="!uploading" :close-on-press-escape="!uploading">
      <el-upload
        drag
        :auto-upload="false"
        :show-file-list="true"
        v-model:file-list="uploadFiles"
        :disabled="uploading"
        :limit="1"
        accept=".pdf,.md,.markdown"
        :on-change="onFileSelect"
        :on-remove="clearUpload"
        :on-exceed="() => ElMessage.warning('每次只能上传一个文件')"
      >
        <el-icon class="upload-icon" :size="48"><UploadFilled /></el-icon>
        <div class="upload-text">将文件拖到此处，或<em>点击选择</em></div>
        <template #tip>
          <div class="upload-tip">支持 PDF、Markdown（.md）文件</div>
        </template>
      </el-upload>
      <template #footer>
        <el-button :disabled="uploading" @click="uploadDialogVisible = false">取消</el-button>
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
.pagination { margin-top: 20px; justify-content: flex-end; }
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
  color: var(--text-dim);
  margin-bottom: 16px;
  padding-left: 4px;
}

.upload-icon {
  margin-bottom: 12px;
}

.upload-text {
  color: var(--text-mut);
  font-size: 14px;
}

.upload-tip {
  color: var(--text-dim);
  font-size: 12px;
  margin-top: 8px;
}

.chunk-card {
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--bg-glass);
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
  color: var(--accent);
}

.chunk-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text);
}
</style>
