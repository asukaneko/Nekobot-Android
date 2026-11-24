package app.lianxu.independent

import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import com.google.gson.JsonObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.zip.ZipException

/** One bounded terminal event per user operation or batch, never per file, frame or database row. */
internal enum class LocalStep(val event: String) {
    ARCHIVE_READ("backup.step"), SNAPSHOT("backup.step"), DOCUMENT_WRITE("backup.step"),
    PREVIEW_VALIDATE("backup.step"), UNSEAL("backup.step"), RECHECK("backup.step"),
    CHECK_SPACE("backup.step"), STAGE_MODELS("backup.step"), RESTORE_ROWS("backup.step"),
    COMMIT("backup.step"), FINISH_MODELS("backup.step"),
    TOOLBOX_ORDER("settings.save"), VIDEO_MODE("settings.save"), AUX_VISION("settings.save"),
    MEMORY_RULES("settings.save"), DIAGNOSTIC_FORMAT("settings.save"), PROFILE("settings.save"),
    AVATAR("settings.save"), ROLE_PROMPTS("settings.save"), MODEL_CONFIG("settings.save"),
    MODEL_PURPOSE("settings.save"), COMPANION_PERMISSION("settings.save"),
    ROUTE_CHECK("attachment.prepare"), IMAGE_BATCH("attachment.prepare"), VIDEO_ORIGINALS("attachment.prepare"),
    VIDEO_FRAMES("attachment.prepare"), REQUEST_CAPACITY("attachment.prepare"),
    SAVED_REPLY("reply.notification");

    inline fun <T> observe(turn: ChatTurn? = null, enabled: Boolean = true, reportSuccess: Boolean = true,
        result: (T) -> LocalResult = { LocalResult.COMPLETED }, block: () -> T): T {
        if (!enabled) return block()
        val start = System.nanoTime()
        val value = try {
            block()
        } catch (error: Throwable) {
            runCatching { Diagnostics.localStep(this, LocalResult.forFailure(error, this), start, turn) }
            throw error
        }
        if (reportSuccess) runCatching { Diagnostics.localStep(this, result(value), start, turn) }
        return value
    }

    companion object {
        val events = entries.map { it.event }.toSet()
        fun project(event: String, data: JsonObject): JsonObject? {
            fun string(key: String) = data[key]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val stage = entries.firstOrNull { it.event == event && it.name == string("stage") } ?: return null
            val outcome = LocalResult.entries.firstOrNull { it.name == string("result") } ?: return null
            if (!outcome.belongsTo(event)) return null
            return JsonObject().apply {
                addProperty("stage", stage.name)
                addProperty("result", outcome.name)
                string("turn")?.takeIf(ChatVoiceDiagnostics::validTurn)?.let { addProperty("turn", it) }
                data["attempt"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toIntOrNull()
                    ?.takeIf { it > 0 }?.let { addProperty("attempt", it) }
                data["request_number"]?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asString?.toLongOrNull()
                    ?.takeIf { it > 0 }?.let { addProperty("request_number", it) }
            }
        }
    }
}

internal enum class LocalResult {
    COMPLETED, SUBMITTED, VISIBLE, VISIBILITY_UNAVAILABLE, NO_SAVED_MESSAGE, APP_DISABLED, CHANNEL_DISABLED,
    CANCELLED, IO_FAILED, DATABASE_FAILED, STORAGE_FULL, ACCESS_DENIED, OUT_OF_MEMORY,
    SPACE_CHECK_REJECTED, PASSWORD_REQUIRED, WRONG_PASSWORD, VISION_MODEL_MISSING, INVALID_ARCHIVE, INVALID_INPUT, STATE_CHANGED,
    CAPACITY_EXCEEDED, WRITE_REJECTED, FAILED;

    fun belongsTo(event: String): Boolean = when (this) {
        SUBMITTED, VISIBLE, VISIBILITY_UNAVAILABLE, NO_SAVED_MESSAGE, APP_DISABLED, CHANNEL_DISABLED -> event == "reply.notification"
        WRONG_PASSWORD, PASSWORD_REQUIRED, SPACE_CHECK_REJECTED, INVALID_ARCHIVE -> event == "backup.step"
        WRITE_REJECTED -> event == "settings.save"
        CAPACITY_EXCEEDED, VISION_MODEL_MISSING -> event == "attachment.prepare"
        else -> true
    }

    companion object {
        fun forFailure(error: Throwable, step: LocalStep? = null): LocalResult = when (error) {
            is CancellationException -> CANCELLED
            is BackupKeySeal.NeedsPassword -> PASSWORD_REQUIRED
            is BackupKeySeal.WrongPassword -> WRONG_PASSWORD
            is OutOfMemoryError -> OUT_OF_MEMORY
            is SQLiteFullException -> STORAGE_FULL
            is SQLiteException -> DATABASE_FAILED
            is SecurityException -> ACCESS_DENIED
            is ZipException -> if (step?.event == "backup.step") INVALID_ARCHIVE else IO_FAILED
            is IOException -> IO_FAILED
            is CompanionModelFailure -> when (error.failure) {
                ChatFailure.CONTEXT_CAPACITY, ChatFailure.IMAGE_LIMIT -> CAPACITY_EXCEEDED
                ChatFailure.MISSING_VISION_MODEL -> VISION_MODEL_MISSING
                ChatFailure.SOURCE_CHANGED -> STATE_CHANGED
                else -> FAILED
            }
            is IllegalArgumentException -> when {
                error.cause is OutOfMemoryError -> OUT_OF_MEMORY
                step == LocalStep.ARCHIVE_READ -> INVALID_ARCHIVE
                step == LocalStep.CHECK_SPACE -> SPACE_CHECK_REJECTED
                else -> INVALID_INPUT
            }
            else -> FAILED
        }

        fun insert(row: Long) = if (row == -1L) WRITE_REJECTED else COMPLETED
    }
}
