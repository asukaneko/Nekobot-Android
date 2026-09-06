package com.nekobot.app.data.local.ai

import java.util.Locale

/**
 * android_help 工具的操作指南：帮助 Agent 在操作 Android 设备前了解
 * 能力边界、标准流程、定位方式与常见陷阱。内容随应用语言本地化。
 */
internal object AndroidUsageGuide {

    /**
     * 返回操作指南文本。
     *
     * @param language 应用语言（zh/en/ja/ko，自动归一化）
     * @param topic 章节名（permissions/flow/selectors/index/gestures/input/scroll/observe/troubleshooting/safety）；
     *              为空或 all 时返回完整指南
     */
    fun build(language: String, topic: String?): String {
        val lang = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        val chapters = when (lang) {
            "ja" -> jaChapters()
            "ko" -> koChapters()
            "en" -> enChapters()
            else -> zhChapters()
        }
        val requested = topic?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (requested.isEmpty() || requested == "all") {
            return buildString {
                appendLine("# Android 操作指南")
                appendLine()
                chapters.forEachIndexed { index, (key, body) ->
                    append(body)
                    if (index < chapters.lastIndex) append("\n\n")
                }
            }
        }
        val matched = chapters.filter { it.first == requested || it.first.contains(requested) }
        if (matched.isEmpty()) {
            val available = chapters.joinToString("、") { it.first }
            return "未知主题「$requested」。可用主题：$available。省略 topic 可查看完整指南。"
        }
        return matched.joinToString("\n\n") { it.second }
    }

    // ------------------------------------------------------------------
    // 中文（默认）
    // ------------------------------------------------------------------

