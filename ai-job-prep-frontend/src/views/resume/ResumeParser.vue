<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { resumeApi, parseStructured } from '@/api/resume.api'
import type { Resume, ResumeSection } from '@/api/resume.api'
import { ElMessage, ElMessageBox, type UploadFile, type UploadUserFile } from 'element-plus'

const uploading = ref(false)
const progress = ref(0)
const resumeList = ref<Resume[]>([])
const sectionsDialogVisible = ref(false)
const currentSections = ref<ResumeSection[]>([])
const currentFileName = ref('')
const retryTarget = ref<Resume | null>(null)
const retryVisible = ref(false)
const retryFiles = ref<UploadUserFile[]>([])
const retryFile = ref<File | null>(null)
const retrying = ref(false)
const deletingId = ref<string | null>(null)
let listVersion = 0
let disposed = false
let refreshTimer: ReturnType<typeof setTimeout> | undefined
const needsRetry = (status: string) => status === 'FAILED' || status === 'PENDING_RETRY'

const statusLabels: Record<string, string> = {
  UPLOADING: '上传中',
  PARSING: '提取文本中',
  ANALYZING: 'AI 分析中',
  READY: '解析完成',
  FAILED: '解析失败',
  PENDING_RETRY: '待重试',
}

const statusType: Record<string, string> = {
  UPLOADING: 'info',
  PARSING: 'warning',
  ANALYZING: 'warning',
  READY: 'success',
  FAILED: 'danger',
  PENDING_RETRY: 'info',
}

async function loadList() {
  const version = ++listVersion
  clearTimeout(refreshTimer)
  try {
    const list = await resumeApi.list()
    if (version === listVersion && !disposed) resumeList.value = list
  } catch {
    // Keep the current records so a transient refresh failure does not hide recovery actions.
  } finally {
    if (version === listVersion && !disposed && processingList.value.length) {
      refreshTimer = setTimeout(loadList, 3000)
    }
  }
}

async function handleUpload(param: { file: File }) {
  uploading.value = true
  progress.value = 0
  try {
    const resume = await resumeApi.upload(param.file, (p) => {
      progress.value = p
    })
    if (needsRetry(resume.status)) ElMessage.warning('这份简历解析失败，请在记录中选择重新解析。')
    else ElMessage.success(resume.status === 'READY' ? '这份简历已解析，可查看结果。' : '简历上传成功，后台解析中...')
    await loadList()
  } catch (e: any) {
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
    progress.value = 0
  }
}

function viewSections(resume: Resume) {
  currentSections.value = parseStructured(resume.structuredJson)
  currentFileName.value = resume.fileName
  sectionsDialogVisible.value = true
}

const processingList = computed(() =>
  resumeList.value.filter(d =>
    d.status !== 'READY' && !needsRetry(d.status))
)

const doneList = computed(() =>
  resumeList.value.filter(d =>
    d.status === 'READY' || needsRetry(d.status))
)

function openRetry(resume: Resume) {
  retryTarget.value = resume
  retryFiles.value = []
  retryFile.value = null
  retryVisible.value = true
}

function selectRetryFile(file: UploadFile) {
  retryFile.value = null
  if (!file.raw || !/\.(pdf|md|markdown)$/i.test(file.name)) {
    retryFiles.value = []
    ElMessage.warning('请选择 PDF 或 Markdown 文件')
    return
  }
  retryFile.value = file.raw
}

async function retryResume() {
  if (!retryTarget.value || !retryFile.value || retrying.value) return
  retrying.value = true
  ++listVersion
  clearTimeout(refreshTimer)
  try {
    const updated = await resumeApi.reanalyze(retryTarget.value.id, retryFile.value)
    resumeList.value = resumeList.value.map(r => r.id === updated.id ? updated : r)
    retryVisible.value = false
    retryFiles.value = []
    retryFile.value = null
    ElMessage.success('已重新提交解析')
    await loadList()
  } catch { /* Keep the selected file and dialog for retry; request interceptor reports the error. */ }
  finally {
    retrying.value = false
    clearTimeout(refreshTimer)
    if (!disposed && processingList.value.length) refreshTimer = setTimeout(loadList, 3000)
  }
}

async function deleteResume(resume: Resume) {
  if (deletingId.value !== null || retrying.value) return
  deletingId.value = resume.id
  try {
    await ElMessageBox.confirm(`删除简历「${resume.fileName}」？`, '删除简历', {
      confirmButtonText: '删除简历', cancelButtonText: '取消', type: 'warning',
    })
    ++listVersion
    await resumeApi.delete(resume.id)
    resumeList.value = resumeList.value.filter(r => r.id !== resume.id)
    ElMessage.success('简历已删除')
    await loadList()
  } catch { /* Cancellation or API error leaves the record available. */ }
  finally {
    deletingId.value = null
    clearTimeout(refreshTimer)
    if (!disposed && processingList.value.length) refreshTimer = setTimeout(loadList, 3000)
  }
}

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

function sectionLabel(type: string): string {
  const map: Record<string, string> = {
    skills: '专业技能',
    experience: '工作经历',
    projects: '项目经历',
    education: '教育背景',
    certificates: '证书',
    objective: '求职意向',
    self: '自我评价',
    raw: '全文',
  }
  return map[type] || type
}

function sectionColor(type: string): string {
  const map: Record<string, string> = {
    skills: 'primary',
    experience: 'success',
    projects: 'warning',
    education: 'info',
    certificates: 'primary',
    objective: 'danger',
    self: 'primary',
  }
  return map[type] || 'primary'
}

onMounted(loadList)
onBeforeUnmount(() => { disposed = true; ++listVersion; clearTimeout(refreshTimer) })
</script>

