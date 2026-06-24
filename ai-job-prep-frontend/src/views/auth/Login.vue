<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '@/stores/auth.store'
import { ElMessage } from 'element-plus'

const auth = useAuthStore()
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const loading = ref(false)
const shaking = ref(false)

function toggleMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  username.value = ''
  password.value = ''
}

function triggerShake() {
  shaking.value = true
  setTimeout(() => shaking.value = false, 500)
}

async function submit() {
  if (!username.value.trim() || !password.value.trim()) {
    triggerShake()
    ElMessage.warning('请填写用户名和密码')
    return
  }
  if (mode.value === 'register' && password.value.trim().length < 6) {
    triggerShake()
    ElMessage.warning('密码至少 6 位')
    return
  }
  loading.value = true
  try {
    if (mode.value === 'login') {
      await auth.login(username.value.trim(), password.value)
      ElMessage.success('登录成功')
    } else {
      await auth.register(username.value.trim(), password.value)
      ElMessage.success('注册成功')
    }
  } catch {
    // error message already shown by request interceptor
    triggerShake()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth-root">
    <!-- Ambient Background -->
    <div class="bg-scene">
      <div class="bg-grid" />
      <div class="bg-orbs">
        <div class="bg-orb" />
        <div class="bg-orb" />
        <div class="bg-orb" />
      </div>
      <div class="bg-noise" />
    </div>

    <!-- Floating Particles -->
    <div class="particles">
      <div class="particle" /><div class="particle" /><div class="particle" />
      <div class="particle" /><div class="particle" /><div class="particle" />
      <div class="particle" /><div class="particle" />
    </div>

    <!-- Main Stage -->
    <div class="auth-stage">
      <!-- Left: Brand Narrative -->
      <div class="brand-zone">
        <div class="brand-logo">
          <div class="brand-dot" />
          <div class="brand-name">MemorAI</div>
        </div>
        <div class="brand-tagline">
          你的<em>AI 求职</em><br>私人备考教练
        </div>
        <p class="brand-desc">
          文档入库 &rarr; 知识管理 &rarr; 语义检索 &rarr; 智能出题 &rarr; 错题复习。
          全闭环备考系统，让每一次复习都精准高效。
        </p>
        <div class="brand-feats">
          <div class="feat-badge"><span class="fb-dot" /> 向量知识库</div>
          <div class="feat-badge"><span class="fb-dot" /> 智能出题</div>
          <div class="feat-badge"><span class="fb-dot" /> 错题本</div>
          <div class="feat-badge"><span class="fb-dot" /> 薄弱点分析</div>
        </div>
      </div>

      <!-- Right: Auth Card -->
      <div class="auth-wrap">
        <div :class="['auth-card', { shake: shaking }]">
          <h2 class="auth-title">{{ mode === 'login' ? '欢迎回来' : '创建账号' }}</h2>
          <p class="auth-sub">
            {{ mode === 'login' ? '登录你的 MemorAI 账号继续学习' : '加入 MemorAI，开启智能备考之旅' }}
          </p>

          <form class="auth-form" @submit.prevent="submit">
            <div class="input-group">
              <label>用户名</label>
              <input
                v-model="username"
                class="auth-input"
                :placeholder="mode === 'login' ? '输入用户名' : '2-50 个字符'"
                autocomplete="username"
              />
            </div>
            <div class="input-group">
              <label>密码</label>
              <input
                v-model="password"
                type="password"
                class="auth-input"
                :placeholder="mode === 'login' ? '输入密码' : '至少 6 位'"
                autocomplete="current-password"
              />
            </div>
            <button class="auth-btn" type="submit" :disabled="loading">
              {{ loading ? '请稍候...' : (mode === 'login' ? '登录' : '创建账号') }}
            </button>
          </form>

          <div class="auth-switch">
            <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
            <a href="#" @click.prevent="toggleMode">
              {{ mode === 'login' ? '立即注册' : '去登录' }}
            </a>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.auth-root {
  min-height: 100vh; position: relative; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
  background: var(--bg-root, #06080f);
}

/* ====== Background Layers ====== */
.bg-scene { position: fixed; inset: 0; pointer-events: none; z-index: 0; }

.bg-grid {
  position: absolute; inset: 0;
  background-image:
    linear-gradient(rgba(255,255,255,0.015) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255,255,255,0.015) 1px, transparent 1px);
  background-size: 64px 64px;
}

.bg-orbs { position: absolute; inset: 0; }
.bg-orb {
  position: absolute; border-radius: 50%; filter: blur(120px); opacity: 0.35;
  animation: orbDrift 18s ease-in-out infinite;
}
.bg-orb:nth-child(1) {
  width: 620px; height: 620px;
  background: radial-gradient(circle, rgba(56,189,248,0.25), transparent 70%);
  top: -15%; left: -10%;
}
.bg-orb:nth-child(2) {
  width: 480px; height: 480px;
  background: radial-gradient(circle, rgba(2,132,199,0.2), transparent 70%);
  bottom: -20%; right: -12%;
  animation-delay: -6s;
}
.bg-orb:nth-child(3) {
  width: 340px; height: 340px;
  background: radial-gradient(circle, rgba(56,189,248,0.12), transparent 70%);
  top: 40%; left: 55%;
  animation-delay: -12s;
}

@keyframes orbDrift {
  0%, 100% { transform: translate(0, 0) scale(1); }
  25% { transform: translate(60px, -40px) scale(1.08); }
  50% { transform: translate(-30px, 50px) scale(0.95); }
  75% { transform: translate(-50px, -30px) scale(1.05); }
}

.bg-noise {
  position: absolute; inset: 0; opacity: 0.03;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  background-size: 256px 256px;
}

/* ====== Floating Particles ====== */
.particles { position: fixed; inset: 0; pointer-events: none; z-index: 1; }
.particle {
  position: absolute; width: 4px; height: 4px; border-radius: 50%;
  background: var(--accent, #38bdf8); opacity: 0;
  animation: partFloat 12s ease-in-out infinite;
  box-shadow: 0 0 8px var(--accent-glow, rgba(56,189,248,0.18));
}
.particle:nth-child(1) { left: 10%; top: 20%; }
.particle:nth-child(2) { left: 25%; top: 12%; animation-delay: -3s; width: 6px; height: 6px; }
.particle:nth-child(3) { left: 72%; top: 18%; animation-delay: -6s; }
.particle:nth-child(4) { left: 85%; top: 60%; animation-delay: -9s; width: 5px; height: 5px; }
.particle:nth-child(5) { left: 15%; top: 75%; animation-delay: -2s; }
.particle:nth-child(6) { left: 55%; top: 80%; animation-delay: -7s; width: 7px; height: 7px; }
.particle:nth-child(7) { left: 40%; top: 30%; animation-delay: -4s; }
.particle:nth-child(8) { left: 90%; top: 35%; animation-delay: -10s; width: 3px; height: 3px; }

@keyframes partFloat {
  0%, 100% { opacity: 0; transform: translateY(0) scale(0.5); }
  20% { opacity: 0.7; transform: translateY(-30px) scale(1.2); }
  40% { opacity: 0.3; transform: translateY(-60px) scale(0.8); }
  60% { opacity: 0.6; transform: translateY(-20px) scale(1.1); }
  80% { opacity: 0.1; transform: translateY(-40px) scale(0.4); }
}

/* ====== Layout ====== */
.auth-stage {
  position: relative; z-index: 2; display: flex; gap: 80px;
  align-items: center; max-width: 1100px; width: 100%; padding: 40px;
}

/* ====== Left: Brand ====== */
.brand-zone { flex: 1; min-width: 380px; }

.brand-logo { display: flex; align-items: center; gap: 12px; margin-bottom: 40px; }
.brand-dot {
  width: 10px; height: 10px; border-radius: 50%;
  background: var(--accent, #38bdf8);
  box-shadow: 0 0 16px var(--accent-glow, rgba(56,189,248,0.18)),
              0 0 40px var(--accent-glow, rgba(56,189,248,0.18));
  animation: dotPulse 2.5s ease-in-out infinite;
}
@keyframes dotPulse {
  0%, 100% { box-shadow: 0 0 16px var(--accent-glow, rgba(56,189,248,0.18)), 0 0 40px var(--accent-glow, rgba(56,189,248,0.18)); }
  50% { box-shadow: 0 0 28px var(--accent-glow, rgba(56,189,248,0.18)), 0 0 60px rgba(56,189,248,0.3); }
}
.brand-name {
  font-family: var(--font-display); font-size: 22px; font-weight: 700;
  letter-spacing: -0.3px; color: var(--text, #e8ecf2);
}

.brand-tagline {
  font-family: var(--font-display); font-size: 44px; font-weight: 700;
  line-height: 1.15; letter-spacing: -1px; margin-bottom: 20px;
  color: var(--text, #e8ecf2);
}
.brand-tagline em {
  font-style: normal;
  background: linear-gradient(135deg, var(--accent, #38bdf8), #818cf8);
  -webkit-background-clip: text; -webkit-text-fill-color: transparent;
  background-clip: text;
}
.brand-desc {
  font-size: 15px; line-height: 1.8; color: var(--text-mut, #7b8ca0); max-width: 420px;
}

.brand-feats { display: flex; gap: 12px; margin-top: 36px; flex-wrap: wrap; }
.feat-badge {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 16px; border-radius: 24px; font-size: 12px;
  background: rgba(255,255,255,0.03); border: 1px solid var(--border, rgba(255,255,255,0.08));
  color: var(--text-mut, #7b8ca0); backdrop-filter: blur(8px);
}
.fb-dot {
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--accent, #38bdf8);
}

/* ====== Right: Card ====== */
.auth-wrap { flex: 0 0 400px; }

.auth-card {
  background: var(--card-bg, rgba(12,16,28,0.85));
  border: 1px solid var(--border, rgba(255,255,255,0.08));
  border-radius: 24px; padding: 44px 40px;
  backdrop-filter: blur(30px); -webkit-backdrop-filter: blur(30px);
  box-shadow: 0 20px 60px rgba(0,0,0,0.5), 0 0 0 1px rgba(255,255,255,0.03) inset;
  position: relative; overflow: hidden;
}
.auth-card::before {
  content: ''; position: absolute; top: -1px; left: 40px; right: 40px;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,0.08), transparent);
}
.auth-card::after {
  content: ''; position: absolute; top: 0; right: 0;
  width: 200px; height: 200px;
  background: radial-gradient(circle at 100% 0%, rgba(56,189,248,0.06), transparent 70%);
  pointer-events: none;
}

.auth-title {
  font-family: var(--font-display); font-size: 24px; font-weight: 600;
  margin-bottom: 6px; letter-spacing: -0.3px; color: var(--text, #e8ecf2);
}
.auth-sub {
  font-size: 13px; color: var(--text-mut, #7b8ca0); margin-bottom: 32px;
}

.auth-form { display: flex; flex-direction: column; gap: 16px; }

.input-group { position: relative; }
.input-group label {
  display: block; font-size: 11px; color: var(--text-mut, #7b8ca0);
  text-transform: uppercase; letter-spacing: 0.6px; margin-bottom: 6px;
  font-weight: 500;
}
.auth-input {
  width: 100%; padding: 13px 16px; font-size: 14px; font-family: var(--font, 'Satoshi', sans-serif);
  background: var(--input-bg, rgba(255,255,255,0.03));
  border: 1px solid var(--border, rgba(255,255,255,0.08));
  border-radius: 12px; color: var(--text, #e8ecf2); outline: none;
  transition: border-color 0.25s, box-shadow 0.25s, background 0.25s;
}
.auth-input:focus {
  border-color: var(--accent, #38bdf8);
  box-shadow: 0 0 0 4px var(--accent-glow, rgba(56,189,248,0.18));
  background: rgba(56,189,248,0.04);
}
.auth-input::placeholder { color: var(--text-dim, #3e4a5a); }

.auth-btn {
  margin-top: 8px; width: 100%; padding: 14px; font-size: 15px;
  font-family: var(--font, 'Satoshi', sans-serif); font-weight: 600;
  border: none; border-radius: 12px; cursor: pointer;
  background: linear-gradient(135deg, var(--accent, #38bdf8), #0369a1);
  color: #fff; transition: transform 0.15s, box-shadow 0.25s, opacity 0.2s;
  position: relative; overflow: hidden;
}
.auth-btn::after {
  content: ''; position: absolute; inset: 0;
  background: linear-gradient(135deg, rgba(255,255,255,0.15), transparent 60%);
}
.auth-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 8px 28px rgba(56,189,248,0.25);
}
.auth-btn:active:not(:disabled) { transform: translateY(0); }
.auth-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.auth-switch {
  text-align: center; margin-top: 22px; font-size: 13px;
  color: var(--text-mut, #7b8ca0);
}
.auth-switch a {
  color: var(--accent, #38bdf8); text-decoration: none; font-weight: 500;
  cursor: pointer; transition: opacity 0.15s;
  margin-left: 2px;
}
.auth-switch a:hover { opacity: 0.8; }

/* ====== Shake ====== */
@keyframes shakeAnim {
  0%, 100% { transform: translateX(0); }
  10%, 50%, 90% { transform: translateX(-4px); }
  30%, 70% { transform: translateX(4px); }
}
.shake { animation: shakeAnim 0.5s ease-in-out; }
</style>
