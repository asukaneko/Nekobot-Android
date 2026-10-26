/* ============================================================
   Nekobot 官网交互脚本
   多语言依赖 i18n.js 提供的 window.NEKOBOT_I18N（需先加载）
   ============================================================ */
(function () {
  'use strict';

  const $ = (sel, ctx = document) => ctx.querySelector(sel);
  const $$ = (sel, ctx = document) => Array.from(ctx.querySelectorAll(sel));

  /* ---------- 0. 多语言引擎 ---------- */
  const I18N_DICT = window.NEKOBOT_I18N || {};
  const I18N_DEFAULT = 'zh-CN';
  const LANGS = ['zh-CN', 'zh-TW', 'en', 'ja', 'ko'];
  const LANG_LABELS = { 'zh-CN': '简体中文', 'zh-TW': '繁體中文', 'en': 'English', 'ja': '日本語', 'ko': '한국어' };
  const LANG_HTML = { 'zh-CN': 'zh-CN', 'zh-TW': 'zh-TW', 'en': 'en', 'ja': 'ja', 'ko': 'ko' };

  let currentLang = I18N_DEFAULT;
  let chatScript = [];
  let chatGeneration = 0;
  let chatStarted = false;
  let lastReleases = null;
  let refreshDynamicA11y = null; // 由截图画廊注册，语言切换时刷新动态 aria 标签

  function detectLang() {
    // 每次打开网站都优先跟随浏览器语言；手动选择只在浏览器语言不受支持时作为回退
    const prefs = (navigator.languages && navigator.languages.length)
      ? navigator.languages
      : [navigator.language || I18N_DEFAULT];
    for (const raw of prefs) {
      const l = String(raw).toLowerCase();
      if (l.startsWith('zh')) {
        if (l.includes('tw') || l.includes('hk') || l.includes('mo') || l.includes('hant')) return 'zh-TW';
        return 'zh-CN';
      }
      if (l.startsWith('ja')) return 'ja';
      if (l.startsWith('ko')) return 'ko';
      if (l.startsWith('en')) return 'en';
    }
    let saved = null;
    try { saved = localStorage.getItem('nekobot_site_lang'); } catch {}
    if (saved && LANGS.includes(saved)) return saved;
    return I18N_DEFAULT;
  }

  function t(key, vars) {
    const d = I18N_DICT[currentLang] || {};
    let s = (d[key] != null) ? d[key] : ((I18N_DICT[I18N_DEFAULT] || {})[key]);
    if (s == null) return key;
    if (vars) {
      Object.keys(vars).forEach((k) => { s = s.replace('{' + k + '}', vars[k]); });
    }
    return s;
  }

  // 模拟聊天的发言方顺序（ai / me 交替，与文案条数一一对应）
  const CHAT_FROM_PATTERN = ['ai', 'me', 'ai', 'ai', 'me', 'ai', 'ai'];

  function applyI18n() {
    const dict = I18N_DICT[currentLang] || I18N_DICT[I18N_DEFAULT] || {};
    const fallback = I18N_DICT[I18N_DEFAULT] || {};
    $$('[data-i18n]').forEach((el) => {
      const v = dict[el.dataset.i18n] != null ? dict[el.dataset.i18n] : fallback[el.dataset.i18n];
      if (v != null) el.textContent = v;
    });
    $$('[data-i18n-html]').forEach((el) => {
      const v = dict[el.dataset.i18nHtml] != null ? dict[el.dataset.i18nHtml] : fallback[el.dataset.i18nHtml];
      if (v != null) el.innerHTML = v;
    });
    $$('[data-i18n-attr]').forEach((el) => {
      el.dataset.i18nAttr.split(';').forEach((pair) => {
        const idx = pair.indexOf(':');
        if (idx < 1) return;
        const attr = pair.slice(0, idx).trim();
        const key = pair.slice(idx + 1).trim();
        if (!attr || !key) return;
        const v = dict[key] != null ? dict[key] : fallback[key];
        if (v != null) el.setAttribute(attr, v);
      });
    });
    document.title = t('meta.title');
    const metaDesc = $('meta[name="description"]');
    if (metaDesc) metaDesc.setAttribute('content', t('meta.description'));
    document.documentElement.lang = LANG_HTML[currentLang] || I18N_DEFAULT;
    const rawChat = Array.isArray(dict.chat) && dict.chat.length ? dict.chat : (fallback.chat || []);
    chatScript = rawChat.map((text, i) => ({ from: CHAT_FROM_PATTERN[i % CHAT_FROM_PATTERN.length], text }));
    updateLangSwitcher();
    if (refreshDynamicA11y) refreshDynamicA11y();
  }

  function updateLangSwitcher() {
    const label = $('#langBtnLabel');
    if (label) label.textContent = LANG_LABELS[currentLang] || currentLang;
    const btn = $('#langBtn');
    if (btn) btn.setAttribute('aria-label', t('lang.aria'));
    const menu = $('#langMenu');
    if (menu) {
      $$('button[data-lang]', menu).forEach((b) => {
        const active = b.dataset.lang === currentLang;
        b.classList.toggle('active', active);
        b.setAttribute('aria-selected', String(active));
      });
    }
  }

  function closeLangMenu() {
    const sw = $('#langSwitch');
    const btn = $('#langBtn');
    if (sw && sw.classList.contains('open')) {
      sw.classList.remove('open');
      if (btn) btn.setAttribute('aria-expanded', 'false');
    }
  }

  function setLang(lang) {
    if (!LANGS.includes(lang) || lang === currentLang) { closeLangMenu(); return; }
    currentLang = lang;
    try { localStorage.setItem('nekobot_site_lang', lang); } catch {}
    applyI18n();
    if (lastReleases) renderReleases(lastReleases);
    chatGeneration += 1;
    if (chatStarted) playChat();
  }

  currentLang = detectLang();
  applyI18n();

  // 语言切换器交互
  (() => {
    const sw = $('#langSwitch');
    const btn = $('#langBtn');
    const menu = $('#langMenu');
    if (!sw || !btn || !menu) return;
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const open = sw.classList.toggle('open');
      btn.setAttribute('aria-expanded', String(open));
    });
    menu.addEventListener('click', (e) => {
      const b = e.target.closest('button[data-lang]');
      if (!b) return;
      setLang(b.dataset.lang);
    });
    document.addEventListener('click', (e) => {
      if (!sw.contains(e.target)) closeLangMenu();
    });
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') closeLangMenu();
    });
  })();

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
  const sections = ['screenshots', 'features', 'modes', 'role', 'agent', 'advanced', 'plugins', 'changelog', 'faq', 'download']
    .map((id) => document.getElementById(id))
    .filter(Boolean);
  const linkMap = new Map(
    $$('.nav-links a').map((a) => [a.getAttribute('href').slice(1), a])
  );
  const spy = typeof IntersectionObserver === 'function' ? new IntersectionObserver(
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
  ) : null;
  if (spy) sections.forEach((s) => spy.observe(s));

  /* ---------- 4. 滚动渐入动画 ---------- */
  const reduceMotion = window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const revealIO = typeof IntersectionObserver === 'function' && !reduceMotion ? new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          entry.target.classList.remove('reveal-pending');
          revealIO.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.12 }
  ) : null;
  function observeReveal(el) {
    // 只有成功注册动画后才隐藏内容，脚本加载失败时仍可阅读页面。
    if (!revealIO) return;
    revealIO.observe(el);
    el.classList.add('reveal-pending');
  }
  $$('.reveal').forEach(observeReveal);

  /* ---------- 5. 手机模型 · 模拟聊天循环（文案随语言切换） ---------- */
  const chatBody = $('#chatBody');
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
    if (!chatBody || !chatScript.length) return;
    const gen = chatGeneration;
    chatBody.innerHTML = '';
    for (const item of chatScript) {
      if (gen !== chatGeneration) return; // 语言已切换，放弃本轮
      if (item.from === 'ai') {
        const typing = addTyping();
        await sleep(TYPING_TIME);
        typing.remove();
      }
      addMsg(item.from, item.text);
      await sleep(MSG_GAP);
    }
    await sleep(LOOP_PAUSE);
    if (gen !== chatGeneration) return;
    playChat();
  }

  // 等手机滚入视野后再开始播放，省电也更自然
  if (chatBody && typeof IntersectionObserver === 'function') {
    const phoneIO = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting) {
          phoneIO.disconnect();
          chatStarted = true;
          playChat();
        }
      },
      { threshold: 0.3 }
    );
    phoneIO.observe(chatBody);
  } else if (chatBody) {
    chatStarted = true;
    playChat();
  }

  /* ---------- 6. 功能展示 Tab 切换 ---------- */
  const tabs = $$('.sc-tab');
  const panels = $$('.sc-panel');
  tabs.forEach((tab) => {
    tab.addEventListener('click', () => {
      const key = tab.dataset.tab;
      tabs.forEach((t2) => {
        const active = t2 === tab;
        t2.classList.toggle('active', active);
        t2.setAttribute('aria-selected', String(active));
      });
      panels.forEach((p) => p.classList.toggle('active', p.dataset.panel === key));
    });
  });

  /* ---------- 7. 截图画廊：横向滚动 + 箭头分页 + 进度条 + 灯箱 ---------- */
  const screenshotGallery = $('.screenshot-gallery');
  if (screenshotGallery) {
    // 纵向滚轮转换为横向滚动
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

    // 箭头按钮 + 圆点 + 标题联动
    const prevBtn = $('#galleryPrev');
    const nextBtn = $('#galleryNext');
    const dotsBox = $('#galleryDots');
    const shotTitle = $('#shotTitle');
    const shotDesc = $('#shotDesc');
    const shotCaption = $('.screenshot-caption');
    const shotCards = $$('.screenshot-card', screenshotGallery);
    let activeShot = 0;

    const goTo = (i, smooth = true) => {
      const clamped = Math.max(0, Math.min(shotCards.length - 1, i));
      screenshotGallery.scrollTo({ left: clamped * screenshotGallery.clientWidth, behavior: smooth ? 'smooth' : 'auto' });
    };

    // 生成轮播圆点
    const dots = shotCards.map((card, i) => {
      const dot = document.createElement('button');
      dot.type = 'button';
      dot.addEventListener('click', () => goTo(i));
      if (dotsBox) dotsBox.appendChild(dot);
      return dot;
    });

    const updateGalleryUi = () => {
      const cw = screenshotGallery.clientWidth || 1;
      const sl = screenshotGallery.scrollLeft;
      const idx = Math.max(0, Math.min(shotCards.length - 1, Math.round(sl / cw)));
      if (prevBtn) prevBtn.disabled = sl <= 1;
      if (nextBtn) nextBtn.disabled = sl >= screenshotGallery.scrollWidth - cw - 1;
      dots.forEach((d, i) => d.classList.toggle('active', i === idx));
      if (idx !== activeShot) {
        activeShot = idx;
        const card = shotCards[idx];
        const capB = $('figcaption b', card);
        const capS = $('figcaption span', card);
        if (shotTitle) shotTitle.textContent = capB ? capB.textContent : '';
        if (shotDesc) shotDesc.textContent = capS ? capS.textContent : '';
        if (shotCaption && shotCaption.animate) {
          shotCaption.animate(
            [{ opacity: 0, transform: 'translateY(8px)' }, { opacity: 1, transform: 'none' }],
            { duration: 320, easing: 'ease-out' }
          );
        }
      }
    };
    if (prevBtn) prevBtn.addEventListener('click', () => goTo(activeShot - 1));
    if (nextBtn) nextBtn.addEventListener('click', () => goTo(activeShot + 1));
    screenshotGallery.addEventListener('scroll', updateGalleryUi, { passive: true });
    window.addEventListener('resize', () => { goTo(activeShot, false); updateGalleryUi(); });
    window.addEventListener('load', updateGalleryUi);
    updateGalleryUi();

    // 灯箱预览（点击卡片放大，支持左右切换与键盘操作）
    const lightbox = document.createElement('div');
    lightbox.className = 'lightbox';
    lightbox.setAttribute('role', 'dialog');
    lightbox.setAttribute('aria-modal', 'true');
    lightbox.innerHTML = `
      <div class="lightbox-backdrop"></div>
      <figure class="lightbox-figure">
        <img alt="" />
        <figcaption><b></b><span></span></figcaption>
      </figure>
      <button class="lightbox-nav prev" type="button"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg></button>
      <button class="lightbox-nav next" type="button"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg></button>
      <button class="lightbox-close" type="button"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>`;
    document.body.appendChild(lightbox);

    const lbImg = $('.lightbox-figure img', lightbox);
    const lbTitle = $('.lightbox-figure b', lightbox);
    const lbDesc = $('.lightbox-figure span', lightbox);
    let lbIndex = 0;

    // 语言切换时刷新依赖文案的动态 aria / 标题
    refreshDynamicA11y = () => {
      lightbox.setAttribute('aria-label', t('js.lightboxLabel'));
      const lbPrev = $('.lightbox-nav.prev', lightbox);
      const lbNext = $('.lightbox-nav.next', lightbox);
      const lbClose = $('.lightbox-close', lightbox);
      if (lbPrev) lbPrev.setAttribute('aria-label', t('js.lbPrev'));
      if (lbNext) lbNext.setAttribute('aria-label', t('js.lbNext'));
      if (lbClose) lbClose.setAttribute('aria-label', t('js.lbClose'));
      shotCards.forEach((card, i) => {
        const capB = $('figcaption b', card);
        const name = capB ? capB.textContent : t('js.defaultShot');
        if (dots[i]) dots[i].setAttribute('aria-label', t('js.galleryView', { name }));
        card.setAttribute('aria-label', t('js.galleryZoom', { name }));
      });
    };
    refreshDynamicA11y();

    function syncLightbox() {
      const card = shotCards[lbIndex];
      if (!card) return;
      const img = $('img', card);
      lbImg.src = img.currentSrc || img.src;
      lbImg.alt = img.alt;
      const capB = $('figcaption b', card);
      const capS = $('figcaption span', card);
      lbTitle.textContent = capB ? capB.textContent : '';
      lbDesc.textContent = capS ? capS.textContent : '';
    }
    function openLightbox(index) {
      lbIndex = index;
      syncLightbox();
      lightbox.classList.add('open');
      document.body.classList.add('lightbox-open');
    }
    function closeLightbox() {
      lightbox.classList.remove('open');
      document.body.classList.remove('lightbox-open');
    }
    function stepLightbox(delta) {
      lbIndex = (lbIndex + delta + shotCards.length) % shotCards.length;
      syncLightbox();
    }

    shotCards.forEach((card, i) => {
      card.tabIndex = 0;
      card.setAttribute('role', 'button');
      card.addEventListener('click', () => openLightbox(i));
      card.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openLightbox(i); }
      });
    });
    $('.lightbox-backdrop', lightbox).addEventListener('click', closeLightbox);
    $('.lightbox-close', lightbox).addEventListener('click', closeLightbox);
    $('.lightbox-nav.prev', lightbox).addEventListener('click', () => stepLightbox(-1));
    $('.lightbox-nav.next', lightbox).addEventListener('click', () => stepLightbox(1));
    document.addEventListener('keydown', (e) => {
      if (!lightbox.classList.contains('open')) return;
      if (e.key === 'Escape') closeLightbox();
      else if (e.key === 'ArrowLeft') stepLightbox(-1);
      else if (e.key === 'ArrowRight') stepLightbox(1);
    });
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
      // 跟随页面展示的版本，确保预发行版也下载到对应 APK。
      const displayedTag = $('#dlVersion')?.textContent.trim();
      const releasePath = displayedTag ? 'tags/' + encodeURIComponent(displayedTag) : 'latest';
      const r = await fetch('https://api.github.com/repos/asukaneko/Nekobot-Android/releases/' + releasePath, {
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

  /* ---------- 11. 从 GitHub Releases 拉取更新日志 ----------
     release body 为双语格式（见 RELEASE.md）：
     中文条目置顶，其后用 "### English" 小节分隔；
     中文界面（简/繁）显示中文条目，其他语言显示英文段，英文段缺失时回退中文。 */
  function splitReleaseBody(body) {
    const parts = { default: [], en: [] };
    let cur = 'default';
    (body || '').split(/\r?\n/).forEach((line) => {
      if (/^#{2,4}\s*\[?English\]?\s*$/.test(line.trim())) {
        cur = 'en';
        return;
      }
      const s = line.trim();
      if (s.startsWith('-') || s.startsWith('*')) parts[cur].push(s.replace(/^[-*]\s*/, ''));
    });
    return parts;
  }

  function releaseItemsForLang(body) {
    const parts = splitReleaseBody(body);
    const isZh = currentLang === 'zh-CN' || currentLang === 'zh-TW';
    if (!isZh && parts.en.length) return parts.en;
    return parts.default;
  }

  const FALLBACK_RELEASES = [
  {
    "tag_name": "v0.8.7",
    "published_at": "2026-10-26",
    "body": "- 全新外层界面：导航重组为「会话 / 内容库 / 能力 / 我的」四个标签，底栏与侧栏改为扁平样式，主题色板改为中性浅底配单一强调色，并新增 NekoShell 组件库。\n- 会话首页新增问候区：结合角色卡与角色记忆生成 AI 问候，可更换欢迎立绘并取景裁切，记忆变化后自动重新生成。\n- 会话首页 banner 支持固定角色：尚无会话的角色也可固定，点击可直接进入、新建会话或弹出固定角色的会话选择面板。\n- Agent 新会话新增欢迎页：基于长期记忆生成四条起步建议，点击直接发送，支持换一批并按记忆与语言缓存。\n- 新增 AI 模型余额查询：支持 DeepSeek、OpenRouter、Moonshot（Kimi）、SiliconFlow 及 OpenAI 兼容计费接口，记录按数据库档案隔离保存。\n- 新增 Kimi（月之暗面）模型提供商：接入 Kimi Code 设备码授权登录，模型编辑器与快速设置提供 Kimi 预设。\n- AI 模型编辑弹窗重构为三步骤分步全屏编辑器：用途与预设 / 连接与模型 / 参数与确认，提供字段级校验、未保存退出确认与草稿保留。\n- 引导页会话模型配置复用分步模型编辑器；版本更新检查支持预发布版语义化排序。\n- 修复会话切换与首屏加载闪烁、会话操作横条点击错位、窄屏动作条点击与用量统计串扰，并修复 CI 打包 OOM 与崩溃记录读取。\n- 统一设置与列表页面布局样式，全局搜索与用量页扁平化；应用默认字号调整为 17sp。\n\n### English\n- Brand-new outer interface: navigation reorganized into four tabs (Sessions / Library / Capabilities / Profile), flat bottom bar and rail, a near-white neutral palette with a single accent color, and a new NekoShell component library.\n- The sessions home adds a greeting area: AI greetings from character cards and memories, a swappable portrait with crop, regenerated when memories change.\n- The sessions home banner supports pinned characters, including those without sessions, with direct entry, new-session creation, or a pinned-character session picker.\n- New Agent sessions get a welcome page with four memory-based starter suggestions that can be sent directly, refreshed, and cached by memory and language.\n- Added AI model balance queries for DeepSeek, OpenRouter, Moonshot (Kimi), SiliconFlow, and OpenAI-compatible billing endpoints, with records isolated per database profile.\n- Added the Kimi (Moonshot AI) model provider with Kimi Code device-code login and Kimi presets in the model editor and quick setup.\n- The AI model editor dialog is now a three-step full-screen editor with per-field validation, unsaved-changes confirmation, and draft retention.\n- Onboarding model configuration reuses the step-by-step editor; update checks now sort pre-release versions semantically.\n- Fixed session-switch flicker, action-strip tap misalignment, narrow-screen action strips, token-usage cross-talk, CI packaging OOM, and crash-recorder version reading.\n- Unified layout styling across settings and list pages, flattened global search and usage pages; the default app font size is now 17sp."
  },
  {
    "tag_name": "v0.8.6-rc2",
    "published_at": "2026-10-23",
    "body": "- 聊天输入区重构：输入法弹起时切换为双行工具行，含「+」菜单、当前模型胶囊、语音输入与发送/停止按钮；「+」面板改为底部抽屉。\n- 实时对话界面重构为呼吸感动效布局：氛围光晕、环形频谱、字幕面板与玻璃质操作按钮。\n- 新增阶跃星辰 StepAudio 3 Realtime 语音，支持应用内 Agent 工具调用。\n- 新建角色会话改为可搜索的角色选择弹窗，同名角色可按标签定位，开场白支持折叠编辑。\n- 内置浏览器新增页面缩放，按标签页保存缩放比例。\n- 本地 AI 模型支持通过 HTTP 地址连接；内置浏览器与 Agent 浏览器开放白名单主机明文访问。\n- 新增原生崩溃记录，写入 crash/last_crash.txt。\n- 修复进度步骤详情闪退与点击无响应，超长工具结果截断以避免卡顿。\n- 修复启动时子代理任务恢复触发的 Room 主线程崩溃。\n- 全应用错误提示统一改为系统 Toast。\n- 工具步骤优先展示多语言名称，补充子代理工具说明，详情解析失败降级为原文。\n- 优化会话列表搜索栏样式，并适配横屏与键盘弹出时的弹窗布局。\n\n### English\n- Reworked the chat composer: a two-row toolbar appears with the keyboard, holding the \"+\" menu, current model chip, voice input, and send/stop; the \"+\" panel now opens as a bottom sheet.\n- The realtime voice screen uses a breathing animated layout with ambient glow, ring spectrum, subtitle panel, and glass action buttons.\n- Added StepFun StepAudio 3 Realtime voice with in-app Agent tool calling.\n- Creating a character session now uses a searchable character picker with tag-based disambiguation and a collapsible opening message.\n- The built-in browser supports page zoom, stored per tab.\n- Local AI models can connect over HTTP; the built-in and Agent browsers allow cleartext access to whitelisted hosts.\n- Added native crash recording to crash/last_crash.txt.\n- Fixed crashes and unresponsive taps in progress-step details; overly long tool results are truncated to avoid stalling.\n- Fixed a Room main-thread crash when restoring subagent tasks at startup.\n- Unified app-wide error messages as system toasts.\n- Tool steps prefer localized names, subagent tool descriptions are complete, and unparsable details fall back to raw text.\n- Refined the session-list search bar and dialog layout for landscape and keyboard insets."
  },
  {
    "tag_name": "v0.8.6-rc1",
    "published_at": "2026-10-19",
    "body": "- 新增单会话角色模式：角色绑定固定聊天窗口，配备独立记忆、核心记忆、经历档案及来源原话回查。\n- 支持将本地会话复制迁入单会话角色，并按公开规则重建记忆；提供备份、预览、进度、断点恢复及脱敏错误报告，保留原始资料。\n- 单会话角色支持独立聊天主备模型队列与关系评估模型，展示六维关系分数、角色自述、心情、情绪强度和精力，并支持原话核对与事实纠错。\n- 记忆设置支持语义对照、向量模型、近期原话预算、整理阈值及回查次数、总额度和单次上限。\n- 本地聊天支持会话级 P0 模型选择；思考强度新增自定义参数，并提供角色视角思考与自定义提示词 Beta 设置。\n- Agent 支持全局及会话级沙箱环境变量，并提供环境变量填写工具。\n- 日志页支持导出完整 UTF-8 日志。\n- 修复旧数据库升级与大型资料包导入，完善迁入恢复、来源映射和导入数据隔离。\n- 修复角色内心想法与正文的流式显示、空格换行及分段解析，并过滤普通历史中的旧思考内容与签名。\n- 修复关系评估触发、请求预算、超时处理和无效来源引用；应用回到前台可恢复被中断的评估。\n- 修复小上下文下的记忆预算、超容量原话保留、核心与锁定记忆优先级及混合检索排序。\n- 修复会话改名同步、创建资格筛选与二级页面返回后的聊天位置保留。\n- 修复 Agent AI 气泡正文限宽、内心独白折叠与纯表情显示；表情面板支持独立发送并保留输入草稿。\n- 统一液态玻璃弹窗底色，改善弹窗切换时的背景采样交接；自定义工具集编辑关闭后返回模式列表。\n- 补齐新增界面的英语、繁体中文、日语与韩语翻译，改善深色主题、大字体和底部安全区适配。\n- 整理会话列表、聊天设置与角色记忆页面；消息配色与主题色独立，支持分别设置用户与 AI 气泡颜色及可选动态效果。\n- 关系状态页提供七套独立皮肤、分层状态卡片与六维分数条。\n- 长按消息使用气泡旁悬浮工具条，支持窄屏操作折叠；消息详情和用量页展示模型、Token、缓存及生成统计。\n- 高级设置分层折叠，展示提示词占比与实时注入栈；思考设置入口统一为设置行和弹窗。\n- 全局 Agent 记忆修改记录支持差异对比、片段高亮及全文查看。\n\n### English\n- Added single-session characters with a dedicated chat, independent and core memories, experience archives, and original-message references.\n- Local chats can be copied into single-session characters and have memories rebuilt using public rules, with backups, previews, progress, resumable processing, and redacted error reports while preserving the originals.\n- Single-session characters support independent primary/fallback chat models and a relationship-evaluation model, with six relationship scores, character descriptions, mood, emotional intensity, energy, source verification, and factual corrections.\n- Memory settings include semantic matching, embedding models, recent-message budgets, consolidation thresholds, and recall limits per reply and per call.\n- Local chats support session-level P0 model selection; reasoning settings add custom parameters, character-perspective thinking, and custom prompt controls in Beta.\n- Agents support global and session-level sandbox environment variables, plus a tool for entering their values.\n- The log screen can export complete UTF-8 logs.\n- Fixed upgrades from older databases and large archive imports, with improved migration recovery, source mapping, and isolation of imported data.\n- Fixed streaming and parsing of character thoughts and replies, preserving spaces and line breaks while filtering old reasoning content and signatures from ordinary chat history.\n- Fixed relationship-evaluation triggers, request budgets, timeout handling, and invalid source references; interrupted evaluations can resume when the app returns to the foreground.\n- Fixed small-context memory budgets, preservation of oversized pending messages, core and locked memory priorities, and hybrid retrieval ranking.\n- Fixed session-name synchronization, character eligibility filtering, and restoration of chat position after returning from secondary screens.\n- Fixed Agent reply width, inner-monologue folding, and standalone sticker rendering; stickers can be sent independently without clearing the input draft.\n- Unified liquid-glass dialog tint and improved background-capture handoff between dialogs; closing a custom tool-set editor returns to the mode list.\n- Completed English, Traditional Chinese, Japanese, and Korean translations for new screens, with improved dark-mode, large-text, and bottom-inset support.\n- Refined session lists, chat settings, and character-memory screens; message colors are independent of the theme, with separate user/AI bubble colors and optional animations.\n- Relationship screens offer seven independent skins, layered status cards, and six-dimensional score bars.\n- Long-press actions use a floating message toolbar with compact layouts; message details and usage screens show models, tokens, cache usage, and generation statistics.\n- Advanced settings use collapsible sections with prompt-budget breakdowns and a live injection stack; reasoning controls use consistent settings rows and dialogs.\n- Global Agent memory history supports diffs, inline change highlighting, and full-text views."
  }
];
  function renderReleases(releases) {
    const container = $('#changelogList');
    if (!container) return;
    container.innerHTML = releases.slice(0, 3).map((rel, i) => {
      const ver = rel.tag_name;
      const date = rel.published_at ? rel.published_at.slice(0, 10) : '';
      const items = releaseItemsForLang(rel.body || '');
      const delay = i === 0 ? '' : i === 1 ? ' d1' : ' d2';
      const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
      return `<div class="tl-item reveal${delay}"><div class="tl-dot"></div><div class="tl-card"><div class="tl-head"><b>${esc(ver)}</b><time>${esc(date)}</time></div><ul>${items.map((item) => `<li>${esc(item)}</li>`).join('')}</ul></div></div>`;
    }).join('');
    container.querySelectorAll('.reveal').forEach(observeReveal);
  }

  (async () => {
    try {
      const r = await fetch('https://api.github.com/repos/asukaneko/Nekobot-Android/releases?per_page=3', {
        headers: { Accept: 'application/vnd.github.v3+json' },
        signal: AbortSignal.timeout(5000),
      });
      if (!r.ok) throw new Error(String(r.status));
      lastReleases = await r.json();
    } catch {
      lastReleases = FALLBACK_RELEASES;
    }
    renderReleases(lastReleases);
  })();

})();
