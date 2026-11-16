<script setup lang="ts">
import { ref, watch, onBeforeUnmount, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth.store'

import { useFeaturesStore } from '@/stores/features.store'

const features = useFeaturesStore()
const route = useRoute()
const auth = useAuthStore()
const mobileQuery = window.matchMedia('(max-width: 768px)')
const isMobile = ref(mobileQuery.matches)
const collapsed = ref(isMobile.value)
function updateViewport(event: MediaQueryListEvent) {
  isMobile.value = event.matches
  collapsed.value = event.matches
}
mobileQuery.addEventListener('change', updateViewport)
onBeforeUnmount(() => mobileQuery.removeEventListener('change', updateViewport))
watch(() => route.path, () => { if (isMobile.value) collapsed.value = true })
const isLight = ref(localStorage.getItem('theme') === 'light')

function toggleTheme() {
  isLight.value = !isLight.value
  const mode = isLight.value ? 'light' : 'dark'
  document.documentElement.setAttribute('data-theme', mode)
  localStorage.setItem('theme', mode)
}

const nav = computed(() => [
  { path: '/ask', title: 'AI 问答', icon: 'ChatDotRound' },
  { path: '/notes', title: '学习笔记', icon: 'Notebook' },
  { path: '/quiz', title: '刷题', icon: 'EditPen' },
  { path: '/interview', title: '模拟面试', icon: 'Microphone' },
  { path: '/knowledge', title: '知识库', icon: 'Collection' },
  { path: '/resume', title: '简历解析', icon: 'Document' },
  { path: '/profile', title: '用户画像', icon: 'User' },
].filter(item => features.ragEnabled || item.path !== '/knowledge'))
const isActive = (p: string) => route.path.startsWith(p)
</script>

<template>
  <div class="shell">
    <button v-if="isMobile && !collapsed" class="nav-backdrop" aria-label="收起导航" @click="collapsed = true" />
    <aside :class="['sb', { 'sb-fold': collapsed }]" @keydown.esc="collapsed = true">
      <router-link to="/ask" class="sb-brand" aria-label="MemorAI 首页">
        <span class="sb-dot" />
        <span v-if="!collapsed" class="sb-name">MemorAI</span>
      </router-link>
      <nav class="sb-nav" aria-label="主导航">
        <router-link v-for="m in nav" :key="m.path" :to="m.path" :aria-label="m.title" :title="collapsed ? m.title : undefined" :class="['sb-link', isActive(m.path) ? 'on' : '']">
          <el-icon class="sb-ico" aria-hidden="true"><component :is="m.icon" /></el-icon>
          <span v-if="!collapsed" class="sb-lbl">{{ m.title }}</span>
        </router-link>
      </nav>
      <div class="sb-bot">
        <router-link v-if="features.ragEnabled" to="/admin/knowledge-bases" aria-label="知识库管理" title="知识库管理" :class="['sb-link', route.path.startsWith('/admin/knowledge-bases') ? 'on' : '']">
          <el-icon class="sb-ico" aria-hidden="true"><FolderOpened /></el-icon>
          <span v-if="!collapsed" class="sb-lbl">知识库管理</span>
        </router-link>
        <router-link v-if="features.ragEnabled && auth.user?.role === 'admin'" to="/admin/es" aria-label="ES 管理" title="ES 管理" :class="['sb-link', route.path.startsWith('/admin/es') ? 'on' : '']">
          <el-icon class="sb-ico" aria-hidden="true"><DataLine /></el-icon>
          <span v-if="!collapsed" class="sb-lbl">ES 管理</span>
        </router-link>
        <router-link v-if="features.ragEnabled && auth.user?.role === 'admin'" to="/admin/settings" aria-label="检索配置" title="检索配置" :class="['sb-link', route.path.startsWith('/admin/settings') ? 'on' : '']">
          <el-icon class="sb-ico" aria-hidden="true"><Setting /></el-icon>
          <span v-if="!collapsed" class="sb-lbl">检索配置</span>
        </router-link>
        <button class="sb-link" :aria-label="isLight ? '切换深色主题' : '切换浅色主题'" :title="isLight ? '切换深色主题' : '切换浅色主题'" @click="toggleTheme">
          <el-icon class="sb-ico" aria-hidden="true"><Sunny v-if="isLight" /><Moon v-else /></el-icon>
          <span v-if="!collapsed" class="sb-lbl">{{ isLight ? '切换深色' : '切换浅色' }}</span>
        </button>
        <button class="sb-link" :aria-label="collapsed ? '展开导航' : '收起导航'" :title="collapsed ? '展开导航' : '收起导航'" :aria-expanded="!collapsed" @click="collapsed=!collapsed">
          <el-icon class="sb-ico" aria-hidden="true"><Expand v-if="collapsed" /><Fold v-else /></el-icon>
        </button>
        <!-- User info + logout -->
        <div v-if="!collapsed" class="sb-user">
          <span class="sb-user-name">{{ auth.user?.username ?? '...' }}</span>
          <button class="sb-logout" @click="auth.logout()">退出</button>
        </div>
        <button v-else class="sb-link" aria-label="退出登录" title="退出登录" @click="auth.logout()">
          <el-icon class="sb-ico" aria-hidden="true"><SwitchButton /></el-icon>
        </button>
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
    <div class="ambient" />
  </div>
</template>



<style scoped>
.shell { display: flex; height: 100vh; height: 100dvh; position: relative; }
.ambient {
  position: fixed; inset: 0; pointer-events: none; z-index: -1;
  background:
    radial-gradient(circle at 30% 20%, rgba(56,189,248,0.04), transparent 50%),
    radial-gradient(circle at 70% 80%, rgba(56,189,248,0.02), transparent 40%);
}
.sb {
  width: 200px; flex-shrink: 0; background: var(--bg-sidebar);
  backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
  border-right: 1px solid var(--border); display: flex; flex-direction: column;
  padding: 18px 10px; overflow-y: auto; transition: width 0.25s;
}
.sb-fold { width: 52px; }
.sb-brand { text-decoration: none; flex-shrink: 0; display: flex; align-items: center; gap: 10px; padding: 6px 8px; margin-bottom: 32px; cursor: pointer; user-select: none; }
.sb-dot {
  width: 8px; height: 8px; border-radius: 50%; background: var(--accent);
  box-shadow: 0 0 12px var(--accent-glow), 0 0 24px var(--accent-glow); transition: box-shadow 0.3s;
}
.sb-brand:hover .sb-dot { box-shadow: 0 0 16px var(--accent-glow), 0 0 36px var(--accent-glow); }
.sb-name { font-family: var(--font-display); font-size: 15px; font-weight: 800; color: var(--text); letter-spacing: -0.3px; }
.sb-nav { flex: 1; display: flex; flex-direction: column; gap: 1px; }
.sb-link {
  display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 10px;
  font-size: 13px; color: var(--text-mut); text-decoration: none; transition: all 0.15s;
}
.sb-link:hover { background: var(--bg-hover); color: var(--text); }
.sb-link.on { background: var(--bg-hover); color: var(--text); }
.sb-link.on .sb-ico { color: var(--accent); }
.sb-ico { font-size: 16px; width: 16px; text-align: center; flex-shrink: 0; }
.sb-lbl { white-space: nowrap; font-weight: 500; }
.sb-bot { padding-top: 12px; border-top: 1px solid var(--border); display: flex; flex-direction: column; gap: 1px; }
.sb-user {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  border-top: 1px solid var(--border); margin-top: 8px; padding-top: 12px;
}
.sb-user-name { font-size: 12px; color: var(--text-mut); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sb-logout {
  font-size: 11px; color: var(--text-dim); background: none; border: 1px solid var(--border);
  padding: 2px 8px; border-radius: 6px; cursor: pointer; font-family: var(--font);
  transition: all 0.15s;
}
.sb-logout:hover { border-color: var(--red); color: var(--red); }
.main { flex: 1; min-width: 0; overflow-y: auto; padding: 36px 44px; }
button.sb-link { width: 100%; border: none; background: transparent; cursor: pointer; font-family: inherit; text-align: left; }
button.sb-link:hover { background: var(--bg-hover); }
.sb-fold { padding-inline: 6px; }
.sb-link { min-height: 40px; }
.nav-backdrop { display: none; }
@media (max-width: 1024px) {
  .main { padding: 24px; }
}
@media (max-width: 768px) {
  .sb { position: absolute; inset: 0 auto 0 0; z-index: 30; background: var(--bg-panel); }
  .sb-brand { margin-bottom: 20px; }
  .main { margin-left: 52px; padding: 16px; }
  .nav-backdrop { display: block; position: absolute; inset: 0 0 0 52px; z-index: 20; border: none; background: rgba(0,0,0,.35); }
}
@media (max-height: 660px) {
  .sb { padding-block: 8px; }
  .sb-brand { margin-bottom: 8px; }
  .sb-link { min-height: 32px; padding-block: 5px; }
  .sb-bot { padding-top: 4px; }
}
</style>
