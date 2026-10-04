package com.emilioaugust.copypus.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

data class SavedImage(
    val file: File,
    val hash: String
)

object ImageClipboardSaver {
    suspend fun save(context: Context, uri: Uri): SavedImage {
        val result = withContext(
            Dispatchers.IO
        ) {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open image URI")

            val bytes = inputStream.use {
                it.readBytes()
            }

            val imageHash = MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { byte ->
                    "%02x".format(byte)
                }

            val mimeType = context.contentResolver.getType(uri)

            val extension = when (mimeType) {
                "image/jpeg" -> "jpg"
                "image/webp" -> "webp"
                "image/gif" -> "gif"
                "image/png" -> "png"
                else -> "img"
            }

            val imagesDir = File(
                context.filesDir,
                "clipboard_images"
            )

            if (!imagesDir.exists()) {
                imagesDir.mkdirs()
            }

            val imageFile = File(
                imagesDir,
                "image_${UUID.randomUUID()}.$extension"
            )

            imageFile.outputStream().use { output ->
                output.write(bytes)
            }

            SavedImage(
                file = imageFile,
                hash = imageHash
            )
        }

        return result
    }
}