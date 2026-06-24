<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { resumeApi, parseStructured } from '@/api/resume.api'
import type { Resume, ResumeSection } from '@/api/resume.api'
import { ElMessage } from 'element-plus'

const uploading = ref(false)
const progress = ref(0)
const resumeList = ref<Resume[]>([])
const sectionsDialogVisible = ref(false)
const currentSections = ref<ResumeSection[]>([])
const currentFileName = ref('')

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
  try {
    resumeList.value = await resumeApi.list()
  } catch {
    // ignore
  }
}

async function handleUpload(param: { file: File }) {
  uploading.value = true
  progress.value = 0
  try {
    await resumeApi.upload(param.file, (p) => {
      progress.value = p
    })
    ElMessage.success('简历上传成功，后台解析中...')
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
    d.status !== 'READY' && d.status !== 'FAILED')
)

const doneList = computed(() =>
  resumeList.value.filter(d =>
    d.status === 'READY' || d.status === 'FAILED')
)

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
    skills: '',
    experience: 'success',
    projects: 'warning',
    education: 'info',
    certificates: '',
    objective: 'danger',
    self: '',
  }
  return map[type] || ''
}

onMounted(loadList)
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
        <el-table-column prop="fileName" label="文件名" min-width="200" />
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
        <el-table-column label="操作" width="120" align="center" fixed="right">
          <template #default="{ row }">
            <el-button
              text type="primary" size="small"
              :disabled="row.status !== 'READY'"
              @click="viewSections(row)"
            >
              查看解析
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="暂无解析记录" />
    </el-card>

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

.desc {
  color: #909399;
  font-size: 14px;
  margin: 8px 0 20px;
}

.upload-card {
  margin-bottom: 20px;
}

.upload-icon {
  margin-bottom: 8px;
  color: #409eff;
}

.upload-text {
  font-size: 14px;
  color: #606266;
}

.upload-text em {
  color: #409eff;
  font-style: normal;
}

.upload-tip {
  font-size: 12px;
  color: #909399;
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
  border-bottom: 1px solid #f0f0f0;
}

.doc-row:last-child {
  border-bottom: none;
}

.doc-name {
  font-size: 14px;
}

.section-item {
  padding: 12px;
  margin-bottom: 12px;
  background: #fafafa;
  border-radius: 6px;
  border: 1px solid #ebeef5;
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
  color: #303133;
}
</style>
