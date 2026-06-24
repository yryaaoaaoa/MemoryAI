<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '@/stores/auth.store'

const route = useRoute()
const auth = useAuthStore()
const collapsed = ref(false)
const isLight = ref(localStorage.getItem('theme') === 'light')

function toggleTheme() {
  isLight.value = !isLight.value
  const mode = isLight.value ? 'light' : 'dark'
  document.documentElement.setAttribute('data-theme', mode)
  localStorage.setItem('theme', mode)
}

if (localStorage.getItem('theme') === 'light') {
  document.documentElement.setAttribute('data-theme', 'light')
} else {
  document.documentElement.setAttribute('data-theme', 'dark')
}

const sbStyle = computed(() => isLight.value ? {
  background: '#ffffff',
  backdropFilter: 'none',
  WebkitBackdropFilter: 'none',
  borderRightColor: '#e5e7eb',
  boxShadow: '1px 0 8px rgba(0,0,0,0.03)',
} : {})

const nav = [
  { path: '/ask', title: 'AI 问答', icon: '◆' },
  { path: '/quiz', title: '刷题', icon: '✎' },
  { path: '/interview', title: '模拟面试', icon: '◆' },
  { path: '/knowledge', title: '知识库', icon: '▣' },
  { path: '/resume', title: '简历解析', icon: '○' },
  { path: '/profile', title: '用户画像', icon: '◎' },
]
const isActive = (p: string) => route.path.startsWith(p)
</script>

<template>
  <div class="shell">
    <aside :class="['sb', { 'sb-fold': collapsed }]" :style="sbStyle">
      <div class="sb-brand" @click="$router.push('/ask')">
        <span class="sb-dot" />
        <span v-if="!collapsed" class="sb-name">MemorAI</span>
      </div>
      <nav class="sb-nav">
        <router-link v-for="m in nav" :key="m.path" :to="m.path" :class="['sb-link', isActive(m.path) ? 'on' : '']">
          <span class="sb-ico">{{ m.icon }}</span>
          <span v-if="!collapsed" class="sb-lbl">{{ m.title }}</span>
        </router-link>
      </nav>
      <div class="sb-bot">
        <router-link to="/admin/knowledge-bases" :class="['sb-link', route.path.startsWith('/admin/knowledge-bases') ? 'on' : '']">
          <span class="sb-ico">&#9881;</span>
          <span v-if="!collapsed" class="sb-lbl">知识库管理</span>
        </router-link>
        <router-link to="/admin/es" :class="['sb-link', route.path.startsWith('/admin/es') ? 'on' : '']">
          <span class="sb-ico">&#9776;</span>
          <span v-if="!collapsed" class="sb-lbl">ES 管理</span>
        </router-link>
        <router-link to="/admin/settings" :class="['sb-link', route.path.startsWith('/admin/settings') ? 'on' : '']">
          <span class="sb-ico">&#9881;</span>
          <span v-if="!collapsed" class="sb-lbl">检索配置</span>
        </router-link>
        <button class="sb-link" style="width:100%;border:none;background:none;cursor:pointer;font-family:inherit" @click="toggleTheme">
          <span class="sb-ico">{{ isLight ? '&#9728;' : '&#9789;' }}</span>
          <span v-if="!collapsed" class="sb-lbl">{{ isLight ? '浅色' : '深色' }}</span>
        </button>
        <button class="sb-link" style="width:100%;border:none;background:none;cursor:pointer;font-family:inherit" @click="collapsed=!collapsed">
          <span class="sb-ico">{{ collapsed ? '&#9656;' : '&#9666;' }}</span>
        </button>
        <!-- User info + logout -->
        <div v-if="!collapsed" class="sb-user">
          <span class="sb-user-name">{{ auth.user?.username ?? '...' }}</span>
          <button class="sb-logout" @click="auth.logout()">退出</button>
        </div>
        <button v-else class="sb-link" style="width:100%;border:none;background:none;cursor:pointer;font-family:inherit" @click="auth.logout()">
          <span class="sb-ico">&#9166;</span>
        </button>
      </div>
    </aside>
    <main class="main">
      <router-view />
    </main>
    <div class="ambient" />
  </div>
</template>

<style>
@import url('https://api.fontshare.com/v2/css?f[]=cabinet-grotesk@800&f[]=satoshi@400,500,700&display=swap');

