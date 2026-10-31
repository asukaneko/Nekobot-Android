package com.nekobot.app.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Device-only commit witness for recovering file changes after a process interruption. */
@Entity(tableName = "portable_import_commits")
data class PortableImportCommitEntity(@PrimaryKey val id: String, val createdAt: Long)
