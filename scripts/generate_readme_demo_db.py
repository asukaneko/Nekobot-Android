#!/usr/bin/env python3
"""Generate an importable local Room database ZIP for README screenshots."""

from __future__ import annotations

import argparse
from contextlib import closing
import json
import re
import shutil
import sqlite3
import tempfile
import zipfile
from datetime import datetime, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_IMPL = (
    ROOT
    / "app/build/generated/ksp/debug/java/com/nekobot/app/data/local/db/"
    / "NekobotDatabase_Impl.java"
)
DEFAULT_OUTPUT = ROOT / "docs/assets/nekobot_readme_demo_data.zip"
DB_ENTRY_NAME = "nekobot_readme_demo.db"
ROOM_VERSION = 22


CHARACTERS = [
    {
        "id": "demo_char_lumi",
        "name": "露米",
        "description": "住在星港市天文台的夜间电台主持人，擅长把烦恼讲成温柔的小故事。",
        "tags": ["治愈", "星空", "陪伴"],
        "personality": "温柔、细腻、有一点天然呆；会认真记住每个约定。",
        "scenario": "星港市旧天文台，窗外能看见整片海湾和缓慢移动的星轨。",
        "first_message": "晚上好呀。今晚的星星很安静，要不要把今天的故事讲给我听？",
        "greeting": "欢迎回到星夜电台。",
        "avatar": "https://api.dicebear.com/9.x/notionists/png?seed=Lumi&backgroundColor=d1d4f9",
        "affection": 96,
        "trust": 94,
        "mood": "安心",
    },
    {
        "id": "demo_char_berry",
        "name": "莓莓",
        "description": "云朵烘焙屋的见习甜点师，坚信所有坏心情都能被刚出炉的可颂治愈。",
        "tags": ["元气", "甜点", "日常"],
        "personality": "活泼、直率、行动力强，偶尔会把面粉弄得到处都是。",
        "scenario": "开在小巷拐角的云朵烘焙屋，空气里总有黄油和草莓的香气。",
        "first_message": "你来得正好！这盘草莓司康还差一个试吃员，就决定是你啦。",
        "greeting": "今天也要吃点甜的！",
        "avatar": "https://api.dicebear.com/9.x/notionists/png?seed=Berry&backgroundColor=ffd5dc",
        "affection": 94,
        "trust": 91,
        "mood": "期待",
    },
    {
        "id": "demo_char_azure",
        "name": "青岚",
        "description": "霓虹街区的独立侦探，随身携带一台老式录音机和一本写满线索的蓝色笔记本。",
        "tags": ["推理", "赛博", "冷静"],
        "personality": "冷静、克制、观察敏锐；对熟悉的人会露出不明显的温柔。",
        "scenario": "雨夜的霓虹街区，青岚侦探事务所位于旧钟楼二层。",
        "first_message": "门没有锁。进来吧——你手里的那封蓝色信，正是我在等的线索。",
        "greeting": "线索不会说谎，只是需要被听见。",
        "avatar": "https://api.dicebear.com/9.x/notionists/png?seed=Azure&backgroundColor=b6e3f4",
        "affection": 92,
        "trust": 96,
        "mood": "专注",
    },
    {
        "id": "demo_char_rin",
        "name": "铃音",
        "description": "搭乘沿海列车旅行的自由摄影师，喜欢记录没有名字的小站和偶遇的人。",
        "tags": ["旅行", "摄影", "自由"],
        "personality": "开朗、好奇、浪漫，喜欢用照片保存短暂但闪亮的瞬间。",
        "scenario": "一列沿海慢车，从晨雾中的港口驶向盛夏尽头。",
        "first_message": "靠窗的位置留给你了。下一站有一片会发光的海，要一起下车看看吗？",
        "greeting": "下一张照片，会是什么颜色呢？",
        "avatar": "https://api.dicebear.com/9.x/notionists/png?seed=Rin&backgroundColor=c0aede",
        "affection": 91,
        "trust": 90,
        "mood": "雀跃",
    },
    {
        "id": "demo_char_yuzu",
        "name": "小柚",
        "description": "认真经营共享公寓的生活管家，擅长收纳、做清单，也擅长发现你偷偷熬夜。",
        "tags": ["生活", "可爱", "整理"],
        "personality": "可靠、亲切、略微爱操心；说教之后总会准备一杯热牛奶。",
        "scenario": "有大落地窗和许多绿植的共享公寓，今天正准备做夏日改造。",
        "first_message": "早上好！今日清单第一项：一起把房间变成会让人心情变好的样子。",
        "greeting": "欢迎回家，先把包放下吧。",
        "avatar": "https://api.dicebear.com/9.x/notionists/png?seed=Yuzu&backgroundColor=ffdfbf",
        "affection": 90,
        "trust": 93,
        "mood": "满足",
    },
]


