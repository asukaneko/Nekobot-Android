package com.nekobot.app.data.local

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import com.nekobot.app.data.local.db.NekobotDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Publishes a new immutable image before atomically changing only avatar references. */
internal class CompanionAvatarStore(private val db: NekobotDatabase, private val filesDir: File) {
    suspend fun save(sessionId: String, characterId: String, png: ByteArray): String {
        currentCoroutineContext().ensureActive()
        return withContext(Dispatchers.IO + NonCancellable) {
            require(png.size in 1..4 * 1024 * 1024) { "Invalid avatar size" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
            require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..512 && bounds.outHeight == bounds.outWidth) {
                "Invalid cropped avatar"
            }
            val directory = File(filesDir, "portraits").apply { mkdirs() }
            val image = File(directory, "avatar_${UUID.randomUUID()}.png")
            try {
                FileOutputStream(image).use { output -> output.write(png); output.fd.sync() }
                val path = Uri.fromFile(image).toString()
                db.withTransaction {
                    val session = requireNotNull(db.sessionDao().getById(sessionId))
                    require(session.companionMode && session.characterId == characterId) { "Character ownership changed" }
                    val owner = db.companionMemoryDao().owner(characterId)
                    require(owner == null || owner.sessionId == sessionId) { "Character ownership changed" }
                    val windows = db.sessionDao().listForCompanionEligibility(characterId).filter { it.companionMode }
                    require(windows.singleOrNull()?.id == sessionId) { "Ambiguous character ownership" }
                    check(db.characterDao().updateAvatar(characterId, path, Instant.now().toString()) == 1)
                    db.sessionDao().updateCharacterAvatar(characterId, path)
                }
                path
            } catch (failure: Throwable) {
                // This unique file is ours; existing avatar/portrait files are never removed.
                image.delete()
                throw failure
            }
        }
    }
}
