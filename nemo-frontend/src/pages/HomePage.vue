<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useSession } from '@/composables/useSession'
import { useParticleBackground } from '@/features/home/useParticleBackground'
import penguinLogo from '@/assets/home-penguin.png'

const canvas = ref<HTMLCanvasElement>()
const { reducedMotion } = useParticleBackground(canvas)
const session = useSession()
const accountLink = computed(() => session.user.value?.userRole === 'admin' ? '/admin/problems' : session.user.value ? '/me' : '/login')
const accountLabel = computed(() => session.user.value?.userRole === 'admin' ? '管理后台' : session.user.value ? '个人中心' : '登录')
let previousTitle = ''
onMounted(() => { previousTitle = document.title; document.title = 'nemo的算法小站' })
onBeforeUnmount(() => { document.title = previousTitle })
</script>

<template>
  <div class="home" :class="{ 'motion-reduced': reducedMotion }">
    <div class="ambient" aria-hidden="true">
      <div class="ambient-glow" />
      <div class="ambient-grid" />
      <canvas ref="canvas" class="particle-field" />
      <div class="orbit orbit-one" /><div class="orbit orbit-two" />
      <div class="floating-tile tile-braces"><svg viewBox="0 0 48 48"><path d="M18 9h-3c-3 0-4 2-4 5v5c0 3-2 5-5 5 3 0 5 2 5 5v5c0 3 1 5 4 5h3m12-30h3c3 0 4 2 4 5v5c0 3 2 5 5 5-3 0-5 2-5 5v5c0 3-1 5-4 5h-3" /></svg></div>
      <div class="floating-tile tile-code"><svg viewBox="0 0 48 48"><path d="m15 15-9 9 9 9m18-18 9 9-9 9m-6-24-6 30" /></svg></div>
      <div class="floating-tile tile-chart"><svg viewBox="0 0 48 48"><path d="M8 30h7v10H8zm11-10h7v20h-7zm11-12h7v32h-7z" /></svg></div>
      <div class="floating-tile tile-graph"><svg viewBox="0 0 48 48"><path d="m21 13-9 20m17-19 9 19M17 36h16" /><circle cx="25" cy="9" r="5" /><circle cx="10" cy="37" r="5" /><circle cx="39" cy="37" r="5" /></svg></div>
    </div>

    <header class="home-header">
      <RouterLink to="/" class="home-brand" aria-label="nemo的算法小站首页">
        <span class="home-monogram" aria-hidden="true"><img :src="penguinLogo" alt="" width="30" height="30" /></span>
        <span>nemo<span class="brand-divider">/</span><span class="brand-caption">算法小站</span></span>
      </RouterLink>
      <nav class="home-nav" aria-label="首页导航">
        <RouterLink to="/problems">题库</RouterLink>
        <RouterLink :to="accountLink" class="account-link" :class="{ 'is-login': !session.user.value }">{{ accountLabel }}<svg viewBox="0 0 20 20" aria-hidden="true"><path d="M5 10h10m-4-4 4 4-4 4" /></svg></RouterLink>
      </nav>
    </header>

    <main class="home-main">
      <section class="hero" aria-labelledby="home-title">
        <div class="hero-eyebrow"><span class="status-dot" />A LITTLE SPACE FOR BIG IDEAS</div>
        <h1 id="home-title"><span class="name">nemo</span><span class="title-rest">的算法小站</span><span class="title-dot" aria-hidden="true">.</span></h1>
        <p class="hero-description">让想法变成代码，让每一次练习都有收获。</p>
        <p class="hero-detail">从一行代码开始，探索算法的更多可能。</p>
        <RouterLink class="practice-button" to="/problems">
          <svg class="button-code" viewBox="0 0 24 24" aria-hidden="true"><path d="m8 7-5 5 5 5m8-10 5 5-5 5m-3-13-2 16" /></svg>
          <span>开启算法练习</span>
          <svg class="button-arrow" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14m-6-6 6 6-6 6" /></svg>
        </RouterLink>
        <div class="language-notes" aria-label="支持的编程语言"><span>Java</span><i /><span>Python</span><i /><span>C++</span></div>
      </section>

      <div class="home-bottom">
        <p class="home-motto"><span class="motto-line" />保持好奇，持续进步。</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