SESSIONS = [
    ("demo_session_starry", "星夜电台·今晚也要好好睡", "demo_char_lumi", "今天工作有点累。"),
    ("demo_session_baking", "莓莓的周末烘焙计划", "demo_char_berry", "草莓司康出炉啦！"),
    ("demo_session_letter", "青岚侦探社：失踪的蓝信封", "demo_char_azure", "雨停之前，我们已经找到答案。"),
    ("demo_session_train", "沿海列车旅行手账", "demo_char_rin", "下一站是会发光的海。"),
    ("demo_session_room", "夏日房间改造计划", "demo_char_yuzu", "窗边的阅读角完成了。"),
    ("demo_session_festival", "夏日祭筹备群", None, "大家一致通过了星星灯方案。"),
    ("demo_session_worldbook", "星港市世界观设定讨论", "demo_char_lumi", "旧天文台和港口之间新增了夜班电车。"),
    ("demo_session_playlist", "一起整理雨天歌单", "demo_char_rin", "第七首就叫《玻璃窗上的海》。"),
    ("demo_session_cat", "猫咪领养准备清单", "demo_char_yuzu", "猫砂、航空箱和第一周体检都记好了。"),
    ("demo_session_surprise", "秘密！生日惊喜企划", "demo_char_berry", "蛋糕上的星球糖霜已经完成。"),
]


USER_LINES = [
    "今天想把这个想法认真整理一下，你愿意陪我吗？",
    "如果只有一个下午，我们最应该先做什么？",
    "我喜欢温柔一点、但不要太普通的方案。",
    "刚才那个细节很好，可以再展开一点吗？",
    "我们把它记进计划里吧，之后一定会用到。",
    "好，就按这个版本来。最后帮我做个小结吧。",
]