<template>
  <div class="resume">
    <h2>简历解析</h2>
    <p class="desc">上传简历（PDF / Markdown），系统自动解析章节并提取技能标签，用于个性化出题。</p>

    <!-- Upload -->
    <el-card class="upload-card" shadow="never">
      <el-upload
        drag
        :auto-upload="true"
        accept=".pdf,.md,.markdown"
        :show-file-list="false"
        :http-request="handleUpload"
        :disabled="uploading"
      >
        <el-icon class="upload-icon" :size="48"><UploadFilled /></el-icon>
        <div class="upload-text">
          <span>拖拽简历文件到此处，或 <em>点击上传</em></span>
        </div>
        <template #tip>
          <div class="upload-tip">支持 PDF、Markdown 格式</div>
        </template>
      </el-upload>

      <el-progress
        v-if="uploading"
        :percentage="progress"
        :stroke-width="6"
        class="upload-progress"
      />
    </el-card>

    <!-- Processing -->
    <el-card v-if="processingList.length" class="list-card" shadow="never">
      <template #header>处理中</template>
      <div v-for="r in processingList" :key="r.id" class="doc-row">
        <span class="doc-name">{{ r.fileName }}</span>
        <el-tag :type="statusType[r.status] || 'info'" size="small">
          {{ statusLabels[r.status] || r.status }}
        </el-tag>
      </div>
    </el-card>

    <!-- History -->
    <el-card class="list-card" shadow="never">
      <template #header>
        <span>解析记录</span>
        <el-button text type="primary" size="small" @click="loadList" style="float:right">
          刷新
        </el-button>
      </template>

      <el-table v-if="doneList.length" :data="doneList" stripe>
        <el-table-column label="文件名" min-width="200">
          <template #default="{ row }">
            <span>{{ row.fileName }}</span>
            <p v-if="needsRetry(row.status)" class="resume-error">{{ row.errorMsg || '解析未完成，请重新选择原文件重试。' }}</p>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType[row.status] || 'info'" size="small">
              {{ statusLabels[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ row.createdAt }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              text type="primary" size="small"
              v-if="row.status === 'READY'"
              @click="viewSections(row)"
            >
              查看解析
            </el-button>
            <template v-else-if="needsRetry(row.status)">
              <el-button text type="primary" size="small" :disabled="retrying || deletingId !== null" @click="openRetry(row)">重新解析</el-button>
              <el-button text type="danger" size="small" :loading="deletingId === row.id" :disabled="retrying || deletingId !== null" @click="deleteResume(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无解析记录" />
    </el-card>

    <el-dialog v-model="retryVisible" title="重新解析简历" width="480px"
      :close-on-click-modal="!retrying" :close-on-press-escape="!retrying" :show-close="!retrying">
      <p>重新选择「{{ retryTarget?.fileName }}」的原文件。</p>
      <el-upload v-model:file-list="retryFiles" :auto-upload="false" :limit="1" accept=".pdf,.md,.markdown"
        :disabled="retrying" :on-change="selectRetryFile" :on-remove="() => { retryFile = null }"
        :on-exceed="() => ElMessage.warning('请先移除已选文件')">
        <el-button :disabled="retrying">选择简历文件</el-button>
      </el-upload>
      <template #footer>
        <el-button :disabled="retrying" @click="retryVisible = false">取消</el-button>
        <el-button type="primary" :loading="retrying" :disabled="!retryFile || retrying" @click="retryResume">重新解析</el-button>
      </template>
    </el-dialog>

    <!-- Sections dialog -->
    <el-dialog v-model="sectionsDialogVisible" :title="currentFileName + ' - 解析结果'" width="700px">
      <div v-for="sec in currentSections" :key="sec.type" class="section-item">
        <div class="section-header">
          <el-tag :type="sectionColor(sec.type)" effect="plain">
            {{ sectionLabel(sec.type) }}
          </el-tag>
          <el-tag
            v-for="tag in sec.tags" :key="tag"
            size="small"
            style="margin-left:6px"
          >
            {{ tag }}
          </el-tag>
        </div>
        <pre class="section-content">{{ sec.content }}</pre>
      </div>
      <template #footer>
        <el-button @click="sectionsDialogVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.resume {
  max-width: 800px;
  margin: 0 auto;
}
.resume-error { color: var(--red); font-size: 13px; white-space: normal; overflow-wrap: anywhere; }

.desc {
  color: var(--text-mut);
  font-size: 14px;
  margin: 8px 0 20px;
}

.upload-card {
  margin-bottom: 20px;
}

.upload-icon {
  margin-bottom: 8px;
  color: var(--accent);
}

.upload-text {
  font-size: 14px;
  color: var(--text);
}

.upload-text em {
  color: var(--accent);
  font-style: normal;
}

.upload-tip {
  font-size: 12px;
  color: var(--text-mut);
  margin-top: 6px;
}

.upload-progress {
  margin-top: 16px;
}

.list-card {
  margin-bottom: 16px;
}

.doc-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 0;
  border-bottom: 1px solid var(--border);
  gap: 12px;
}

.doc-row:last-child {
  border-bottom: none;
}

.doc-name {
  font-size: 14px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.section-item {
  padding: 12px;
  margin-bottom: 12px;
  background: var(--bg-input);
  border-radius: 6px;
  border: 1px solid var(--border);
}

.section-header {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.section-content {
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
  color: var(--text);
}

@media (max-width: 600px) {
  .resume :deep(.el-card__body) { padding: 12px; }
  .resume :deep(.el-upload-dragger) { padding: 24px 12px; }
  .doc-row { flex-wrap: wrap; }
}
</style>