.home{--home-accent:#6495ff;position:relative;isolation:isolate;min-height:calc(100svh - 49px);display:flex;flex-direction:column;overflow:hidden;background:#0b1020;color:#f3f6ff}
.ambient{position:absolute;inset:0;z-index:-1;pointer-events:none;overflow:hidden}
.ambient-glow{position:absolute;inset:0;background:radial-gradient(ellipse at 50% 44%,rgba(57,92,180,.2),transparent 58%),radial-gradient(ellipse at 82% 18%,rgba(69,73,155,.15),transparent 45%),linear-gradient(130deg,#0b101c,#11192b 57%,#0b1020)}
.ambient-glow::before,.ambient-glow::after{content:'';position:absolute;width:80%;height:42%;background:linear-gradient(100deg,rgba(61,103,204,.13),transparent 75%);transform:rotate(34deg);left:-40%;top:30%;border-top:1px solid rgba(84,124,228,.12)}
.ambient-glow::after{left:auto;right:-42%;top:54%;transform:rotate(23deg);background:linear-gradient(270deg,rgba(87,78,187,.15),transparent 80%)}
.ambient-grid{position:absolute;inset:0;background-image:linear-gradient(rgba(123,151,211,.025) 1px,transparent 1px),linear-gradient(90deg,rgba(123,151,211,.025) 1px,transparent 1px);background-size:110px 110px;mask-image:radial-gradient(ellipse at center,#000,transparent 78%)}
.particle-field{position:absolute;inset:0;width:100%;height:100%;opacity:.9}
.orbit{position:absolute;top:50%;left:50%;width:min(1100px,83vw);height:min(530px,55vw);border:1px solid rgba(95,137,240,.24);border-radius:50%;transform:translate(-50%,-50%) rotate(-19deg);animation:orbit-breathe 16s ease-in-out infinite}
.orbit::before,.orbit::after{content:'';position:absolute;width:7px;height:7px;background:#568dff;border-radius:50%;box-shadow:0 0 18px rgba(75,128,255,.8)}
.orbit::before{left:8%;top:21%}.orbit::after{right:8%;bottom:20%;width:5px;height:5px}
.orbit-two{width:min(1510px,114vw);height:min(720px,73vw);border-color:rgba(89,128,216,.1);animation-delay:-8s}
.orbit-two::after{background:#9a80ff;box-shadow:0 0 15px rgba(151,117,255,.6)}
.floating-tile{position:absolute;display:grid;place-items:center;width:90px;height:90px;border-radius:17px;border:1px solid rgba(98,134,249,.45);background:linear-gradient(145deg,rgba(37,59,112,.55),rgba(28,31,61,.8));box-shadow:inset 0 1px 0 rgba(156,181,255,.1),0 16px 35px rgba(0,0,0,.17);color:#8c8bff;animation:tile-float 10s ease-in-out infinite;--tile-turn:13deg}
.floating-tile svg{width:43px;height:43px;fill:none;stroke:currentColor;stroke-width:2.2;stroke-linecap:round;stroke-linejoin:round}
.tile-braces{left:12%;top:24%;--tile-turn:13deg}.tile-code{left:9%;top:65%;--tile-turn:-15deg;animation-delay:-3s;color:#9579ff}.tile-chart{right:11%;top:22%;--tile-turn:12deg;animation-delay:-5s;color:#7795ff}.tile-chart svg{fill:currentColor;stroke:none}.tile-graph{right:8%;top:66%;--tile-turn:-12deg;animation-delay:-7s;color:#7b8dff}
.home-header{width:min(1400px,100%);margin:0 auto;padding:32px 48px;display:flex;align-items:center;justify-content:space-between;gap:20px}
.home-brand{display:flex;align-items:center;gap:12px;color:#e3e7ef;font-size:18px;font-weight:600;letter-spacing:.01em}
.home-monogram{display:grid;place-items:center;width:36px;height:36px;flex-shrink:0;border:1px solid #2b4077;border-radius:11px;background:linear-gradient(140deg,#263a70,#19284b);box-shadow:0 4px 18px rgba(32,73,175,.14)}
.home-monogram img{display:block;width:30px;height:30px;object-fit:contain}
.brand-divider{color:#555f72;margin:0 12px;font-weight:300}.brand-caption{color:#9fa8b9;font-weight:400;font-size:13px;letter-spacing:.06em}
.home-nav{display:flex;align-items:center;gap:32px;font-size:13px}.home-nav a{color:#a6afbe;transition:color .2s}.home-nav a:hover{color:var(--home-accent)}
.account-link{display:flex;align-items:center;gap:8px;padding:9px 16px;border:1px solid #30477b;border-radius:9px;background:linear-gradient(135deg,rgba(35,55,99,.55),rgba(21,30,51,.65))}
.home-nav .account-link.is-login{color:#fff;border-color:#779af7;background:linear-gradient(110deg,#416ce2,#6289f3);box-shadow:0 4px 20px rgba(50,88,188,.22);font-weight:600}.home-nav .account-link.is-login:hover{color:#fff;filter:brightness(1.12);border-color:#b2c7ff}.home-nav .account-link.is-login:active{filter:brightness(.95)}
.account-link svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:1.3;stroke-linecap:round;stroke-linejoin:round}
.home-main{flex:1;display:flex;flex-direction:column;width:100%}
.hero{flex:1;width:min(1080px,100%);margin:0 auto;padding:clamp(60px,11vh,130px) 32px clamp(76px,13vh,160px);display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;animation:hero-arrive .8s ease-out both}
.hero-eyebrow{display:flex;align-items:center;gap:10px;padding:8px 13px;border:1px solid rgba(112,153,244,.25);border-radius:20px;color:#b4c3e0;background:rgba(30,45,76,.6);box-shadow:inset 0 1px 0 rgba(159,186,255,.04);font:10px/1.3 Consolas,monospace;letter-spacing:.15em}
.status-dot{width:5px;height:5px;border-radius:50%;background:#8db6ff;box-shadow:0 0 9px rgba(141,182,255,.4)}
h1{margin:32px 0 22px;font-size:clamp(40px,5.1vw,78px);font-weight:650;line-height:1.25;letter-spacing:-.045em;text-wrap:balance}
.name{color:var(--home-accent);background:linear-gradient(110deg,#52a2ff,#7680ff);background-clip:text;-webkit-text-fill-color:transparent;font-family:Inter,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;font-size:1.08em;font-style:normal;font-weight:750;line-height:1;letter-spacing:-.055em;margin-right:8px}.title-rest{letter-spacing:-.035em;text-shadow:0 2px 24px rgba(200,217,255,.06)}.title-dot{color:#5688ff}
.hero-description{font-size:clamp(15px,1.3vw,18px);line-height:1.9;letter-spacing:.08em;color:#aeb7c8;margin:0}.hero-detail{font-size:13px;line-height:1.8;color:#788398;margin:8px 0 0;letter-spacing:.07em}
.practice-button{margin-top:40px;display:flex;align-items:center;justify-content:center;gap:14px;min-height:56px;padding:0 25px;border:1px solid #6d94ff;border-radius:11px;background:linear-gradient(115deg,#3478ff,#6079ff);color:#fff;font-size:15px;font-weight:650;box-shadow:inset 0 1px 0 rgba(255,255,255,.17),0 8px 32px rgba(44,91,224,.25);transition:transform .2s,filter .2s,box-shadow .2s}
.practice-button:hover{transform:translateY(-3px);filter:brightness(1.1);box-shadow:0 0 40px rgba(69,114,255,.23),0 12px 26px rgba(0,0,0,.2)}
.practice-button:focus-visible{outline:2px solid #c5d9ff;outline-offset:5px}
.practice-button svg{width:20px;height:20px;fill:none;stroke:currentColor;stroke-width:1.6;stroke-linecap:round;stroke-linejoin:round}.button-arrow{margin-left:7px;transition:transform .2s}.practice-button:hover .button-arrow{transform:translateX(3px)}
.language-notes{margin-top:22px;display:flex;align-items:center;gap:15px;color:#7c879c;font:11px/1.5 Consolas,monospace;letter-spacing:.02em}.language-notes i{width:2px;height:2px;background:#53627e;border-radius:50%}
.home-bottom{width:min(1400px,100%);margin:0 auto;padding:18px 48px 28px;display:flex;align-items:center;justify-content:space-between;gap:24px}
.home-motto{display:flex;align-items:center;gap:12px;margin:0;color:#8494af;font-size:11px;letter-spacing:.08em}.motto-line{width:24px;height:1px;background:#548aff;box-shadow:0 0 8px rgba(84,138,255,.25)}
.motion-reduced .orbit,.motion-reduced .floating-tile{animation:none}
@keyframes hero-arrive{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:translateY(0)}}
@keyframes orbit-breathe{0%,100%{opacity:.7}50%{opacity:1}}
@keyframes tile-float{0%,100%{transform:translateY(0) rotate(var(--tile-turn))}50%{transform:translateY(-12px) rotate(var(--tile-turn))}}
@media(min-width:1600px){.hero{padding-top:130px;padding-bottom:170px}}
@media(max-width:1000px){.floating-tile{width:70px;height:70px;opacity:.65}.floating-tile svg{width:34px;height:34px}.tile-braces{left:5%;top:21%}.tile-chart{right:5%;top:20%}.tile-code{left:4%;top:70%}.tile-graph{right:4%;top:71%}}
@media(max-width:767px){.home-header{padding:24px}.home-nav{gap:20px}.home-brand{gap:9px;font-size:16px}.brand-divider{margin:0 8px}.brand-caption{font-size:11px}.account-link{padding:8px 11px}.hero{padding:70px 24px 90px}.home-bottom{padding:18px 24px 25px}.orbit-one{width:135vw;height:90vw}.orbit-two{width:180vw;height:125vw}.hero-eyebrow{font-size:9px;letter-spacing:.1em}h1{margin-top:29px;margin-bottom:24px}.floating-tile{width:52px;height:52px;border-radius:12px;opacity:.45}.floating-tile svg{width:27px;height:27px}.tile-braces{left:-12px;top:22%}.tile-chart{right:-8px;top:19%}.tile-code{left:-8px;top:72%}.tile-graph{right:-10px;top:76%}}
@media(max-width:480px){.brand-caption,.brand-divider{display:none}.hero{padding:66px 20px 86px}h1{font-size:38px;line-height:1.35}.name{display:block;font-size:1.65em;margin:0 0 13px;letter-spacing:-.05em}.hero-description{max-width:280px;font-size:14px;letter-spacing:.03em}.hero-detail{font-size:11px;letter-spacing:.04em}.practice-button{min-height:54px;margin-top:32px}.home-motto{font-size:10px;gap:8px}.home-bottom{gap:16px}}
@media(prefers-reduced-motion:reduce){.hero,.orbit,.floating-tile{animation:none;transform:rotate(var(--tile-turn,0deg))}.orbit{transform:translate(-50%,-50%) rotate(-19deg)}.practice-button:hover{transform:none}}
</style>