    private fun zhChapters(): List<Pair<String, String>> = listOf(
        "permissions" to """
### 权限（permissions）
- 操作前先调用 android_accessibility_status 确认「辅助功能」已开启并连接；未开启时用 android_open_settings(target=accessibility) 引导用户开启。
- 读取/操作通知需开启「通知使用权」（android_open_settings target=notification_listener）。
- 截图需要 Android 11+ 且辅助功能已连接；截图失败时改用 android_ui_tree 观察。
        """.trimIndent(),
        "flow" to """
### 标准操作流程（flow）
1. 打开目标应用：android_open_app（按名称/包名），或 android_open_url / android_global_action(home/recents) 切换。
2. 观察界面：android_ui_tree（interactive_only=true 只看可交互元素），或 android_step（动作+自动截图+视觉描述）。
3. 定位目标元素：优先用 interactive 编号（index），其次用文字/内容描述（selector+field）。
4. 执行操作：android_ui_click / android_ui_tap / android_ui_set_text / android_ui_scroll / android_ui_swipe / android_ui_ime_action。
5. 等待结果：android_wait_for_idle 等待加载/动画结束。
6. 确认结果：android_screenshot + understand_image（或直接 android_step），比对是否达到预期。
7. 未达预期：分析错误与界面状态，调整定位方式或滚动后重试，不要盲目重复相同操作。
        """.trimIndent(),
        "selectors" to """
### 元素定位（selectors）
- android_ui_click / android_ui_set_text / android_ui_scroll 支持按文字查找：selector 为要匹配的文本、内容描述或资源 ID。
- field 限定匹配范围：auto（默认，同时匹配 text/description/view_id）/ text / description / view_id / class。
- exact=true 要求完整匹配（不区分大小写）；默认模糊包含匹配。
- 同一文字可能匹配多个元素：先看 android_ui_tree 的 interactive 列表，改用 index 精确指定。
- 密码字段文本始终脱敏显示为 <redacted>，不要尝试读取密码。
        """.trimIndent(),
        "index" to """
### 编号定位（index）
- android_ui_tree 返回的 interactive 列表为当前可点击/可输入/可滚动元素分配了从 0 开始的编号，每项含 role/text/view_id/bounds。
- 点击/输入/滚动时传 index 即可精确定位，避免文本重复歧义：如 android_ui_click(index=3)。
- 编号只在本次 ui_tree 结果内有效；界面变化后编号可能失效，操作前应重新读取。
- 列表类界面（商品、搜索结果）几乎一定有重复文本，优先使用 index。
        """.trimIndent(),
        "gestures" to """
### 坐标手势（gestures）
- android_ui_tap 按坐标点击：传 x/y（屏幕像素坐标），或传 selector/index 自动取其 bounds 中心。
- android_ui_swipe 按坐标或方向滑动：传 x1,y1,x2,y2 精确滑动，或 direction+index/selector 在元素区域内滑动。
- 游戏、自绘 View（SurfaceView/GLSurfaceView/TextureView）、部分 Flutter/RN 应用不暴露可点击节点或对 ACTION_CLICK 无响应，此时用坐标手势代替语义点击。
- 长按：android_ui_tap 传 duration_ms=600 以上。
- 坐标以屏幕像素为单位；可与截图（android_screenshot）及交互元素 bounds 交叉定位。
        """.trimIndent(),
        "input" to """
### 文本输入（input）
- 常规输入：android_ui_set_text（selector 或 index 定位输入框，text 为内容）。
- 输入后触发搜索/确认：android_ui_ime_action 执行输入法回车（IME Enter）。
- 输入框不接受 ACTION_SET_TEXT 时：先 android_clipboard_write 写入文本，再 android_ui_paste 粘贴。
- 剪贴板读写需要用户授权，只在必要时使用。
        """.trimIndent(),
        "scroll" to """
### 滚动与加载（scroll）
- android_ui_scroll：direction 支持 up/down/left/right（forward/backward/next/previous 同义）；可传 selector/index 指定滚动区域，省略时滚动首个可滚动区域。
- 列表（RecyclerView）是虚拟化的：屏幕外的项不在界面树中，必须滚动后重新 ui_tree 才能看到更多内容。
- 加载中的界面先 android_wait_for_idle（timeout_ms 默认 2000）再读取，避免读到中间状态。
        """.trimIndent(),
        "observe" to """
### 观察与诊断（observe）
- android_screenshot 保存当前屏幕到会话工作区（返回 path）；android_ui_tree 返回结构化元素。
- 图片类内容（商品图、海报、游戏画面）用 understand_image 分析截图（image_url 填截图 path）。
- android_step 一步完成「执行动作 → 等待稳定 → 自动截图 → 视觉描述」，推荐在需要观察结果的动作后使用。
- 操作失败时先截图/读树定位原因，再调整参数重试。
        """.trimIndent(),
        "troubleshooting" to """
### 常见陷阱（troubleshooting）
- 相同文字多个元素 → 用 index 而非 selector。
- 元素找不到 → 可能未加载完成（先 wait_for_idle）或不在屏幕内（先滚动）。
- ACTION_CLICK 无响应 → 改用 android_ui_tap（坐标/编号 bounds 中心手势点击）。
- 截图失败 → 检查辅助功能是否连接、系统是否为 Android 11+。
- 辅助功能未连接 → 所有 ui_* 工具都会失败，先 android_accessibility_status 检查并用 android_open_settings 引导开启。
- 无障碍树为空 → 自绘应用/游戏，改用截图 + 坐标手势。
        """.trimIndent(),
        "safety" to """
### 安全规则（safety）
- 每个界面操作都会请求用户授权；用户拒绝后立即停止该操作并说明影响。
- 涉及支付、下单、转账、删除、发送等不可逆或高影响操作，即使技术上可行，也必须先向用户明确说明并等待确认，不要替用户做出购买或资金决策。
- 不读取/回显密码字段（系统已脱敏）。
- 不将剪贴板、通知、账号等敏感信息泄露到最终回复中。
- 操作敏感应用（银行、支付、邮件）时保持最小必要操作范围。
        """.trimIndent()
    )

    // ------------------------------------------------------------------
    // English
    // ------------------------------------------------------------------

