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
  const sections = ['screenshots', 'features', 'modes', 'role', 'agent', 'advanced', 'changelog', 'faq', 'download']
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
      const capB = $('figcaption b', card);
      dot.setAttribute('aria-label', `查看：${capB ? capB.textContent : '应用截图'}`);
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
    lightbox.setAttribute('aria-label', '截图大图预览');
    lightbox.innerHTML = `
      <div class="lightbox-backdrop"></div>
      <figure class="lightbox-figure">
        <img alt="" />
        <figcaption><b></b><span></span></figcaption>
      </figure>
      <button class="lightbox-nav prev" type="button" aria-label="上一张"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg></button>
      <button class="lightbox-nav next" type="button" aria-label="下一张"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg></button>
      <button class="lightbox-close" type="button" aria-label="关闭预览"><svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg></button>`;
    document.body.appendChild(lightbox);

    const lbImg = $('.lightbox-figure img', lightbox);
    const lbTitle = $('.lightbox-figure b', lightbox);
    const lbDesc = $('.lightbox-figure span', lightbox);
    let lbIndex = 0;

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
      const capB = $('figcaption b', card);
      card.setAttribute('aria-label', `放大查看：${capB ? capB.textContent : '应用截图'}`);
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
    { tag_name: 'v0.5.1', published_at: '2026-08-14', body: '- 新增实时语音对话模式\n- 新增数据导出中心，支持按类别批量导出/导入本地数据\n- 数据导出中心支持详细数据选择和滚动浏览\n- 工作区支持长按移动文件进入文件夹\n- Token 用量界面新增平均价格与活跃会话展示\n- 聊天界面新增命令与 Agent 技能建议\n- 设置页面新增开发者性能监视器\n- 修复底部导航栏标签文本换行问题\n- 网络请求默认使用 HTTPS 并对 HTTP 发出警告\n- 完善多语言 UI 覆盖' },
    { tag_name: 'v0.5.0', published_at: '2026-08-13', body: '- 新增全局搜索与命令中心\n- WebDAV 备份新增增量修订历史与指定版本恢复\n- 扩展本地 Agent 的 Android 系统操作与应用发现能力\n- 新增可由用户和 AI 共同维护的全局 Agent 记忆\n- 本地 AI 模型列表新增按功能筛选与分类计数\n- 优化 Compose 状态管理、数据库迁移验证与 Release 安装包体积' },
    { tag_name: 'v0.4.10', published_at: '2026-08-13', body: '- 新增聊天界面背景自定义功能（关闭/跟随立绘/自定义图片）及背景强度调节\n- 字号调节改为 12-24sp 精确滑块并支持「跟随系统字号」开关\n- Token 记录增加估算费用（USD）的计算与展示\n- 数据库备份支持密码保护凭据导入导出\n- 新增 /wenku8_login 与 /wenku_login 斜杠命令\n- 完善角色生成与 RAG 设置的多语言适配' },
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
