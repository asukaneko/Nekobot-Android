package com.nekobot.app.ui.screens.stickers

import androidx.lifecycle.viewModelScope
import com.nekobot.app.R
import com.nekobot.app.ServiceContainer
import com.nekobot.app.data.local.LocalStickerStorage
import com.nekobot.app.data.local.StickerImport
import com.nekobot.app.data.local.StickerMarkers
import com.nekobot.app.data.local.db.LocalStickerEntity
import com.nekobot.app.data.repository.Resource
import com.nekobot.app.ui.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 待导入的一张表情草稿。
 *
 * 来源二选一：本地图片用 [uri]（导入确认时才读取字节，避免一次把整批图片读进内存），
 * ZIP 内条目直接带 [bytes]。
 */
data class StickerDraft(
    val name: String,
    val uri: String? = null,
    val bytes: ByteArray? = null,
    val mimeType: String? = null,
    val sourceName: String? = null,
    val source: String = "import"
)

/** 表情包管理页 ViewModel：观察已导入表情，负责导入/重命名/删除。 */
class StickerStoreViewModel : BaseViewModel() {

    private val _stickers = MutableStateFlow<List<LocalStickerEntity>>(emptyList())
    val stickers: StateFlow<List<LocalStickerEntity>> = _stickers.asStateFlow()

    private val _drafts = MutableStateFlow<List<StickerDraft>>(emptyList())
    val drafts: StateFlow<List<StickerDraft>> = _drafts.asStateFlow()

    /** 导入失败/跳过的草稿数量（用于结果提示）。 */
    private var skippedDrafts = 0

    init {
        viewModelScope.launch {
            unified.observeStickers().collect { _stickers.value = it }
        }
    }

    /** 合并新选的草稿；同一来源重复选择时不重复添加。 */
    fun addDrafts(items: List<StickerDraft>) {
        if (items.isEmpty()) return
        val existingKeys = _drafts.value.mapNotNull { it.uri ?: it.sourceName }.toSet()
        val merged = items.filterNot { draft ->
            val key = draft.uri ?: draft.sourceName
            key != null && key in existingKeys
        }
        if (merged.isEmpty()) {
            showToast(string(R.string.sticker_import_duplicate))
            return
        }
        _drafts.value = _drafts.value + merged
    }

    fun updateDraftName(index: Int, name: String) {
        _drafts.value = _drafts.value.mapIndexed { i, draft ->
            if (i == index) draft.copy(name = name.take(StickerMarkers.MAX_NAME_LENGTH)) else draft
        }
    }

    fun removeDraft(index: Int) {
        _drafts.value = _drafts.value.filterIndexed { i, _ -> i != index }
    }

    fun clearDrafts() {
        _drafts.value = emptyList()
    }

    /** 确认导入：读取本地 URI 字节并批量写入表情库。 */
    fun confirmImport() {
        val drafts = _drafts.value
        if (drafts.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            try {
                val context = ServiceContainer.appContext
                val resolver = context?.contentResolver
                val imports = mutableListOf<StickerImport>()
                var skipped = 0
                withContext(Dispatchers.IO) {
                    drafts.forEach { draft ->
                        val name = StickerMarkers.sanitizeName(draft.name)
                        if (name.isBlank()) {
                            skipped++
                            return@forEach
                        }
                        val bytes = draft.bytes ?: draft.uri?.let { raw ->
                            runCatching {
                                resolver?.openInputStream(android.net.Uri.parse(raw))?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        if (bytes == null || bytes.isEmpty()) {
                            skipped++
                            return@forEach
                        }
                        imports += StickerImport(
                            name = name,
                            bytes = bytes,
                            mimeType = draft.mimeType,
                            sourceName = draft.sourceName,
                            source = draft.source
                        )
                    }
                }
                if (imports.isEmpty()) {
                    showError(string(R.string.sticker_import_failed))
                    return@launch
                }
                when (val result = unified.importStickers(imports)) {
                    is Resource.Success -> {
                        skippedDrafts = skipped
                        _drafts.value = emptyList()
                        val count = result.data ?: 0
                        showToast(
                            if (skipped > 0) {
                                string(R.string.sticker_import_result_with_skipped, count, skipped)
                            } else {
                                string(R.string.sticker_import_result, count)
                            }
                        )
                    }
                    is Resource.Error -> showError(result.message)
                    is Resource.Loading -> Unit
                }
            } catch (e: Exception) {
                showError(e.message ?: string(R.string.sticker_import_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    fun rename(id: String, newName: String) {
        val sanitized = StickerMarkers.sanitizeName(newName)
        if (sanitized.isBlank()) {
            showError(string(R.string.sticker_name_required))
            return
        }
        viewModelScope.launch {
            when (val result = unified.renameSticker(id, sanitized)) {
                is Resource.Success -> showToast(string(R.string.sticker_rename_success))
                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (val result = unified.deleteSticker(id)) {
                is Resource.Success -> showToast(string(R.string.sticker_delete_success))
                is Resource.Error -> showError(result.message)
                is Resource.Loading -> Unit
            }
        }
    }

    /** 批量删除：逐个删除并统计失败数量，便于一次性反馈结果。 */
    fun deleteMany(ids: Collection<String>) {
        val targets = ids.toList()
        if (targets.isEmpty()) return
        viewModelScope.launch {
            setLoading(true)
            var failed = 0
            try {
                targets.forEach { id ->
                    val result = runCatching { unified.deleteSticker(id) }.getOrNull()
                    if (result !is Resource.Success) failed++
                }
            } finally {
                setLoading(false)
            }
            val deleted = targets.size - failed
            if (failed == 0) {
                showToast(string(R.string.sticker_batch_delete_success, deleted))
            } else {
                showToast(string(R.string.sticker_batch_delete_partial, deleted, failed))
            }
        }
    }
}