    private fun enChapters(): List<Pair<String, String>> = listOf(
        "permissions" to """
### Permissions
- Before acting, call android_accessibility_status to confirm the accessibility service is enabled and connected; otherwise guide the user with android_open_settings(target=accessibility).
- Reading/acting on notifications requires the notification listener permission (android_open_settings target=notification_listener).
- Screenshots require Android 11+ with the accessibility service connected; fall back to android_ui_tree when screenshots fail.
        """.trimIndent(),
        "flow" to """
### Standard flow
1. Open the target app: android_open_app (by name/package), or switch with android_open_url / android_global_action(home/recents).
2. Observe the screen: android_ui_tree (interactive_only=true to see just interactive elements), or android_step (action + auto screenshot + vision description).
3. Locate the target element: prefer the interactive index, then text/content-description (selector+field).
4. Act: android_ui_click / android_ui_tap / android_ui_set_text / android_ui_scroll / android_ui_swipe / android_ui_ime_action.
5. Wait: android_wait_for_idle for loading/animation to settle.
6. Confirm: android_screenshot + understand_image (or android_step) and compare with the goal.
7. If not achieved: analyze the error and screen state, adjust targeting or scroll, and retry; do not blindly repeat the same call.
        """.trimIndent(),
        "selectors" to """
### Selectors
- android_ui_click / android_ui_set_text / android_ui_scroll match by text: selector is the text, content description, or view id.
- field limits matching: auto (default, matches text/description/view_id) / text / description / view_id / class.
- exact=true requires a full match (case-insensitive); default is fuzzy contains.
- The same text may match several elements: check the interactive list from android_ui_tree and use index instead.
- Password fields are always redacted as <redacted>; never try to read passwords.
        """.trimIndent(),
        "index" to """
### Index-based targeting
- android_ui_tree returns an interactive list numbering clickable/editable/scrollable elements from 0, each with role/text/view_id/bounds.
- Pass index to click/type/scroll precisely, e.g. android_ui_click(index=3), avoiding duplicate-text ambiguity.
- Indexes are only valid within the last ui_tree result; re-read after the screen changes.
- List screens (products, search results) almost always have duplicate text; prefer index.
        """.trimIndent(),
        "gestures" to """
### Coordinate gestures
- android_ui_tap taps at coordinates: pass x/y (screen pixels), or selector/index to auto-tap the bounds center.
- android_ui_swipe swipes by coordinates (x1,y1,x2,y2) or by direction+index/selector within an element's bounds.
- Games, self-drawn views (SurfaceView/GLSurfaceView/TextureView) and some Flutter/RN apps expose no clickable nodes or ignore ACTION_CLICK; use coordinate gestures there.
- Long press: android_ui_tap with duration_ms >= 600.
- Coordinates are screen pixels; cross-reference with screenshots and interactive bounds.
        """.trimIndent(),
        "input" to """
### Text input
- Normal input: android_ui_set_text (locate the field with selector or index, provide text).
- After typing, trigger search/confirm with android_ui_ime_action (IME Enter).
- If the field rejects ACTION_SET_TEXT: android_clipboard_write first, then android_ui_paste.
- Clipboard read/write requires user authorization; use only when necessary.
        """.trimIndent(),
        "scroll" to """
### Scrolling and loading
- android_ui_scroll: direction supports up/down/left/right (forward/backward/next/previous are synonyms); pass selector/index to target a region, or omit to scroll the first scrollable area.
- RecyclerViews are virtualized: off-screen items are absent from the tree; scroll then re-read ui_tree to see more.
- For loading screens, run android_wait_for_idle (timeout_ms defaults to 2000) before reading.
        """.trimIndent(),
        "observe" to """
### Observation and diagnosis
- android_screenshot saves the screen to the session workspace (returns path); android_ui_tree returns structured elements.
- For visual content (product images, posters, game frames) analyze the screenshot with understand_image (image_url = screenshot path).
- android_step performs action → wait → auto screenshot → vision description in one call; prefer it after actions whose results you need to observe.
- On failure, screenshot/read the tree first, then adjust and retry.
        """.trimIndent(),
        "troubleshooting" to """
### Common pitfalls
- Duplicate text → use index, not selector.
- Element missing → it may still be loading (wait_for_idle first) or off-screen (scroll first).
- ACTION_CLICK ignored → switch to android_ui_tap (gesture tap at bounds center).
- Screenshot fails → check accessibility connectivity and Android 11+.
- Accessibility not connected → all ui_* tools fail; check android_accessibility_status and guide the user via android_open_settings.
- Empty accessibility tree → self-drawn apps/games; use screenshots + coordinate gestures.
        """.trimIndent(),
        "safety" to """
### Safety rules
- Every UI action requests user authorization; stop immediately and explain when refused.
- For irreversible or high-impact actions (payment, ordering, transfers, deletion, sending), always explain and wait for explicit user confirmation first; never make purchase or money decisions for the user.
- Never read or echo password fields (system-redacted).
- Never leak clipboard, notification, or account data into final replies.
- Keep the operation minimal on sensitive apps (banking, payment, email).
        """.trimIndent()
    )

    // ------------------------------------------------------------------
    // 日本語
    // ------------------------------------------------------------------

