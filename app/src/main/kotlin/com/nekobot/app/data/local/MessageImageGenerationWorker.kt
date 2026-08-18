package com.nekobot.app.data.local

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.ai.ImageGenerationReference
import com.nekobot.app.data.repository.Resource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 消息生图后台任务。任务状态会写入 Room，离开聊天页后仍会继续执行。 */
class MessageImageGenerationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val local = ServiceContainer.localRepository
        val task = local.getMessageImage(taskId) ?: return Result.success()
        if (task.status == LocalRepository.MESSAGE_IMAGE_STATUS_COMPLETED) return Result.success()

        local.markMessageImageRunning(taskId)
        return try {
            val referenceImage = readReferenceImage(task.referenceImagePath, task.referenceImageMimeType)
            when (
                val result = ServiceContainer.unified.generateImages(
                    prompt = task.prompt,
                    size = DEFAULT_IMAGE_SIZE,
                    n = 1,
                    referenceImage = referenceImage
                )
            ) {
                is Resource.Success -> {
                    val image = result.data.firstOrNull()
                        ?: throw IllegalStateException("图片生成未返回可保存的结果")
                    local.completeMessageImage(taskId, image)
                    Result.success()
                }

                is Resource.Error -> {
                    local.failMessageImage(taskId, result.message ?: "图片生成失败")
                    Result.failure()
                }

                is Resource.Loading -> Result.retry()
            }
        } catch (error: Exception) {
            local.failMessageImage(taskId, error.message ?: "图片生成失败")
            Result.failure()
        }
    }

    /** 参考立绘可能来自 file/content URI 或远程 URL；读取失败时退化为纯文本生图。 */
    private suspend fun readReferenceImage(path: String?, mimeType: String?): ImageGenerationReference? =
        withContext(Dispatchers.IO) {
            if (path.isNullOrBlank()) return@withContext null
            val uri = runCatching { android.net.Uri.parse(path) }.getOrNull() ?: return@withContext null
            val bytes = runCatching {
                when (uri.scheme?.lowercase()) {
                    "http", "https" -> {
                        OkHttpClient().newCall(Request.Builder().url(path).get().build())
                            .execute().use { response ->
                                if (!response.isSuccessful) return@use null
                                response.body?.bytes()
                            }
                    }
                    "content" -> applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.readBytes()
                    else -> File(path).takeIf(File::isFile)?.readBytes()
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return@withContext null
            ImageGenerationReference(
                bytes = bytes,
                mimeType = mimeType?.takeIf { it.isNotBlank() } ?: guessMimeType(path)
            )
        }

    private fun guessMimeType(path: String): String = when (
        path.substringBefore('?').substringAfterLast('.', "").lowercase()
    ) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private companion object {
        const val KEY_TASK_ID = "message_image_task_id"
        const val DEFAULT_IMAGE_SIZE = "1024x1024"
    }
}

/** 统一调度入口，避免界面重组或重复点击导致同一任务被多次执行。 */
object MessageImageGenerationScheduler {
    private const val KEY_TASK_ID = "message_image_task_id"
    private const val WORK_PREFIX = "message_image_generation_"

    fun enqueue(context: Context, taskId: String) {
        val request = OneTimeWorkRequestBuilder<MessageImageGenerationWorker>()
            .setInputData(workDataOf(KEY_TASK_ID to taskId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "$WORK_PREFIX$taskId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}
