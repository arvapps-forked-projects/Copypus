package com.emilioaugust.copypus.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupItem(
    val text: String? = null,
    val imageHash: String? = null,
    val type: String,
    val isFavorite: Boolean,
    val timestamp: Long,
    val imageFileName: String? = null
)

@Serializable
data class BackupManifest(
    val formatVersion: Int = 1,
    val app: String = "Copypus",
    val createdAt: Long = System.currentTimeMillis(),
    val itemsCount: Int,
    val imagesCount: Int
)

data class ImportResult(
    val importedItems: Int,
    val importedImages: Int
)

sealed class BackupException(message: String) : Exception(message) {
    data object InvalidBackup :
        BackupException("Invalid or corrupted backup file")
    data object WrongPassword :
        BackupException("Wrong password or corrupted backup")
    data object UnsupportedVersion :
        BackupException("Unsupported backup version")
    data object MissingImage :
        BackupException("An image is missing from the backup")
}