    private fun jaChapters(): List<Pair<String, String>> = listOf(
        "permissions" to """
### 権限（permissions）
- 操作前に android_accessibility_status で「ユーザー補助」が有効・接続済みかを確認してください。未接続なら android_open_settings(target=accessibility) で案内します。
- 通知の読み取り・操作には「通知の利用」権限が必要です（android_open_settings target=notification_listener）。
- スクリーンショットは Android 11 以上かつユーザー補助接続が必要です。失敗時は android_ui_tree で代替します。
        """.trimIndent(),
        "flow" to """
### 標準フロー（flow）
1. 対象アプリを開く：android_open_app（名前/パッケージ名）、または android_open_url / android_global_action(home/recents) で切り替え。
2. 画面を観察：android_ui_tree（interactive_only=true で操作可能要素のみ）、または android_step（操作＋自動スクリーンショット＋視覚説明）。
3. 対象を特定：interactive の番号（index）を優先し、次に文字列（selector+field）。
4. 操作：android_ui_click / android_ui_tap / android_ui_set_text / android_ui_scroll / android_ui_swipe / android_ui_ime_action。
5. 待機：android_wait_for_idle で読み込み・アニメーション完了を待つ。
6. 確認：android_screenshot + understand_image（または android_step）で目標と照合。
7. 未達成ならエラーと画面状態を分析し、定位を変えるかスクロールして再試行。同じ呼び出しの無意味な繰り返しはしない。
        """.trimIndent(),
        "selectors" to """
### 要素定位（selectors）
- android_ui_click / android_ui_set_text / android_ui_scroll はテキスト検索に対応：selector にテキスト・説明・リソース ID を指定。
- field で範囲を限定：auto（既定、text/description/view_id を同時照合）/ text / description / view_id / class。
- exact=true で完全一致（大文字小文字を区別しない）。既定はあいまい部分一致。
- 同じ文字列が複数要素に一致する場合は android_ui_tree の interactive 一覧を確認し、index で指定。
- パスワード欄は常に <redacted> にマスクされます。読み取りを試みないでください。
        """.trimIndent(),
        "index" to """
### 番号定位（index）
- android_ui_tree の interactive 一覧は、クリック/入力/スクロール可能な要素に 0 から連番を振り、role/text/view_id/bounds を含みます。
- index を渡せば曖昧さなく定位できます：例 android_ui_click(index=3)。
- 番号は直近の ui_tree 内でのみ有効。画面が変われば再読み込みしてください。
- リスト画面（商品・検索結果）はほぼ必ず重複テキストがあるため index を優先。
        """.trimIndent(),
        "gestures" to """
### 座標ジェスチャー（gestures）
- android_ui_tap：x/y（画面ピクセル）で座標タップ。selector/index を渡すと bounds 中心を自動タップ。
- android_ui_swipe：x1,y1,x2,y2 の座標スワイプ、または direction+index/selector で要素領域内をスワイプ。
- ゲーム・自前描画 View（SurfaceView/GLSurfaceView/TextureView）や一部の Flutter/RN アプリはクリック可能ノードが無いか ACTION_CLICK を無視します。その場合は座標ジェスチャーを使用。
- 長押し：android_ui_tap で duration_ms=600 以上。
- 座標は画面ピクセル単位。スクリーンショットと interactive の bounds を併用して定位。
        """.trimIndent(),
        "input" to """
### テキスト入力（input）
- 通常入力：android_ui_set_text（selector か index で入力欄を指定、text に内容）。
- 入力後に検索・確定を実行：android_ui_ime_action（IME Enter）。
- ACTION_SET_TEXT を受け付けない入力欄：android_clipboard_write で書き込み、android_ui_paste で貼り付け。
- クリップボードの読み書きはユーザー認可が必要です。必要な時だけ使用。
        """.trimIndent(),
        "scroll" to """
### スクロールと読み込み（scroll）
- android_ui_scroll：direction は up/down/left/right（forward/backward/next/previous は同義）。selector/index で領域指定、省略時は最初のスクロール可能領域。
- RecyclerView は仮想化されており、画面外の要素はツリーにありません。スクロール後に再読込が必要。
- 読み込み中の画面は android_wait_for_idle（timeout_ms 既定 2000）で待ってから読む。
        """.trimIndent(),
        "observe" to """
### 観察と診断（observe）
- android_screenshot で画面をセッションワークスペースに保存（path 返却）。android_ui_tree は構造化要素を返します。
- 画像系（商品画像・ポスター・ゲーム画面）は understand_image でスクリーンショットを分析（image_url に path）。
- android_step は「操作→安定待機→自動スクリーンショット→視覚説明」を一括実行。結果確認が必要な操作の後に推奨。
- 失敗時は先にスクリーンショット/ツリーで原因を確認してからパラメータを調整。
        """.trimIndent(),
        "troubleshooting" to """
### よくある落とし穴
- 重複テキスト → selector ではなく index を使用。
- 要素が見つからない → 読み込み中（先に wait_for_idle）か画面外（先にスクロール）の可能性。
- ACTION_CLICK が効かない → android_ui_tap（bounds 中心のジェスチャータップ）に変更。
- スクリーンショット失敗 → ユーザー補助の接続と Android 11 以上を確認。
- ユーザー補助未接続 → すべての ui_* が失敗。android_accessibility_status で確認し android_open_settings で案内。
- ツリーが空 → 自前描画アプリ/ゲーム。スクリーンショット＋座標ジェスチャーに切り替え。
        """.trimIndent(),
        "safety" to """
### 安全ルール（safety）
- すべての画面操作はユーザー認可を要求します。拒否されたら即座に停止し影響を説明。
- 支払い・注文・送金・削除・送信など不可逆・高影響の操作は、技術的に可能でも必ず事前に明示して確認を待ち、ユーザーに代わって購入や資金判断をしない。
- パスワード欄の読み取り・表示はしない（システムでマスク済み）。
- クリップボード・通知・アカウント等の機密情報を最終返信に漏らさない。
- 銀行・決済・メール等の機密アプリでは最小限の操作範囲に留める。
        """.trimIndent()
    )

