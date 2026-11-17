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

  // 截图语言：中文简繁用中文截图，其余语言用英文截图
  const SHOT_LANGS = { 'zh-CN': 'zh', 'zh-TW': 'zh', 'en': 'en', 'ja': 'en', 'ko': 'en' };

  function applyShotLang() {
    const lang = SHOT_LANGS[currentLang] || 'en';
    $$('img[data-shot-zh][data-shot-en]').forEach((img) => {
      const src = lang === 'zh' ? img.dataset.shotZh : img.dataset.shotEn;
      if (src && img.getAttribute('src') !== src) {
        img.src = src;
        img.loading = 'eager';
      }
    });
  }

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
    applyShotLang();
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
    "tag_name": "v0.9.1",
    "published_at": "2026-11-17",
    "body": "- 新增「跟随系统 / 浅色 / 深色」主题模式，样式设置页可即时切换，冷启动不再深浅闪烁。\n- 图片生成实验室新增历史记录：自动保存最近 100 条，支持预览、复用提示词、用作参考图、单条删除与清空。\n- 消息详情新增「模型用量」分区，逐轮展示实际调用模型与 Token 用量，回退模型及失败状态一并列出。\n- 聊天界面新增右下角悬浮按钮，离开底部时显示，点击逐帧滚动回真正底部。\n- 消息操作菜单恢复「生成图片」。\n- Agent 工作区改动摘要支持按仓库整体折叠，并按会话归属过滤共享工作区的改动。\n- 修复 Token 用量与超大消息处理可能导致的 OOM 崩溃，并收紧用量文件体积上限。\n- 修复长回复渲染时正则重复编译造成的卡顿与 ANR。\n- 修复删除单角色窗口后角色被永久隐藏的问题，删除时同事务释放归属关系。\n- 修复 WebDAV 备份读取不识别压缩格式、GB18030 编码备份无法恢复的问题。\n- 删除、清空等不可逆操作的确认按钮改用危险色，与「保存」区分。\n- 角色、会话、世界书空列表补充新建与导入入口。\n- 聊天多选批量删除新增二次确认，部分失败时提示失败条数。\n- 修正聊天滚动策略：阅读历史期间不再被新消息拉回底部。\n- 精简 Agent 设置项描述文案，过长说明自动省略。\n\n### English\n- Added Follow system / Light / Dark theme modes, switchable in style settings with no flash on cold start.\n- The image generation playground now keeps history of the last 100 entries with preview, reuse prompt, use as reference, per-item delete, and clear all.\n- Message details gained a model usage section listing the model and token usage per round, including fallback models and failures.\n- Chat shows a floating scroll-to-bottom button when the list leaves the bottom, scrolling back frame by frame.\n- Restored the Generate image action in the message menu.\n- The agent workspace change summary can now collapse per repository and filters shared-workspace changes by session ownership.\n- Fixed OOM crashes caused by token usage and oversized messages, with stricter size caps on usage files.\n- Fixed stutter and ANRs from repeated regex compilation when rendering long replies.\n- Fixed characters being permanently hidden after deleting a companion window; ownership is now released in the same transaction.\n- Fixed WebDAV backups not detecting compressed formats and GB18030-encoded backups failing to restore.\n- Destructive actions such as delete and clear now use a danger-colored confirm button, distinct from Save.\n- Empty character, session, and world book lists now offer create and import entries.\n- Multi-select batch delete in chat asks for confirmation and reports how many items failed.\n- Corrected chat scrolling so reading history is no longer interrupted by new messages.\n- Trimmed Agent settings descriptions, with long text ellipsized."
  },
  {
    "tag_name": "v0.9.0",
    "published_at": "2026-11-09",
    "body": "- 新增会话首页聚合场景卡与轻聊面板：可直接召唤角色轻聊，按 `<||>` 分段渲染多气泡并支持 Markdown，同时展示关系变化与剧情选项，可一键展开为完整会话。\n- 天气工具支持最多 16 天逐日预报，主用 Open-Meteo 并以 wttr.in 兜底（#34）。\n- 临时会话改用专属开始页，明确说明不保存记录、不读写长期记忆、结束即清除。\n- 单角色设置新增「记忆与关系」直达入口，并补齐「清空当前会话」。\n- 新增「高级选项」页：隐私锁、归档会话显示、启动打开最新会话、首页顶部卡片聚合等开关集中管理。\n- 本地数据库工具支持分页与输出上限，避免整库正文一次性撑爆上下文。\n- Agent 新增单轮运行预算（token / 时长 / 费用），超预算时带进度收尾，回复「继续」可接着执行。\n- 工具分派层统一超时，超时返回结构化 TIMEOUT，单个工具挂住不再拖死整轮任务。\n- system 提示词拆分为稳定核与动态尾，多轮对话的提示词缓存不再每轮失效。\n- 修复 WorkBuddy 模型目录混入账号不可用模型、聊天鉴权头不正确的问题，并自动清理失效旧模型。\n- 修复天气工具 days 参数被忽略、请求多日仍只返回当天的问题（#34）。\n- 修复会话统计趋势图柱底不齐、超长数值撑破布局的问题。\n- 修复消息详情抽屉样式不统一的问题，统一改用通用抽屉组件。\n- 加固本地 Agent 与插件执行安全边界：统一路径鉴权、下载按实际读取量限额、浏览器跳转逐跳校验、压缩包解包预算。\n- 聚合模式下来源切换整合进立绘编辑抽屉，移除场景卡上的切换按钮。\n- 重构会话首页头部与场景卡的视觉层级，固定角色尚无会话时显示「开始聊」。\n- 用量记录改为合并写盘并按字符预算保留，减少磁盘同步且不再静默丢历史。\n- 优化字体与文字设置区的视觉层次，展开内容改用内嵌面板。\n- 列表视图切换图标统一为 GridView，快捷聊天输入框提示统一为「说点什么…」。\n\n### English\n- Added an aggregated scene card and a quick-chat panel on the sessions home: summon a character to chat, render multiple bubbles split by `<||>` with Markdown, see relationship changes and plot options, and expand into a full session in one tap.\n- The weather tool now supports up to 16-day daily forecasts, backed by Open-Meteo with a wttr.in fallback (#34).\n- Temporary sessions now open a dedicated start page that states records are not saved, long-term memory is untouched, and everything is cleared on exit.\n- Companion settings gained direct entries for memory and relationship, plus a \"clear current session\" action.\n- Added an Advanced Options page that gathers the privacy lock, archived-session display, open-latest-session-on-launch, and home-top-card aggregation switches.\n- Local database tools now paginate and cap their output so a whole library no longer floods the context window.\n- Agents now have a per-run budget (tokens / duration / cost); exceeding it ends the run with progress and a \"continue\" prompt to resume.\n- Tool dispatch applies a unified timeout and returns a structured TIMEOUT, so one stuck tool no longer stalls the whole run.\n- The system prompt is split into a stable core and a dynamic tail, so prompt caching no longer resets every turn.\n- Fixed WorkBuddy model catalogs including models the account cannot use and incorrect chat auth headers, and stale models are now pruned.\n- Fixed the weather tool ignoring the days parameter and returning only today despite multi-day requests (#34).\n- Fixed misaligned bars and overflowing labels in the session-statistics trend chart.\n- Fixed inconsistent styling in the message details sheet by reusing the shared sheet components.\n- Hardened the local agent and plugin execution boundaries: unified path authorization, download limits by actual bytes read, per-hop browser redirect validation, and archive extraction budgets.\n- Source switching in aggregated mode moved into the portrait editor sheet; the scene card button was removed.\n- Reworked the visual hierarchy of the sessions home header and scene cards; pinned characters without a session now show \"Start chat\".\n- Token usage records are now written in batches under a character budget, cutting disk syncs without silently dropping history.\n- Refined the visual hierarchy of the font and text settings area with an inset panel when expanded.\n- Unified the list view-mode icon to GridView and the quick-chat input placeholder to \"Say something…\"."
  },
  {
    "tag_name": "v0.8.9",
    "published_at": "2026-11-03",
    "body": "- 单会话角色全面升级：接入角色记忆第二版，支持编号证据、逐条入库、待确认与锁定保护、按当轮日期呈现记忆与年度纪念日。\n- 新增单会话角色头像裁剪保存，并统一聊天列表显示。\n- 新增「角色与提示词」统一入口，说话方式与思考提示按角色独立保存，支持取消、恢复默认与失败重试。\n- 新增一次性对话示例：为新建单会话角色提供开场示例，停用提示只显示一次并支持不再提示。\n- 新增 WorkBuddy（腾讯 CodeBuddy）OAuth 登录：支持国内版与国际版账号、浏览器授权轮询、token 刷新与在线模型目录。\n- Linux 沙盒新增 /app 挂载：AI 可在沙盒内直接读写应用私有目录，MCP 与交互终端同步支持。\n- 平板侧边导航栏改为可展开收起的左侧抽屉导航。\n- 聊天输入新增 Ctrl+Enter（Command+Enter）硬件键盘发送快捷键。\n- Agent 工作区 git 仓库监控重构：实时轮询扫描仓库变更并刷新进度卡片，支持自动发现嵌套仓库。\n- 数据便携导入重构为三步流程：支持内容分选、冲突确认、差异保护与原子恢复，迁入会话用户资料转为独立用户画像。\n- 更多页整理为五分类并归位应用设置，数据导入导出分栏呈现。\n- 修复 Anthropic 协议只发送第一条系统提示、其余段落被静默丢弃的问题。\n- 修复代码块与表格在消息气泡中被截断、被拆段的问题，含代码块/表格的消息使用更宽的气泡。\n- 修复单会话角色气泡在深色模式下整块白底黑字的问题，改为跟随主题表面色。\n- 修复归档缺少原话快照时被误判为差异、重复导入需再次确认的问题。\n- 修复缺整理模型时记忆整理打断回复的问题，本轮改用已整理内容组装。\n- 修复工作区文件哈希缓存毫秒精度漏判导致改动被误判干净的问题。\n- 修复旧库升级后单会话记忆历史与出处丢失的问题。\n- 修复切档时后台通知唤醒在仓库构造期间回调导致的崩溃。\n- 修复对话示例停用提示重复显示的问题。\n- 修复分页统计未覆盖完整对话并解除示例邀请百条限制。\n- 优化默认思考提示词，回复更自然、内容更多，回复气泡数提升到 2~3 个。\n- 优化会话列表为轻量查询与自动分页，缓存消息展示计算并后台解析流式文本，启动异步清理遗留临时会话。\n- 移除「显示内心独白」开关：独白固定内联显示在正文之后，不再教模型写独白。\n- 补齐导入、来源、更多页与记忆评审等多处英/日/韩/繁中翻译。\n\n### English\n- Companion-mode characters get a major upgrade: memory system v2 with numbered evidence, per-item storage, pending confirmation and lock protection, date-aware memory presentation and annual anniversaries.\n- Added avatar cropping for companion characters with unified display in the chat list.\n- Added a unified \"Character & Prompts\" entry; speech style and thinking prompts are saved per character with cancel, reset-to-default, and retry on failure.\n- Added one-time dialogue examples for new companion characters; the disabled-example notice shows once and can be permanently dismissed.\n- Added WorkBuddy (Tencent CodeBuddy) OAuth login with CN/International accounts, browser-authorization polling, token refresh, and an online model catalog.\n- The Linux sandbox now mounts the app private directory at /app so the AI can read and write it directly; MCP and interactive terminals support it too.\n- The tablet side navigation is replaced by an expandable left drawer.\n- Added Ctrl+Enter (Command+Enter) hardware keyboard send shortcut in chat.\n- Reworked the Agent workspace git watcher: polling scans refresh the progress card in real time and nested repos are discovered automatically.\n- Portable data import is rebuilt as a three-step flow with content selection, conflict confirmation, diff protection, and atomic restore; imported user profiles become standalone user personas.\n- The More page is reorganized into five categories with app settings moved in place, and data import/export is presented in split panes.\n- Fixed Anthropic requests dropping all but the first system prompt segment.\n- Fixed code blocks and tables being clipped or split across segments inside message bubbles; such messages use wider bubbles.\n- Fixed companion AI bubbles rendering as white-on-dark in dark mode; they now follow the theme surface color.\n- Fixed archives without original-message snapshots being flagged as diffs, which forced re-confirmation on repeated imports.\n- Fixed memory consolidation aborting replies when no consolidation model is available; already consolidated content is used instead.\n- Fixed workspace file-hash caches losing changes within the same millisecond.\n- Fixed companion memory history and source attribution being lost on legacy database upgrades.\n- Fixed a crash when a profile-switch background notification callback fired during repository construction.\n- Fixed the disabled-example notice appearing repeatedly.\n- Fixed pagination statistics covering the full conversation and removed the 100-item example invitation limit.\n- Reworked the default thinking prompt for more natural, longer replies of 2~3 bubbles.\n- Sessions now use lightweight queries with automatic paging, message rendering is cached with background streaming parse, and leftover temporary sessions are cleaned asynchronously at startup.\n- Removed the \"show inner monologue\" toggle: monologue is always shown inline after the message body and the model is no longer taught to write it.\n- Completed English/Japanese/Korean/Traditional Chinese translations for import, sources, the More page, and memory review."
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
