package com.emilioaugust.copypus.data.repository

import com.emilioaugust.copypus.data.dao.ClipboardDao
import com.emilioaugust.copypus.data.entity.ClipboardItem

class ClipboardRepository(private val dao: ClipboardDao) {
    fun getAllItems() = dao.getAllItems()

    fun getAllImages() = dao.getAllImages()
    fun getAllFavorites() = dao.getAllFavorites()

    suspend fun getImageByHash(imageHash: String): ClipboardItem? = dao.getImageByHash(imageHash)
    suspend fun saveItem(text: String) = dao.insert(ClipboardItem(text = text))

    suspend fun insertItem(item: ClipboardItem) = dao.insert(item)

    suspend fun saveImage(imageFileName: String, imagePath: String, imageHash: String) {
        dao.insert(
            ClipboardItem(
                text = imageFileName,
                imagePath = imagePath,
                imageHash = imageHash,
                type = "IMAGE"
            )
        )
    }
    suspend fun deleteItem(item: ClipboardItem) = dao.delete(item)

    suspend fun deleteOldItems(time: Long) = dao.deleteOlderThan(time)
    suspend fun clearAll() = dao.clearAll()

    suspend fun clearAllImages() = dao.clearAllImages()
    suspend fun updateFavorite(id: Long, isFavorite: Boolean) {
        dao.updateFavorite(id, isFavorite)
    }
    suspend fun getLatestItem(): ClipboardItem? {
        return dao.getLatestItem()
    }

    suspend fun updateItem(item: ClipboardItem) {
        dao.updateItem(item)
    }

    suspend fun getAllItemsForBackup(): List<ClipboardItem> {
        return dao.getAllItemsForBackup()
    }

    suspend fun insertForBackup(item: ClipboardItem): Long {
        return dao.insertForBackup(item)
    }

}