:root {
  --bg-root: #08080c;
  --bg-glass: rgba(255,255,255,0.035);
  --bg-hover: rgba(255,255,255,0.06);
  --bg-input: rgba(255,255,255,0.04);
  --border: rgba(255,255,255,0.06);
  --border-active: rgba(56,189,248,0.25);
  --text: #e2e8f0;
  --text-mut: #64748b;
  --text-dim: #3b4050;
  --accent: #38bdf8;
  --accent-dim: #0284c7;
  --accent-glow: rgba(56,189,248,0.15);
  --green: #34d399;
  --red: #f87171;
  --amber: #fbbf24;
  --rad: 12px;
  --rad-sm: 8px;
  --font: 'Satoshi',-apple-system,BlinkMacSystemFont,sans-serif;
  --font-display: 'Cabinet Grotesk','Satoshi',sans-serif;
  --font-mono: 'JetBrains Mono','Fira Code',monospace;
}

* { box-sizing: border-box; margin: 0; padding: 0; }
body { background: var(--bg-root); color: var(--text); font-family: var(--font); font-size: 14px; line-height: 1.6; -webkit-font-smoothing: antialiased; }
#app { height: 100vh; overflow: hidden; }

::-webkit-scrollbar { width: 4px; }
::-webkit-scrollbar-track { background: transparent; }
::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.08); border-radius: 2px; }

.el-card {
  --el-card-bg-color: var(--bg-glass); --el-card-border-color: var(--border);
  border-radius: var(--rad) !important; backdrop-filter: blur(12px); -webkit-backdrop-filter: blur(12px);
}
.el-button--primary {
  --el-button-bg-color: var(--accent); --el-button-border-color: var(--accent);
  --el-button-hover-bg-color: var(--accent-dim); --el-button-hover-border-color: var(--accent-dim);
  border-radius: var(--rad-sm) !important;
}
.el-input__wrapper {
  background: var(--bg-input) !important; border: 1px solid var(--border) !important;
  box-shadow: none !important; border-radius: var(--rad-sm) !important; transition: border-color 0.2s, box-shadow 0.2s;
}
.el-input__wrapper:focus-within, .el-input__wrapper:hover { border-color: var(--border-active) !important; box-shadow: 0 0 0 3px var(--accent-glow) !important; }
.el-input__inner { color: var(--text) !important; }
.el-input__inner::placeholder { color: var(--text-dim) !important; }
.el-dialog { --el-dialog-bg-color: rgba(18,18,28,0.95); --el-dialog-border-color: var(--border); backdrop-filter: blur(20px); border-radius: var(--rad) !important; }
.el-message { --el-message-bg-color: rgba(18,18,28,0.9); backdrop-filter: blur(12px); }
.el-tag { --el-tag-bg-color: rgba(255,255,255,0.04); --el-tag-border-color: var(--border); border-radius: 6px !important; }
.el-progress-bar__outer { background: rgba(255,255,255,0.06) !important; border-radius: 2px !important; }
.el-progress-bar__inner { border-radius: 2px !important; }
.el-radio-button__inner { background: rgba(255,255,255,0.03) !important; border-color: var(--border) !important; color: var(--text-mut) !important; border-radius: var(--rad-sm) !important; }
.el-radio-button__original-radio:checked + .el-radio-button__inner { background: var(--accent) !important; color: #fff !important; }
</style>

<style scoped>
.shell { display: flex; height: 100vh; position: relative; }
.ambient {
  position: fixed; inset: 0; pointer-events: none; z-index: -1;
  background:
    radial-gradient(circle at 30% 20%, rgba(56,189,248,0.04), transparent 50%),
    radial-gradient(circle at 70% 80%, rgba(56,189,248,0.02), transparent 40%);
}
.sb {
  width: 200px; flex-shrink: 0; background: rgba(12,12,20,0.7);
  backdrop-filter: blur(20px); -webkit-backdrop-filter: blur(20px);
  border-right: 1px solid var(--border); display: flex; flex-direction: column;
  padding: 18px 10px; transition: width 0.25s;
}
.sb-fold { width: 52px; }
.sb-brand { display: flex; align-items: center; gap: 10px; padding: 6px 8px; margin-bottom: 32px; cursor: pointer; user-select: none; }
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
.sb-ico { font-size: 11px; width: 14px; text-align: center; flex-shrink: 0; }
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
.main { flex: 1; overflow-y: auto; padding: 36px 44px; }
</style>