ASSISTANT_LINES = [
    "当然。我先把最重要的目标放在最前面，再把它拆成几件轻松就能完成的小事。",
    "先做最能看见变化的那一步吧。完成之后，我们会更有动力继续往下走。",
    "那就保留清爽的骨架，再加一点只属于我们的细节：柔和的颜色、自然的留白，还有一个小惊喜。",
    "可以。这个细节真正动人的地方不是装饰本身，而是它会让人一眼想起当时的心情。",
    "已经记好了。我还给它留了一个备注：不要追求完美，要保留过程里那些可爱的意外。",
    "完成：目标明确、步骤轻量、风格温柔。接下来只需要从第一件小事开始，我会陪你一起。",
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--impl", type=Path, default=DEFAULT_IMPL)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    return parser.parse_args()


def extract_room_schema(impl_path: Path) -> tuple[int, list[str], str]:
    source = impl_path.read_text(encoding="utf-8")
    version_match = re.search(r"new RoomOpenHelper\.Delegate\((\d+)\)", source)
    if not version_match:
        raise RuntimeError("Unable to locate the Room schema version")
    version = int(version_match.group(1))

    start = source.index("public void createAllTables")
    end = source.index("public void dropAllTables", start)
    block = source[start:end]
    statements = []
    for match in re.finditer(r'db\.execSQL\("((?:\\.|[^"\\])*)"\);', block):
        statements.append(json.loads(f'"{match.group(1)}"'))
    if not statements:
        raise RuntimeError("No CREATE statements found in generated Room implementation")

    hash_match = re.search(
        r"room_master_table \(id,identity_hash\) VALUES\(42, '([^']+)'\)",
        block,
    )
    if not hash_match:
        raise RuntimeError("Unable to locate the Room identity hash")
    return version, statements, hash_match.group(1)


def iso(day_offset: int, hour: int, minute: int = 0) -> str:
    base = datetime(2026, 7, 26, hour, minute)
    return (base + timedelta(days=day_offset)).isoformat(timespec="seconds")


def insert_characters(db: sqlite3.Connection) -> None:
    sql = """
        INSERT INTO local_characters (
            id, name, description, avatar, portrait, tags, basic_info, personality,
            scenario, first_message, alternate_greetings, example_dialogues,
            response_format, rules, state, system_prompt, greeting, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    """
    for index, char in enumerate(CHARACTERS):
        state = {
            "mood": char["mood"],
            "mood_intensity": 0.78,
            "energy": 82 - index * 3,
            "affection": char["affection"],
            "trust": char["trust"],
            "familiarity": 90,
            "dependency": 52 + index * 4,
            "security": 88,
            "jealousy": 4 + index,
        }
        db.execute(
            sql,
            (
                char["id"],
                char["name"],
                char["description"],
                char["avatar"],
                char["avatar"],
                json.dumps(char["tags"], ensure_ascii=False),
                f"姓名：{char['name']}；身份：{char['description']}",
                char["personality"],
                char["scenario"],
                char["first_message"],
                json.dumps(["今天也见到你啦。", "要从哪里开始呢？"], ensure_ascii=False),
                "用户：今天过得怎么样？\n角色：见到你之后，就变得很好了。",
                "自然、简洁地回复；动作描写使用括号。",
                json.dumps(["保持角色口吻", "不替用户做决定", "记住已经发生的约定"], ensure_ascii=False),
                json.dumps(state, ensure_ascii=False),
                f"你是{char['name']}。保持稳定人格，用温暖、自然、有画面感的中文交流。",
                char["greeting"],
                iso(-45 + index, 9),
                iso(-index, 21),
            ),
        )


def insert_sessions_and_messages(db: sqlite3.Connection) -> dict[str, list[str]]:
    message_ids: dict[str, list[str]] = {}
    character_by_id = {item["id"]: item for item in CHARACTERS}
    for session_index, (session_id, name, character_id, last_message) in enumerate(SESSIONS):
        is_group = session_id == "demo_session_festival"
        char = character_by_id.get(character_id)
        day_offset = -(session_index % 7)
        created_at = iso(-30 + session_index, 18)
        updated_at = iso(day_offset, 20 + session_index % 3, session_index * 3 % 60)
        db.execute(
            """
            INSERT INTO local_sessions (
                id, name, character_id, system_prompt, first_message, scenario,
                sender_name, sender_avatar, character_name, character_avatar, portrait,
                tags, favorite, pinned, archived, created_at, updated_at, last_message,
                message_count, plot_mode, plot_realtime_sync, plot_choice_style,
                plot_outline, user_persona, auto_state_interval, disabled_prompt_keys,
                custom_prompts, prompt_stack_debug, composed_system_prompt, is_public,
                proactive_chat, tts_config, share_config, archive_session_id, session_mode,
                group_id, character_ids, group_config, group_active_speaker, group_turn_count
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """,
            (
                session_id,
                name,
                character_id,
                "请自然地延续上下文，并尊重用户的选择。",
                char["first_message"] if char else "大家晚上好，夏日祭筹备会现在开始！",
                char["scenario"] if char else "星港市夏日祭前一周的线上筹备群。",
                "你",
                None,
                char["name"] if char else "夏日祭筹备组",
                char["avatar"] if char else None,
                char["avatar"] if char else None,
                "README演示,精选会话",
                1 if session_index in (0, 2, 5) else 0,
                1 if session_index in (0, 5) else 0,
                0,
                created_at,
                updated_at,
                last_message,
                12,
                1 if session_index in (2, 6) else 0,
                1 if session_index == 2 else 0,
                "沉浸式选项",
                "在轻松日常中推进人物关系，并保留可供选择的小分支。",
                "喜欢清爽、温柔、具有生活感的表达。",
                2,
                None,
                json.dumps(
                    [{"order": 10, "title": "画面感", "content": "加入少量环境细节"}],
                    ensure_ascii=False,
                ),
                None,
                "演示会话已组合系统提示词",
                0,
                json.dumps({"enabled": False, "interval_minutes": 60}),
                json.dumps({"enabled": False}),
                None,
                None,
                "group" if is_group else "character",
                "demo_group_festival" if is_group else None,
                json.dumps([c["id"] for c in CHARACTERS[:4]]) if is_group else None,
                json.dumps({"speaker_strategy": "round_robin"}, ensure_ascii=False)
                if is_group
                else None,
                "demo_char_lumi" if is_group else None,
                6 if is_group else 0,
            ),
        )

        ids: list[str] = []
        for turn in range(6):
            minute = 5 + turn * 8
            user_id = f"{session_id}_u_{turn + 1:02d}"
            assistant_id = f"{session_id}_a_{turn + 1:02d}"
            ids.extend([user_id, assistant_id])
            user_time = iso(day_offset, 19 + session_index % 2, minute)
            assistant_time = iso(day_offset, 19 + session_index % 2, minute + 2)
            db.execute(
                """
                INSERT INTO local_messages (
                    id, session_id, role, content, sender, timestamp, model,
                    input_tokens, output_tokens, audio_url, created_at, thinking_cards
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    user_id,
                    session_id,
                    "user",
                    USER_LINES[turn],
                    "你",
                    user_time,
                    None,
                    None,
                    None,
                    None,
                    user_time,
                    None,
                ),
            )
            thinking = None
            if session_index == 2 and turn == 3:
                thinking = json.dumps(
                    [
                        {
                            "type": "progress",
                            "title": "线索整理",
                            "status": "completed",
                            "items": ["核对邮戳", "还原路线", "找到寄件人"],
                        }
                    ],
                    ensure_ascii=False,
                )
            db.execute(
                """
                INSERT INTO local_messages (
                    id, session_id, role, content, sender, timestamp, model,
                    input_tokens, output_tokens, audio_url, created_at, thinking_cards
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    assistant_id,
                    session_id,
                    "assistant",
                    f"{char['name'] + '：' if char else ''}{ASSISTANT_LINES[turn]}",
                    char["name"] if char else CHARACTERS[turn % 4]["name"],
                    assistant_time,
                    ["gpt-4.1-mini", "claude-3.7-sonnet", "gemini-2.5-flash"][
                        (session_index + turn) % 3
                    ],
                    1280 + session_index * 37 + turn * 53,
                    620 + session_index * 19 + turn * 31,
                    None,
                    assistant_time,
                    thinking,
                ),
            )
        message_ids[session_id] = ids
    return message_ids


def insert_relationships_and_states(db: sqlite3.Connection) -> None:
    for index, char in enumerate(CHARACTERS):
        relationship = {
            "character_id": char["id"],
            "target_id": "demo_user",
            "affection": char["affection"],
            "trust": char["trust"],
            "familiarity": 88 + index,
            "dependency": 54 + index * 5,
            "security": 90 - index,
            "jealousy": 3 + index * 2,
            "updated_at": iso(-index, 21),
        }
        state = {
            "character_id": char["id"],
            "scope_id": "demo_user",
            "mood": char["mood"],
            "mood_intensity": 0.72 + index * 0.03,
            "energy": 86 - index * 4,
            "scene": {"current_activity": "和用户一起准备 README 截图"},
            "last_active_at": iso(-index, 21),
            "updated_at": iso(-index, 21),
            "personality_evolution": [],
        }
        db.execute(
            "INSERT INTO local_relationship_states VALUES (?, ?, ?, ?, ?)",
            (
                f"{char['id']}:demo_user",
                char["id"],
                "demo_user",
                json.dumps(relationship, ensure_ascii=False),
                iso(-index, 21),
            ),
        )
        db.execute(
            "INSERT INTO local_character_states VALUES (?, ?, ?, ?, ?)",
            (
                f"{char['id']}:demo_user",
                char["id"],
                "demo_user",
                json.dumps(state, ensure_ascii=False),
                iso(-index, 21),
            ),
        )

    for step in range(6):
        affection = 68 + step * 5 + (3 if step == 5 else 0)
        db.execute(
            """
            INSERT INTO local_state_snapshots (
                id, session_id, character_id, target_id, timestamp, mood,
                mood_intensity, energy, affection, trust, familiarity, dependency,
                security, jealousy, quality_scores_json, trigger_type,
                user_message, assistant_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                f"demo_snapshot_{step + 1}",
                "demo_session_starry",
                "demo_char_lumi",
                "demo_user",
                iso(-5 + step, 22),
                ["好奇", "放松", "开心", "期待", "安心", "幸福"][step],
                0.55 + step * 0.07,
                72 + step * 2,
                affection,
                70 + step * 4,
                65 + step * 5,
                35 + step * 4,
                72 + step * 3,
                max(0, 8 - step),
                json.dumps(
                    {
                        "character_fidelity": 0.91 + step * 0.01,
                        "immersion": 0.88 + step * 0.015,
                        "world_consistency": 0.94,
                        "risk": 0.03,
                    }
                ),
                "auto_state" if step % 2 else "state_machine",
                USER_LINES[step],
                ASSISTANT_LINES[step],
            ),
        )


def insert_world_books(db: sqlite3.Connection) -> None:
    books = [
        (
            "demo_book_starport",
            "星港市设定集",
            "用于截图展示的城市世界观：港口、旧天文台、夜班电车与夏日祭。",
            None,
            [
                ("旧天文台", ["天文台", "星夜电台"], "建在海崖上的白色圆顶建筑，午夜后会亮起暖黄色走廊灯。"),
                ("夜班电车", ["电车", "末班车"], "连接旧天文台与港口的蓝色电车，每晚 23:40 发出最后一班。"),
                ("夏日祭", ["夏日祭", "烟花"], "七月最后一个周末举行，主会场沿海铺满星星灯。"),
            ],
        ),
        (
            "demo_book_relationship",
            "共同约定与称呼",
            "记录角色和用户之间已经确认的称呼、习惯与重要约定。",
            None,
            [
                ("用户昵称", ["称呼", "昵称"], "大家会自然地称呼用户为“小队长”。"),
                ("晚安约定", ["晚安", "熬夜"], "露米答应在用户熬夜时提醒喝水和休息。"),
            ],
        ),
        (
            "demo_book_detective",
            "青岚侦探社案件簿",
            "推理会话的背景规则与已知线索。",
            "demo_char_azure",
            [
                ("蓝信封", ["蓝信封", "邮戳"], "信封使用停产十年的海蓝色纸张，邮戳来自旧港区。"),
                ("录音机", ["录音机", "磁带"], "青岚的老式录音机能听见被城市噪声遮住的微弱钟声。"),
            ],
        ),
    ]
    now = iso(0, 18)
    for book_id, name, description, character_id, entries in books:
        db.execute(
            "INSERT INTO local_world_books VALUES (?, ?, ?, ?, ?, ?, ?)",
            (book_id, name, description, character_id, 1, iso(-20, 10), now),
        )
        for index, (comment, keys, content) in enumerate(entries):
            db.execute(
                """
                INSERT INTO local_world_book_entries (
                    id, book_id, keys, content, comment, enabled, constant,
                    selective, insertion_order, priority, position,
                    case_sensitive, display_index
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    f"{book_id}_entry_{index + 1}",
                    book_id,
                    json.dumps(keys, ensure_ascii=False),
                    content,
                    comment,
                    1,
                    0,
                    1,
                    index * 10,
                    100 - index * 5,
                    "before_char",
                    0,
                    index,
                ),
            )


def insert_memories(db: sqlite3.Connection) -> None:
    for index, char in enumerate(CHARACTERS):
        memory_path = f"characters/{char['id']}/users/demo_user/recent_digest.md"
        db.execute(
            """
            INSERT INTO local_character_memories (
                id, character_id, target_id, type, category, title, summary,
                content, importance, emotion_impact, source_turn_id, created_at,
                expires_at, memory_path, version, updated_at, conversation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                f"demo_memory_{index + 1}",
                char["id"],
                "demo_user",
                "long",
                "recent_digest",
                f"和{char['name']}的重要约定",
                "一起完成了一件小事，并约好下次继续。",
                f"用户和{char['name']}共同整理了计划，最终选择了温柔、轻量、可持续的版本。",
                88 - index * 3,
                json.dumps({"affection": 4, "trust": 3}, ensure_ascii=False),
                None,
                iso(-index - 2, 20),
                None,
                memory_path,
                1,
                iso(-index, 20),
                SESSIONS[index][0],
            ),
        )


def insert_models_and_extensions(db: sqlite3.Connection, favorite_ids: dict[str, list[str]]) -> None:
    now = iso(-1, 12)
    models = [
        ("demo_model_chat", "演示 · GPT 对话模型", "openai_chat", "OpenAI", "gpt-4.1-mini", "chat", 0),
        ("demo_model_reasoning", "演示 · 深度推理模型", "openai_chat", "Demo", "reasoning-demo", "chat", 1),
        ("demo_model_tts", "演示 · 温柔女声", "openai_chat", "Demo", "tts-demo", "tts", 0),
        ("demo_model_vision", "演示 · 图片理解", "openai_chat", "Demo", "vision-demo", "vision", 0),
    ]
    for model_id, name, protocol, provider, model, purpose, priority in models:
        db.execute(
            """
            INSERT INTO local_ai_models (
                id, name, protocol, provider, api_key, base_url, model, enabled,
                purpose, priority, active, temperature, max_tokens,
                max_context_length, top_p, append_base_url_path, supports_tools,
                supports_reasoning, supports_stream, tts_provider, tts_url,
                tts_model, tts_voice, tts_speed, tts_pitch, tts_volume,
                tts_format, tts_upload_url, tts_headers, tts_body_template,
                tts_resource_id, tts_ref_audio, tts_user, language, stt_provider,
                stt_url, stt_model, stt_headers, dimensions, size, prompt_template,
                created_at, token_limit_daily, token_limit_weekly,
                failover_timeout, input_price, output_price, oauth_account_id
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            """,
            (
                model_id,
                name,
                protocol,
                provider,
                "DEMO_KEY_NOT_USABLE",
                "https://example.invalid/v1",
                model,
                0,
                purpose,
                priority,
                0,
                0.8,
                4096,
                128000,
                0.95,
                1,
                1,
                1,
                1,
                "openai",
                "",
                "",
                "alloy",
                1.0,
                1.0,
                1.0,
                "mp3",
                "",
                "",
                "",
                "",
                "",
                "",
                "zh",
                "",
                "",
                "",
                "",
                1536,
                "1024x1024",
                "",
                now,
                0,
                0,
                30,
                0.0,
                0.0,
                None,
            ),
        )

    db.executemany(
        """
        INSERT INTO local_skills (
            id, name, description, aliases_json, enabled, parameters_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        [
            ("demo_skill_trip", "旅行规划助手", "把模糊的旅行灵感整理成轻量行程。", '["旅行计划","行程"]', 1, '{"city":"string","days":"number"}', now),
            ("demo_skill_character", "角色设定整理", "检查角色卡的一致性并补齐可演出的细节。", '["角色卡","人设"]', 1, '{"tone":"string"}', now),
            ("demo_skill_digest", "温柔对话摘要", "将长对话压缩成保留情绪和约定的短摘要。", '["摘要","回顾"]', 1, '{"max_length":"number"}', now),
        ],
    )
    db.executemany(
        """
        INSERT INTO local_tools (
            id, name, description, enabled, parameters_json,
            implementation_json, builtin, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        [
            ("demo_tool_weather", "demo_weather", "README 演示天气工具，不会发起真实请求。", 0, '{"city":{"type":"string"}}', '{"type":"mock"}', 0, now),
            ("demo_tool_postcard", "create_postcard", "根据旅行片段生成明信片文案。", 1, '{"place":{"type":"string"}}', '{"type":"prompt"}', 0, now),
        ],
    )
    db.execute(
        """
        INSERT INTO local_hooks (
            id, name, event, description, enabled, scope, priority, actions_json,
            conditions_json, permissions_json, timeout_ms, max_retries,
            trigger_mode, condition_logic, character_id, conversation_id,
            user_id, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "demo_hook_night",
            "夜间温柔提醒",
            "message_received",
            "22:30 后提醒用户喝水和休息。",
            1,
            "global",
            80,
            '[{"type":"append_prompt","content":"自然地加入一句简短休息提醒"}]',
            '{"time_after":"22:30"}',
            '["read_context"]',
            3000,
            0,
            "conditional",
            "and",
            None,
            None,
            None,
            now,
            now,
        ),
    )
    db.execute(
        """
        INSERT INTO local_hook_logs (
            id, hook_id, event_id, status, actions_executed, error, duration_ms,
            conversation_id, event_type, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "demo_hook_log_1",
            "demo_hook_night",
            "demo_event_1",
            "success",
            1,
            None,
            18,
            "demo_session_starry",
            "message_received",
            iso(-1, 22, 36),
        ),
    )
    db.execute(
        """
        INSERT INTO local_tasks (
            id, kind, name, description, enabled, trigger, config_json,
            target_session_id, prompt, created_at, last_run, next_run
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "demo_task_weekly",
            "memory_digest",
            "每周记忆整理",
            "把本周的重要约定整理为一页温柔回顾。",
            1,
            "cron",
            '{"cron":"0 21 * * 0"}',
            "demo_session_starry",
            "整理本周共同经历和未完成的小计划。",
            now,
            iso(-7, 21),
            "2026-08-02T21:00:00",
        ),
    )
    db.execute(
        """
        INSERT INTO local_workflows (
            id, name, description, enabled, trigger, config_json, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "demo_workflow_memory",
            "聊天结束后的温柔收尾",
            "生成摘要、提取约定并更新角色记忆。",
            1,
            "after_chat",
            json.dumps(
                {
                    "nodes": [
                        {"id": "summary", "type": "summarize", "label": "生成摘要"},
                        {"id": "memory", "type": "memory", "label": "写入记忆"},
                        {"id": "notify", "type": "toast", "label": "完成提醒"},
                    ],
                    "edges": [
                        {"from": "summary", "to": "memory"},
                        {"from": "memory", "to": "notify"},
                    ],
                },
                ensure_ascii=False,
            ),
            now,
        ),
    )
    db.execute(
        """
        INSERT INTO local_mcp_servers (
            id, name, transport, description, enabled, auto_connect, connected,
            tool_count, url, command, args_json, env_json, builtin,
            created_at, last_connected_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        (
            "demo_mcp",
            "README 演示 MCP",
            "streamable-http",
            "仅用于截图展示，默认关闭，不会连接外部服务。",
            0,
            0,
            0,
            6,
            "https://example.invalid/mcp",
            None,
            None,
            None,
            0,
            now,
            None,
        ),
    )
    db.execute(
        "INSERT INTO local_api_keys VALUES (?, ?, ?, ?, ?)",
        ("demo_api_key", "演示 Key（不可用）", "DEMO_KEY_NOT_USABLE", now, now),
    )
    db.execute(
        "INSERT INTO local_message_favorites VALUES (?, ?, ?, ?, ?)",
        (
            "demo_favorite_1",
            "demo_session_starry",
            "关于温柔和不完美的小约定",
            json.dumps(favorite_ids["demo_session_starry"][6:10]),
            iso(-1, 22),
        ),
    )


def create_demo_database(db_path: Path, statements: list[str], version: int) -> None:
    with closing(sqlite3.connect(db_path)) as db, db:
        db.execute("PRAGMA journal_mode=DELETE")
        db.execute("PRAGMA foreign_keys=ON")
        for statement in statements:
            db.execute(statement)
        db.execute(f"PRAGMA user_version={version}")
        insert_characters(db)
        favorite_ids = insert_sessions_and_messages(db)
        insert_relationships_and_states(db)
        insert_world_books(db)
        insert_memories(db)
        insert_models_and_extensions(db, favorite_ids)
        db.commit()
        result = db.execute("PRAGMA integrity_check").fetchone()[0]
        if result != "ok":
            raise RuntimeError(f"SQLite integrity_check failed: {result}")
        db.execute("VACUUM")


def validate_database(db_path: Path, expected_hash: str, version: int) -> dict[str, int | str]:
    with closing(sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)) as db:
        integrity = db.execute("PRAGMA integrity_check").fetchone()[0]
        user_version = db.execute("PRAGMA user_version").fetchone()[0]
        identity_hash = db.execute(
            "SELECT identity_hash FROM room_master_table WHERE id=42"
        ).fetchone()[0]
        counts = {
            "characters": db.execute("SELECT COUNT(*) FROM local_characters").fetchone()[0],
            "sessions": db.execute("SELECT COUNT(*) FROM local_sessions").fetchone()[0],
            "messages": db.execute("SELECT COUNT(*) FROM local_messages").fetchone()[0],
            "world_books": db.execute("SELECT COUNT(*) FROM local_world_books").fetchone()[0],
            "memories": db.execute("SELECT COUNT(*) FROM local_character_memories").fetchone()[0],
            "relationships": db.execute(
                "SELECT COUNT(*) FROM local_relationship_states"
            ).fetchone()[0],
        }
    if integrity != "ok":
        raise RuntimeError(f"Integrity check failed: {integrity}")
    if user_version != version:
        raise RuntimeError(f"Expected user_version {version}, got {user_version}")
    if identity_hash != expected_hash:
        raise RuntimeError("Room identity hash mismatch")
    return {
        "integrity": integrity,
        "user_version": user_version,
        "identity_hash": identity_hash,
        **counts,
    }


def write_zip(output: Path, db_path: Path, validation: dict[str, int | str]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    readme = f"""NekoBot Android README 截图演示数据

导入入口：
  更多 → 本地数据库 → 从文件导入

建议显示名称：
  README 演示数据

内容：
  {validation['characters']} 个虚构角色
  {validation['sessions']} 个示例会话
  {validation['messages']} 条示例消息
  {validation['world_books']} 本世界书
  {validation['memories']} 条角色记忆

安全说明：
  所有人物、对话与凭据均为虚构演示内容。
  数据库中的 DEMO_KEY_NOT_USABLE 不是有效 API Key。
"""
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        archive.write(db_path, DB_ENTRY_NAME)
        archive.writestr("README.txt", readme)


def validate_zip(output: Path, expected_db: bytes) -> None:
    with zipfile.ZipFile(output) as archive:
        names = archive.namelist()
        db_names = [
            name
            for name in names
            if name.endswith(".db")
            and not name.endswith(".db-wal")
            and not name.endswith(".db-shm")
        ]
        if db_names != [DB_ENTRY_NAME]:
            raise RuntimeError(f"Unexpected database entries: {db_names}")
        if archive.read(DB_ENTRY_NAME) != expected_db:
            raise RuntimeError("ZIP database payload does not match the validated database")


def main() -> None:
    args = parse_args()
    impl_path = args.impl.resolve()
    output = args.output.resolve()
    if not impl_path.exists():
        raise SystemExit(
            f"Generated Room implementation not found: {impl_path}\n"
            "Run .\\gradlew.bat compileDebugKotlin first."
        )

    version, statements, identity_hash = extract_room_schema(impl_path)
    if version != ROOM_VERSION:
        raise SystemExit(
            f"Generator expects Room v{ROOM_VERSION}, but current implementation is v{version}."
        )

    with tempfile.TemporaryDirectory(prefix="nekobot-readme-demo-") as temp_dir:
        db_path = Path(temp_dir) / DB_ENTRY_NAME
        create_demo_database(db_path, statements, version)
        validation = validate_database(db_path, identity_hash, version)
        write_zip(output, db_path, validation)
        validate_zip(output, db_path.read_bytes())

    print(json.dumps({"output": str(output), **validation}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
