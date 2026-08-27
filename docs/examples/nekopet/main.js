/**
 * 喵窝养成所 (nekopet.cattery)
 * 一只随真实时间成长的电子猫：会饿、会无聊、需要陪伴。
 * 长期无人照看（饱食与心情同时归零超过 48 小时）它会离家出走！
 *
 * 运行模型：脚本每次命令都会重新执行，所有持久状态都在 storage。
 */
(function () {
  "use strict";

  var KEY_PREFIX = "pet:";
  var MIN = 60 * 1000;

  /* ----------------------------- 工具函数 ----------------------------- */

  function now() { return Date.now(); }

  function clamp(v, lo, hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
  }

  /** [a, b] 区间内的随机整数（含两端） */
  function ri(a, b) {
    return a + Math.floor(Math.random() * (b - a + 1));
  }

  function pick(arr) {
    return arr[Math.floor(Math.random() * arr.length)];
  }

  function todayStr(offsetDays) {
    var d = new Date(now() + (offsetDays || 0) * 24 * 3600 * 1000);
    var m = String(d.getMonth() + 1);
    var day = String(d.getDate());
    if (m.length < 2) m = "0" + m;
    if (day.length < 2) day = "0" + day;
    return d.getFullYear() + "-" + m + "-" + day;
  }

  function fmtDuration(ms) {
    var totalMin = Math.max(0, Math.ceil(ms / MIN));
    var h = Math.floor(totalMin / 60);
    var m = totalMin % 60;
    if (h > 0) return h + " 小时 " + m + " 分钟";
    return m + " 分钟";
  }

  function bar(v) {
    var filled = Math.round(clamp(v, 0, 100) / 10);
    var s = "";
    for (var i = 0; i < 10; i++) s += i < filled ? "▰" : "▱";
    return s;
  }

  /* ----------------------------- 游戏配置 ----------------------------- */

  var STAGES = [
    { minLv: 10, name: "长老猫", face: "🐈‍⬛" },
    { minLv: 6,  name: "成年猫", face: "😼" },
    { minLv: 3,  name: "少年猫", face: "😺" },
    { minLv: 1,  name: "幼猫",   face: "🐱" }
  ];

  var EXP_BASE = 100; // 升级所需经验 = lv * 80 + 40

  var GOODS = {
    fish: { id: 1, cn: "小鱼干", price: 20, desc: "香脆主食，饱食 +28" },
    milk: { id: 2, cn: "猫奶",   price: 35, desc: "饱食 +12，心情 +22" },
    nip:  { id: 3, cn: "猫薄荷", price: 45, desc: "当场使用，心情 +40" },
    batt: { id: 4, cn: "能量罐", price: 50, desc: "当场使用，能量 +60" },
    box:  { id: 5, cn: "幸运盲盒", price: 88, desc: "当场拆开，惊喜由天定 ✨" }
  };
  var GOOD_IDS = ["fish", "milk", "nip", "batt", "box"];

  var JOBS = [
    "在便利店当迎宾猫", "给快递站看仓库", "客串直播间助播",
    "在咖啡店收银台打盹营业", "给邻居小孩当摸摸许愿池",
    "给熬夜加班的铲屎官当监工"
  ];

  var PAT_LINES = [
    "喉咙里发出小马达一样的呼噜声，尾巴尖轻轻卷住了你的手腕。",
    "眯起眼睛把头往你掌心蹭了蹭，耳朵向后压成了飞机耳。",
    "假装淡定地盯着别处，但身体诚实地拱起了背求继续。",
    "被摸到下巴时突然忘了呼吸，过了两秒才想起来要喵一声。",
    "你一停手它就用脑袋顶你的手：喂，谁允许你停的？",
    "享受得原地踩奶，在你腿上留下几个湿乎乎的小爪印。"
  ];

  var PLAY_TOYS = ["逗猫棒", "激光笔", "毛线球", "纸箱"];
  var PLAY_WIN = [
    "扑击精准命中！它叼着{t}绕房间炫耀了三圈。",
    "{t}在空中划出弧线，它腾空而起完成了一次完美扣杀。",
    "它把{t}按在爪子底下，露出『天下第一』的得意表情。"
  ];
  var PLAY_LOSE = [
    "它瞥了一眼{t}，慢悠悠转身舔毛：幼稚。",
    "对{t}热情三秒后失去兴趣，趴下装成了一滩猫饼。",
    "追了两圈开始怀疑猫生，最后瘫在旁边看你。"
  ];

  var TIPS_LINE = "\n💡 输入 /shop 补给 · /play 陪玩 · /daily 签到";

  var NEED_ADOPT =
    "🐾 喵窝里还空着哦～\n" +
    "输入 /adopt <名字> 领养一只属于这个会话的小猫吧！";

  /* --------------------------- 存储与状态机 --------------------------- */

  function petKey(ctx) {
    return KEY_PREFIX + ctx.sessionId;
  }

  function parsePet(value) {
    if (!value) return null;
    if (typeof value === "string") {
      try { value = JSON.parse(value); } catch (e) { return null; }
    }
    if (typeof value !== "object") return null;
    return value;
  }

  async function loadPet(ctx) {
    return parsePet(await ctx.api.storage.get(petKey(ctx)));
  }

  function freshPet(name) {
    return {
      n: name,
      b: now(),          // 领养时间
      ls: now(),         // 上次活跃时间
      lv: 1,
      xp: 0,
      coin: 100,
      hp: 80,            // 饱食
      md: 70,            // 心情
      en: 90,            // 能量
      bag: { fish: 4, milk: 1 },
      st: {},            // 各行为的时间戳冷却
      slp: null,         // 入睡时间戳
      dy: "",            // 最近签到日期
      stk: 0,            // 连续签到天数
      zero: null         // 饱食+心情同时归零的开始时刻
    };
  }

  /**
   * 按流逝的真实时间推进世界。
   * @returns {"ok"|"gone"} gone 表示猫已经离家出走
   */
  function tick(s) {
    var t = now();
    var hours = (t - (s.ls || t)) / 3600000;
    s.ls = t;
    if (hours <= 0) return "ok";

    if (s.slp) {
      s.en = clamp(s.en + hours * 14, 0, 100);   // 睡觉时回能
    } else {
      s.en = clamp(s.en - hours * 2, 0, 100);    // 清醒时缓慢消耗
    }
    s.hp = clamp(s.hp - hours * 4, 0, 100);
    s.md = clamp(s.md - hours * 2.5, 0, 100);

    if (s.hp <= 0 && s.md <= 0) {
      if (!s.zero) s.zero = t;
      if (t - s.zero > 48 * 3600000) return "gone";
    } else {
      s.zero = null;
    }
    return "ok";
  }

  function expNeed(lv) {
    return lv * 80 + 40;
  }

  function stageOf(lv) {
    for (var i = 0; i < STAGES.length; i++) {
      if (lv >= STAGES[i].minLv) return STAGES[i];
    }
    return STAGES[STAGES.length - 1];
  }

  function moodWord(md) {
    if (md >= 85) return "飞扬跋扈地快乐";
    if (md >= 65) return "心满意足";
    if (md >= 40) return "平静如水";
    if (md >= 20) return "闷闷不乐";
    return "委屈到炸毛";
  }

  function hungryWord(hp) {
    if (hp >= 85) return "吃得很撑";
    if (hp >= 60) return "饱腹感良好";
    if (hp >= 35) return "有点想吃东西";
    if (hp >= 15) return "肚子咕咕叫";
    return "饿得眼冒金星";
  }

  function careTip(s) {
    if (s.hp <= 25) return "⚠️ 它快饿扁了！快 /feed 或者 /shop 买粮！";
    if (s.md <= 25) return "😿 心情低落，试试 /pat 或 /play 逗逗它。";
    if (s.en <= 20) return "🥱 精力见底，让它 /sleep 补个觉吧。";
    return pick([
      " KYA~ 它看起来今天也不错。",
      " 🧶 记得常回家看看，猫是会被冷落的哦。",
      " 💤 离开太久它可是会自己去找新家的……"
    ]);
  }

  /** 消耗经验并处理升级，返回升级提示或空串 */
  function gainExp(s, amount) {
    s.xp += amount;
    var msg = "";
    while (s.xp >= expNeed(s.lv)) {
      s.xp -= expNeed(s.lv);
      s.lv += 1;
      var bonus = s.lv * 25;
      s.coin += bonus;
      if (s.lv === 5 || s.lv === 10 || s.lv % 5 === 0) {
        msg += "🎓 成长里程碑！「" + stageOf(s.lv).name + "」达成！";
      }
    }
    return msg;
  }

  function cdLeft(s, key, cooldownMs) {
    var last = s.st[key] || 0;
    var passed = now() - last;
    if (passed >= cooldownMs) return 0;
    return cooldownMs - passed;
  }

  function setCd(s, key) {
    s.st[key] = now();
  }

  /* ------------------------------ 命令实现 ------------------------------ */

  async function cmdAdopt(ctx) {
    var existing = await loadPet(ctx);
    var name = (ctx.argsText || "").trim().replace(/\s+/g, " ");
    if (existing) {
      if (!name) {
        return "🐱 这个会话已经有主子啦：" + existing.n +
          "\n想换名字的话输入 /rename <新名字>。";
      }
      return "一只猫怎么可以领养两只喵！\n要不…先去 /rank 看看隔壁家的猫？" ;
    }
    if (!name) return "用法：/adopt <名字>\n例如：/adopt 年糕";
    if (name.length > 12) return "名字最多 12 个字符，太长它记不住喵。";

    var s = freshPet(name);
    await ctx.api.storage.set(petKey(ctx), s);
    try {
      await ctx.api.notify("🐾 " + name + " 加入了喵窝！");
    } catch (e) { /* notify 失败不影响结果 */ }

    return (
      "🎉 恭喜！" + stageOf(1).face + " 「" + name + "」正式加入你的喵窝～\n" +
      "立绘：" + stageOf(1).name + " Lv.1\n" +
      "新手礼包已发放：100 小鱼币 + 4 小鱼干 + 1 猫奶\n\n" +
      "📖 养猫须知：\n" +
      "• /pet 查看状态　/feed 投喂　/pat 摸头\n" +
      "• /play 逗猫　/work 打工赚钱　/daily 每日签到\n" +
      "• 商店在 /shop，排行榜在 /rank\n\n" +
      "⏰ 时间是真实流动的：你会离开的每一小时，它都会饿一点点、无聊一点点。" +
      "如果不管它太久（饱食和心情同时归零并持续两天），它就会收拾行李离家出走喵！"
    );
  }

  async function cmdPet(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }

    var st = stageOf(s.lv);
    var need = expNeed(s.lv);
    var lines = [];
    lines.push(st.face + " ── 喵窝档案 ──");
    lines.push("名字：「" + s.n + "」（" + st.name + " Lv." + s.lv + "）");
    lines.push("🌊 经验　" + bar((s.xp / need) * 100) + " " + s.xp + "/" + need);
    lines.push("🍚 饱食　" + bar(s.hp) + " " + Math.round(s.hp) + "/100（" + hungryWord(s.hp) + "）");
    lines.push("❤️ 心情　" + bar(s.md) + " " + Math.round(s.md) + "/100（" + moodWord(s.md) + "）");
    lines.push("⚡ 能量　" + bar(s.en) + " " + Math.round(s.en) + "/100");
    lines.push("🐟 小鱼币　" + s.coin + " 枚　🎒 背包　鱼干x" + (s.bag.fish || 0) + " 牛奶x" + (s.bag.milk || 0));
    lines.push("📅 已陪伴 " + Math.max(1, Math.ceil((now() - s.b) / 86400000)) + " 天　签到连击 x" + s.stk);
    if (s.slp) lines.push("💤 正在呼呼大睡中（再发一次 /sleep 可以唤醒）");
    lines.push("");
    lines.push(pick([
      "它看见你进来，耳朵转了个方向。",
      "它从纸箱里探出了半个脑袋。",
      "它假装没看见你，但尾巴出卖了它的兴奋。",
      "它突然冲过来在你脚边翻了个肚皮。"
    ]) + careTip(s));

    await ctx.api.storage.set(petKey(ctx), s);
    return lines.join("\n");
  }

  function runAwayText(s) {
    return (
      "💨 ……" + s.n + " 收拾好了自己的玩具和毯子。\n" +
      "长期没人喂也没人陪，它留下一张字条离开了这个会话：\n\n" +
      "　『不是不爱这里，只是也想被人惦记喵。』\n\n" +
      "想重新开始的话，随时可以 /adopt <名字> 再遇见下一只猫。"
    );
  }

  async function cmdFeed(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    if (s.slp) return sleepBlock(s);

    var left = cdLeft(s, "feed", 15 * MIN);
    if (left > 0) {
      return "🍽️ 才刚吃过！" + s.n + " 建议你先看看它的圆肚子。" +
        "\n下次投喂解锁：约 " + fmtDuration(left) + " 后";
    }

    var food = null;
    if ((s.bag.fish || 0) > 0) food = "fish";
    else if ((s.bag.milk || 0) > 0) food = "milk";
    if (!food) {
      return "📦 背包空空！背包里已经没有小鱼干和牛奶了。" +
        "\n输入 /shop 看看商品，然后 /buy 1 买些鱼干。";
    }

    s.bag[food] -= 1;
    if (s.bag[food] <= 0) delete s.bag[food];

    var line = "";
    if (food === "fish") {
      s.hp = clamp(s.hp + 28, 0, 100);
      line = pick([
        "「咔嚓咔嚓——」整袋鱼干三口见底，尾巴摇成了螺旋桨。",
        "它叼起鱼干藏进窝里假装吃掉了，其实是想留着慢慢啃。"
      ]);
    } else {
      s.hp = clamp(s.hp + 12, 0, 100);
      s.md = clamp(s.md + 22, 0, 100);
      line = pick([
        "咕嘟咕嘟…喝完还伸出舌头把碗底舔得反光。",
        "小胡子沾了一圈奶渍，一脸无辜地看着你。"
      ]);
    }
    setCd(s, "feed");
    var up = gainExp(s, food === "fish" ? 12 : 10);

    var reply =
      "🥫 " + line + "\n" +
      ("🍚 饱食 " + bar(s.hp) + " ❤️ 心情 " + bar(s.md)) + "\n" +
      "🎒 背包剩余：鱼干x" + (s.bag.fish || 0) + " 牛奶x" + (s.bag.milk || 0) +
      (up ? "\n" + up : "") +
      (s.hp <= 25 ? "\n⚠️ 还是好饿！记得去 /shop 囤货！" : "");
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  async function cmdPat(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    if (s.slp) return sleepBlock(s);

    var left = cdLeft(s, "pat", 10 * MIN);
    if (left > 0) {
      return "🐾 已经rua过一轮啦，让小猫喘口气～\n下次可摸：" + fmtDuration(left) + " 后";
    }

    setCd(s, "pat");
    s.md = clamp(s.md + 6, 0, 100);
    s.en = clamp(s.en - 1, 0, 100);
    var extra = "";
    if (Math.random() < 0.08) {
      s.md = clamp(s.md + 4, 0, 100);
      extra = "\n✨ 稀有事件！它主动翻出肚皮任你揉，心情 +4！";
      try { await ctx.api.notify("🐱 " + s.n + " 触发了翻肚皮彩蛋！"); } catch (e) {}
    }
    var up = gainExp(s, 4);

    var reply =
      "🤚 你轻轻rua了rua " + s.n + " 的头顶…\n" +
      pick(PAT_LINES) + extra + "\n" +
      "❤️ 心情 " + bar(s.md) + up;
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  async function cmdPlay(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    if (s.slp) return sleepBlock(s);
    if (s.en < 12) {
      return "😴 " + s.n + " 累到直不起腰，玩不动了。\n让它 /sleep 补眠，或者 /buy 4 来罐能量饮料！";
    }

    s.en = clamp(s.en - 12, 0, 100);
    var toy = PLAY_TOYS[Math.floor(Math.random() * PLAY_TOYS.length)];
    var win = Math.random() < 0.6;
    var moodGain = win ? ri(16, 26) : ri(4, 9);
    s.md = clamp(s.md + moodGain, 0, 100);

    var tip = 0;
    if (win && Math.random() < 0.3) tip = ri(2, 8);
    s.coin += tip;
    var up = gainExp(s, ri(8, 14));

    var tpl = pick(win ? PLAY_WIN : PLAY_LOSE);
    var flavor = tpl.split("{t}").join(toy);

    var reply =
      "🎾 拿出【" + toy + "】，" + s.n + " 的瞳孔瞬间放大成铜铃！\n" +
      flavor + "\n" +
      "❤️ 心情 +" + moodGain + "（" + bar(s.md) + "）　⚡ 能量 " + bar(s.en) + "\n" +
      (tip > 0 ? "💰 路过的铲屎官看得直鼓掌，当场打赏 " + tip + " 小鱼币！\n" : "") +
      up;
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  function sleepBlock(s) {
    return "💤 Zzz…" + s.n + " 正睡得四仰八叉。\n（再发一次 /sleep 就会把它叫醒）";
  }

  async function cmdSleep(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }

    if (!s.slp) {
      if (s.en > 55) {
        return "😾 " + s.n + " 正上蹿下跳呢，一点也不困！\n等能量低于 55 再来哄睡吧（用 /play 消耗精力试试）。";
      }
      s.slp = now();
      await ctx.api.storage.set(petKey(ctx), s);
      return (
        "🌙 关灯，盖好小毯子。" + s.n + " 缩成一个甜甜圈睡着了。\n" +
        "每睡 1 小时恢复约 14 点能量，睡满 7 小时有熟睡奖励。" +
        "\n醒来之前其他活动都暂停啦；再发一次 /sleep 可以提前唤醒。"
      );
    }

    var sleptMs = now() - s.slp;
    s.slp = null;
    var gained = Math.min(100 - s.en, Math.floor((sleptMs / 3600000) * 13));
    s.en = clamp(s.en + gained, 0, 100);
    var bonusTxt = "";
    if (sleptMs >= 7 * 3600000) {
      s.md = clamp(s.md + 10, 0, 100);
      s.coin += 12;
      bonusTxt = "\n🌟 睡满 7 小时！毛色发亮，心情 +10，找到睡前藏的 12 小鱼币私房钱！";
    } else if (sleptMs < 30 * MIN) {
      bonusTxt = "\n（这就醒了？翻了个身又阖上了眼睛。）";
    }
    var up = gainExp(s, 6);
    var reply =
      "☀️ 揉揉眼…" + s.n + " 打了一个大大的哈欠醒来了。\n" +
      "🛌 这次睡了 " + fmtDuration(sleptMs) + "，恢复 " + gained + " 点能量。" +
      bonusTxt + up;
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  async function cmdWork(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    if (s.slp) return sleepBlock(s);
    if (s.en < 20) return "😵 心有余而力不足！先去睡觉吧（/sleep）";
    if (s.hp < 15) return "铝…肚子太空了干不动活，喂点吃的再来吧！(/feed)";

    var left = cdLeft(s, "work", 45 * MIN);
    if (left > 0) {
      return "💼 今日排班已满！下一次开工：" + fmtDuration(left) + " 后";
    }
    setCd(s, "work");

    var pay = ri(24, 40) + s.lv * 2;
    var job = pick(JOBS);
    s.coin += pay;
    s.en = clamp(s.en - 22, 0, 100);
    s.hp = clamp(s.hp - 8, 0, 100);
    var up = gainExp(s, 15);

    var reply =
      "💼 " + s.n + " 去「" + job + "」搬了一天的砖…\n" +
      "下班时工牌歪了、围兜上全是猫毛，但是赚到了 **" + pay + "** 小鱼币！\n" +
      "🐟 余额 " + s.coin + " 枚　⚡ 能量 " + bar(s.en) + up;
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  async function cmdDaily(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }

    var today = todayStr(0);
    if (s.dy === today) {
      return "✅ 今天已经签过到啦！明天 " + s.n + " 继续蹲守门口等你。";
    }
    s.stk = (s.dy === todayStr(-1)) ? s.stk + 1 : 1;
    s.dy = today;

    var coins = 40 + Math.min(s.stk, 7) * 5 + ri(0, 15);
    s.coin += coins;
    s.bag.fish = (s.bag.fish || 0) + 1;
    var giftTxt = "🎁 附赠小鱼干 x1（已放进背包）";
    if (s.stk > 0 && s.stk % 7 === 0) {
      s.bag.milk = (s.bag.milk || 0) + 2;
      giftTxt += "，连续第 " + s.stk + " 天额外获得猫奶 x2 🥛";
    }
    s.md = clamp(s.md + 8, 0, 100);
    var up = gainExp(s, 15);

    var reply =
      "📅 签到成功！这是第 " + s.stk + " 天连续来看它～\n" +
      s.n + " 从窗台上跳下来蹭了蹭你的裤脚，好像知道你来了。\n" +
      "💰 获得 " + coins + " 小鱼币　🪙 余额 " + s.coin + "\n" +
      giftTxt + up;
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  async function cmdShop(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    var lines = ["🛒 ── 喵窝商店 ──　🪙 你的余额：" + s.coin];
    for (var i = 0; i < GOOD_IDS.length; i++) {
      var k = GOOD_IDS[i];
      var g = GOODS[k];
      lines.push(g.id + ". " + g.cn + "　" + g.price + " 币　· " + g.desc);
    }
    lines.push("\n（购买：/buy 编号；猫薄荷、能量罐和盲盒买完立即生效，鱼干牛奶存入背包）");
    await ctx.api.storage.set(petKey(ctx), s);
    return lines.join("\n");
  }

  function openGacha(s) {
    var roll = Math.random();
    if (roll < 0.30) {
      var c = ri(40, 160);
      s.coin += c;
      return "✨ 拆出了红包猫砂垫！里面有 **" + c + "** 小鱼币现金！（余额 " + s.coin + "）";
    }
    if (roll < 0.55) {
      s.md = clamp(s.md + 50, 0, 100);
      return "✨ 是自动逗猫棒Pro Max！" + s.n + " 玩到疯，心情直接拉满一波（+50）。";
    }
    if (roll < 0.75) {
      s.bag.fish = (s.bag.fish || 0) + 2;
      return "✨ 是珍藏鱼干礼盒！背包增加小鱼干 x2。";
    }
    if (roll < 0.90) {
      s.bag.milk = (s.bag.milk || 0) + 1;
      return "✨ 是小瓶羊奶粉，兑换成了猫奶 x1 放进背包。";
    }
    s.coin += 300;
    return "🎆🎆 终极隐藏款——纯金招财猫摆件！！变卖后入手 **300** 小鱼币！（余额 " + s.coin + "）";
  }

  async function cmdBuy(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    if (s.slp) return sleepBlock(s);

    var arg = (ctx.args[0] || "").trim();
    if (!arg) return "用法：/buy <编号>，输入 /shop 可查看货架。";

    var target = null;
    for (var i = 0; i < GOOD_IDS.length; i++) {
      var g = GOODS[GOOD_IDS[i]];
      if (arg === String(g.id)) { target = g; break; }
    }
    if (!target) return "货架上没有「" + arg + "」哦。输入 /shop 核对一下编号吧。";
    if (s.coin < target.price) {
      return "💸 余额不足！" + target.cn + " 需要 " + target.price + " 币，你现在只有 " + s.coin + " 枚。\n试试 /work 打工或者坚持 /daily 签到攒钱～";
    }
    s.coin -= target.price;

    var resultLine = "";
    if (target.id === 1 || target.id === 2) {
      s.bag[target.id === 1 ? "fish" : "milk"] = (s.bag[target.id === 1 ? "fish" : "milk"] || 0) + 1;
      resultLine = "已放入背包 🎒（鱼干x" + (s.bag.fish || 0) + " 牛奶x" + (s.bag.milk || 0) + "），/feed 即可投喂。";
    } else if (target.id === 3) {
      s.md = clamp(s.md + 40, 0, 100);
      resultLine = "当场拆封撒在地上——" + s.n + " 已经滚进了薄荷宇宙。（心情 " + bar(s.md) + "）";
    } else if (target.id === 4) {
      s.en = clamp(s.en + 60, 0, 100);
      resultLine = "嗝——？尾巴上的毛都竖起来了，功率全开！（能量 " + bar(s.en) + "）";
    } else {
      resultLine = openGacha(s);
    }

    var reply = "🛍️ 花费 " + target.price + " 小鱼币购买了【" + target.cn + "】。\n" + resultLine;
    await ctx.api.storage.set(petKey(ctx), s);
    return reply;
  }

  async function cmdBag(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    await ctx.api.storage.set(petKey(ctx), s);
    return (
      "🎒 " + s.n + " 的储物箱\n" +
      "小鱼干 x" + (s.bag.fish || 0) + "\n" +
      "猫奶　 x" + (s.bag.milk || 0) + "\n\n" +
      "鱼干和牛奶通过 /feed 使用；猫薄荷、能量罐与盲盒请在 /shop 直接购买，买完立刻生效。"
    );
  }

  async function cmdRank(ctx) {
    var all = {};
    try {
      all = (await ctx.api.storage.list()) || {};
    } catch (e) {
      return "暂时读不到排行榜数据喵。";
    }
    var rows = [];
    for (var key in all) {
      if (key.indexOf(KEY_PREFIX) !== 0) continue;
      var p = parsePet(all[key]);
      if (!p || !p.n) continue;
      rows.push({
        n: p.n,
        lv: p.lv || 1,
        coin: p.coin || 0,
        sid: key.slice(key.indexOf(":") + 1),
        sleeping: !!p.slp
      });
    }
    if (rows.length === 0) return "本地还没有任何猫咪上榜。先 /adopt 一只吧！";

    rows.sort(function (a, b) { return (b.lv - a.lv) || (b.coin - a.coin); });

    var medals = ["🥇", "🥈", "🥉"];
    var lines = ["🏆 ── 喵窝荣誉堂（本机前 8）──"];
    for (var j = 0; j < rows.length && j < 8; j++) {
      var r = rows[j];
      var badge = medals[j] || (j + 1 + ".");
      var flag = r.sid === ctx.sessionId ? " ←你家这只" : "";
      lines.push(badge + " " + stageOf(r.lv).face + " " + r.n + "　Lv." + r.lv + " · " + r.coin + " 币" +
        (r.sleeping ? "💤" : "") + flag);
    }
    return lines.join("\n");
  }

  async function cmdRename(ctx) {
    var s = await loadPet(ctx);
    if (!s) return NEED_ADOPT;
    if (tick(s) === "gone") {
      await ctx.api.storage.remove(petKey(ctx));
      return runAwayText(s);
    }
    var left = cdLeft(s, "rn", 6 * 60 * MIN);
    if (left > 0) {
      return "📛 改名太频繁会让猫精神分裂喵！\n下次改名：" + fmtDuration(left) + " 后";
    }
    var name = (ctx.argsText || "").trim().replace(/\s+/g, " ");
    if (!name) return "用法：/rename <新名字>";
    if (name.length > 12) return "新名字最多 12 个字符。";
    var old = s.n;
    s.n = name;
    setCd(s, "rn");
    await ctx.api.storage.set(petKey(ctx), s);
    return "📛 「" + old + "」听到新名字歪了歪头，思考三秒后…接受了！今后请多指教，「" + name + "」。";
  }

  /* ------------------------------ 注册入口 ------------------------------ */

  NekoPlugin.register({
    commands: {
      adopt: cmdAdopt,
      pet: cmdPet,
      feed: cmdFeed,
      pat: cmdPat,
      play: cmdPlay,
      sleep: cmdSleep,
      work: cmdWork,
      daily: cmdDaily,
      shop: cmdShop,
      buy: cmdBuy,
      bag: cmdBag,
      rank: cmdRank,
      rename: cmdRename
    }
  });
})();