    // ------------------------------------------------------------------
    // 한국어
    // ------------------------------------------------------------------

    private fun koChapters(): List<Pair<String, String>> = listOf(
        "permissions" to """
### 권한（permissions）
- 작업 전 android_accessibility_status로 '접근성' 서비스가 활성화·연결되었는지 확인하세요. 미연결 시 android_open_settings(target=accessibility)로 안내합니다.
- 알림 읽기·조작에는 '알림 사용' 권한이 필요합니다（android_open_settings target=notification_listener）。
- 스크린샷은 Android 11 이상 + 접근성 연결이 필요합니다. 실패 시 android_ui_tree로 대체하세요.
        """.trimIndent(),
        "flow" to """
### 표준 흐름（flow）
1. 대상 앱 열기：android_open_app（이름/패키지명），또는 android_open_url / android_global_action(home/recents)로 전환.
2. 화면 관찰：android_ui_tree（interactive_only=true로 조작 가능 요소만）, 또는 android_step（동작+자동 스크린샷+시각 설명）.
3. 대상 요소 특정：interactive 번호(index) 우선, 다음으로 텍스트/내용 설명(selector+field).
4. 조작：android_ui_click / android_ui_tap / android_ui_set_text / android_ui_scroll / android_ui_swipe / android_ui_ime_action.
5. 대기：android_wait_for_idle로 로딩/애니메이션 완료를 기다림.
6. 확인：android_screenshot + understand_image（또는 android_step）로 목표와 대조.
7. 미달 시 오류와 화면 상태를 분석해定位을 바꾸거나 스크롤 후 재시도. 같은 호출을 무의미하게 반복하지 마세요.
        """.trimIndent(),
        "selectors" to """
### 요소定位（selectors）
- android_ui_click / android_ui_set_text / android_ui_scroll은 텍스트 검색 지원：selector에 텍스트·설명·리소스 ID 지정.
- field로 범위 제한：auto（기본, text/description/view_id 동시 매칭）/ text / description / view_id / class.
- exact=true는 완전 일치（대소문자 무시）。기본은 부분 일치.
- 같은 문자가 여러 요소에 매칭되면 android_ui_tree의 interactive 목록을 보고 index로 지정.
- 비밀번호 필드는 항상 <redacted>로 마스킹됩니다. 읽기를 시도하지 마세요.
        """.trimIndent(),
        "index" to """
### 번호定位（index）
- android_ui_tree의 interactive 목록은 클릭/입력/스크롤 가능 요소에 0부터 번호를 부여하며 role/text/view_id/bounds를 포함합니다.
- index를 전달하면 모호함 없이定位됩니다：예 android_ui_click(index=3).
- 번호는 직전 ui_tree 결과 내에서만 유효. 화면이 바뀌면 다시 읽으세요.
- 목록 화면（상품·검색 결과）은 거의 항상 중복 텍스트가 있으므로 index를 우선 사용.
        """.trimIndent(),
        "gestures" to """
### 좌표 제스처（gestures）
- android_ui_tap：x/y（화면 픽셀）로 좌표 탭. selector/index를 주면 bounds 중심을 자동 탭.
- android_ui_swipe：x1,y1,x2,y2 좌표 스와이프, 또는 direction+index/selector로 요소 영역 내 스와이프.
- 게임·자체 렌더링 View（SurfaceView/GLSurfaceView/TextureView）와 일부 Flutter/RN 앱은 클릭 가능 노드가 없거나 ACTION_CLICK을 무시합니다. 좌표 제스처를 사용하세요.
- 길게 누르기：android_ui_tap에서 duration_ms=600 이상.
- 좌표는 화면 픽셀 단위. 스크린샷과 interactive bounds를 함께 사용해定位.
        """.trimIndent(),
        "input" to """
### 텍스트 입력（input）
- 일반 입력：android_ui_set_text（selector 또는 index로 입력란 지정, text에 내용）.
- 입력 후 검색·확정：android_ui_ime_action（IME Enter）.
- ACTION_SET_TEXT를 거부하는 입력란：android_clipboard_write로 쓴 뒤 android_ui_paste로 붙여넣기.
- 클립보드 읽기·쓰기는 사용자 승인이 필요합니다. 꼭 필요할 때만 사용.
        """.trimIndent(),
        "scroll" to """
### 스크롤과 로딩（scroll）
- android_ui_scroll：direction은 up/down/left/right（forward/backward/next/previous 동의어）. selector/index로 영역 지정, 생략 시 첫 스크롤 가능 영역.
- RecyclerView는 가상화되어 화면 밖 요소는 트리에 없습니다. 스크롤 후 다시 읽어야 합니다.
- 로딩 중 화면은 android_wait_for_idle（timeout_ms 기본 2000）로 기다린 뒤 읽으세요.
        """.trimIndent(),
        "observe" to """
### 관찰과 진단（observe）
- android_screenshot으로 화면을 세션 워크스페이스에 저장（path 반환）. android_ui_tree는 구조화된 요소를 반환.
- 이미지류（상품 이미지·포스터·게임 화면）는 understand_image로 스크린샷 분석（image_url에 path）.
- android_step은「동작→안정 대기→자동 스크린샷→시각 설명」을 한 번에 수행. 결과 확인이 필요한 동작 후 권장.
- 실패 시 먼저 스크린샷/트리로 원인을 확인한 뒤 파라미터를 조정하세요.
        """.trimIndent(),
        "troubleshooting" to """
### 흔한 함정
- 중복 텍스트 → selector 대신 index 사용.
- 요소가 없다 → 로딩 중（먼저 wait_for_idle）이거나 화면 밖（먼저 스크롤）일 수 있음.
- ACTION_CLICK 무시 → android_ui_tap（bounds 중심 제스처 탭）으로 전환.
- 스크린샷 실패 → 접근성 연결과 Android 11 이상 확인.
- 접근성 미연결 → 모든 ui_*가 실패. android_accessibility_status로 확인하고 android_open_settings로 안내.
- 트리가 비어 있음 → 자체 렌더링 앱/게임. 스크린샷+좌표 제스처로 전환.
        """.trimIndent(),
        "safety" to """
### 안전 규칙（safety）
- 모든 화면 조작은 사용자 승인을 요청합니다. 거부되면 즉시 중단하고 영향을 설명하세요.
- 결제·주문·송금·삭제·전송 등 되돌릴 수 없거나 영향이 큰 작업은 기술적으로 가능해도 반드시 사전에 명확히 설명하고 확인을 기다리세요. 사용자를 대신해 구매나 금전 판단을 하지 마세요.
- 비밀번호 필드를 읽거나 표시하지 마세요（시스템이 마스킹）.
- 클립보드·알림·계정 등 민감 정보를 최종 답변에 노출하지 마세요.
- 은행·결제·메일 등 민감 앱에서는 최소한의 작업 범위만 유지하세요.
        """.trimIndent()
    )
}