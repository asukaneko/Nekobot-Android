/* ============================================================
   Nekobot 官网交互脚本
   ============================================================ */
(function () {
  'use strict';

  const $ = (sel, ctx = document) => ctx.querySelector(sel);
  const $$ = (sel, ctx = document) => Array.from(ctx.querySelectorAll(sel));

  /* ---------- 1. 导航栏滚动毛玻璃 ---------- */
  const nav = $('#nav');
  const toTop = $('#toTop');
  const onScroll = () => {
    nav.classList.toggle('scrolled', window.scrollY > 24);
    toTop.classList.toggle('show', window.scrollY > 560);
  };
  window.addEventListener('scroll', onScroll, { passive: true });
  onScroll();

  /* ---------- 2. 移动端汉堡菜单 ---------- */
  const burger = $('#navBurger');
  const navLinks = $('#navLinks');
  burger.addEventListener('click', () => {
    const open = navLinks.classList.toggle('open');
    burger.classList.toggle('open', open);
    burger.setAttribute('aria-expanded', String(open));
  });
  navLinks.addEventListener('click', (e) => {
    if (e.target.tagName === 'A') {
      navLinks.classList.remove('open');
      burger.classList.remove('open');
      burger.setAttribute('aria-expanded', 'false');
    }
  });

  /* ---------- 3. 滚动高亮当前导航（Scroll Spy） ---------- */
  const sections = ['screenshots', 'features', 'modes', 'advanced', 'download', 'changelog', 'faq']
    .map((id) => document.getElementById(id))
    .filter(Boolean);
  const linkMap = new Map(
    $$('.nav-links a').map((a) => [a.getAttribute('href').slice(1), a])
  );
  const spy = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        const link = linkMap.get(entry.target.id);
        if (!link) return;
        if (entry.isIntersecting) {
          $$('.nav-links a').forEach((a) => a.classList.remove('active'));
          link.classList.add('active');
        }
      });
    },
    { rootMargin: '-38% 0px -55% 0px' }
  );
  sections.forEach((s) => spy.observe(s));

  /* ---------- 4. 滚动渐入动画 ---------- */
  let revealIO = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          revealIO.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.12 }
  );
  $$('.reveal').forEach((el) => revealIO.observe(el));

  /* ---------- 5. 手机模型 · 模拟聊天循环 ---------- */
  const chatBody = $('#chatBody');
  const chatScript = [
    { from: 'ai', text: '欢迎回家～今天过得怎么样呀？' },
    { from: 'me', text: '有点累，加班到现在才回来' },
    { from: 'ai', text: '辛苦啦！给你留了虚拟热可可' },
    { from: 'ai', text: '要不要听个睡前故事放松一下？' },
    { from: 'me', text: '好呀，想听上次那个猫咪星球的故事' },
    { from: 'ai', text: '好嘞～让我从世界书翻到第 42 页…' },
    { from: 'ai', text: '在遥远的猫咪星球上，每只猫都守护着一颗温暖的星星' },
  ];
  const MSG_GAP = 1300;   // 两条消息间隔
  const TYPING_TIME = 900; // “正在输入”时长
  const LOOP_PAUSE = 3400; // 一轮结束后的停顿

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  function addMsg(from, text) {
    const div = document.createElement('div');
    div.className = `msg ${from}`;
    div.textContent = text;
    chatBody.appendChild(div);
    // 超出屏幕时温和地上移（保持最新可见）
    while (chatBody.scrollHeight > chatBody.clientHeight + 4 && chatBody.children.length > 2) {
      chatBody.removeChild(chatBody.firstElementChild);
    }
  }

  function addTyping() {
    const div = document.createElement('div');
    div.className = 'msg typing';
    div.innerHTML = '<i></i><i></i><i></i>';
    chatBody.appendChild(div);
    return div;
  }

  async function playChat() {
    if (!chatBody) return;
    chatBody.innerHTML = '';
    for (const item of chatScript) {
      if (item.from === 'ai') {
        const typing = addTyping();
        await sleep(TYPING_TIME);
        typing.remove();
      }
      addMsg(item.from, item.text);
      await sleep(MSG_GAP);
    }
    await sleep(LOOP_PAUSE);
    playChat();
  }

  // 等手机滚入视野后再开始播放，省电也更自然
  if (chatBody) {
    const phoneIO = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          phoneIO.disconnect();
          playChat();
        }
      },
      { threshold: 0.3 }
    );
    phoneIO.observe(chatBody);
  }

  /* ---------- 6. 功能展示 Tab 切换 ---------- */
  const tabs = $$('.sc-tab');
  const panels = $$('.sc-panel');
  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const key = tab.dataset.tab;
      tabs.forEach((t) => {
        const active = t === tab;
        t.classList.toggle('active', active);
        t.setAttribute('aria-selected', String(active));
      });
      panels.forEach((p) => p.classList.toggle('active', p.dataset.panel === key));
    });
  });

  /* ---------- 7. 截图画廊：纵向滚轮转换为横向滚动 ---------- */
  const screenshotGallery = $('.screenshot-gallery');
  if (screenshotGallery) {
    screenshotGallery.addEventListener('wheel', (event) => {
      if (Math.abs(event.deltaY) <= Math.abs(event.deltaX)) return;

      const unit = event.deltaMode === 1
        ? 32
        : event.deltaMode === 2
          ? screenshotGallery.clientWidth
          : 1;
      const distance = event.deltaY * unit;
      const maxScrollLeft = screenshotGallery.scrollWidth - screenshotGallery.clientWidth;
      const canScrollLeft = distance < 0 && screenshotGallery.scrollLeft > 1;
      const canScrollRight = distance > 0 && screenshotGallery.scrollLeft < maxScrollLeft - 1;
      if (!canScrollLeft && !canScrollRight) return;

      event.preventDefault();
      screenshotGallery.scrollBy({
        left: distance,
        behavior: 'auto',
      });
    }, { passive: false });
  }

  /* ---------- 8. 回到顶部 ---------- */
  toTop.addEventListener('click', () => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  });

  /* ---------- 9. FAQ 手风琴（同时只展开一个） ---------- */
  const faqItems = $$('.faq-item');
  faqItems.forEach((item) => {
    item.addEventListener('toggle', () => {
      if (item.open) {
        faqItems.forEach((other) => {
          if (other !== item) other.open = false;
        });
      }
    });
  });

  /* ---------- 10. 最新 APK 直链 ---------- */
  (async () => {
    const apkBtns = $$('[data-dl="apk"]');
    if (!apkBtns.length) return;
    try {
      const r = await fetch('https://api.github.com/repos/asukaneko/Nekobot-Android/releases/latest', {
        headers: { Accept: 'application/vnd.github.v3+json' },
        signal: AbortSignal.timeout(5000),
      });
      if (!r.ok) throw new Error(String(r.status));
      const data = await r.json();
      const asset = data.assets?.[0];
      if (asset?.browser_download_url) {
        apkBtns.forEach((btn) => { btn.href = asset.browser_download_url; });
      }
    } catch {}
  })();

  /* ---------- 11. 从 GitHub Releases 拉取更新日志 ---------- */
  const FALLBACK_RELEASES = [
    { tag_name: 'v0.4.0', published_at: '2026-07-26', body: '- 本地模式 WebDAV 备份与恢复功能\n- 成就系统，多层级徽章、范围隔离与解锁动效\n- 仪表盘组件与小组件（最近会话、Token 构成、快捷操作等）\n- OAuth 账号登录与 OpenAI Responses API 协议支持' },
    { tag_name: 'v0.3.10', published_at: '2026-07-24', body: '- 重构 TTS 语音合成系统，多提供商支持与消息级音频持久化\n- 基于会话 ID 的实时事件隔离与多会话支持\n- 隐私锁功能，重构 Markdown 渲染\n- 角色卡初始状态（initial_state）与 Exa MCP 搜索' },
    { tag_name: 'v0.3.9', published_at: '2026-07-23', body: '- 本地模式斜杠命令系统\n- 本地 JM 漫画排行与下载功能\n- 本地耗时命令实时进度报告' },
  ];
  function renderReleases(releases) {
    const container = $('#changelogList');
    if (!container) return;
    container.innerHTML = releases.slice(0, 3).map((rel, i) => {
      const ver = rel.tag_name;
      const date = rel.published_at ? rel.published_at.slice(0, 10) : '';
      const body = (rel.body || '').trim();
      const items = body.split('\n').filter(l => l.trim().startsWith('-') || l.trim().startsWith('*')).map(l => l.trim().replace(/^[-*]\s*/, ''));
      const delay = i === 0 ? '' : i === 1 ? ' d1' : ' d2';
      return `<div class="tl-item reveal${delay}"><div class="tl-dot"></div><div class="tl-card"><div class="tl-head"><b>${ver}</b><time>${date}</time></div><ul>${items.map(item => `<li>${item}</li>`).join('')}</ul></div></div>`;
    }).join('');
    container.querySelectorAll('.reveal').forEach((el) => revealIO.observe(el));
  }
  (async () => {
    try {
      const r = await fetch('https://api.github.com/repos/asukaneko/Nekobot-Android/releases?per_page=3', {
        headers: { Accept: 'application/vnd.github.v3+json' },
        signal: AbortSignal.timeout(5000),
      });
      if (!r.ok) throw new Error(String(r.status));
      renderReleases(await r.json());
    } catch {
      renderReleases(FALLBACK_RELEASES);
    }
  })();

})();
