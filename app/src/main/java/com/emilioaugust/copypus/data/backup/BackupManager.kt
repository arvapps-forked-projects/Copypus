package com.emilioaugust.copypus.data.backup

import android.content.Context
import com.emilioaugust.copypus.data.entity.ClipboardItem
import com.emilioaugust.copypus.data.repository.ClipboardRepository
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.FilterInputStream
import javax.crypto.BadPaddingException

class BackupManager(
    private val context: Context,
    private val repository: ClipboardRepository
) {

    companion object {
        private const val MAGIC = "COPYPUS_BACKUP_V1"
        private val MAGIC_BYTES = MAGIC.toByteArray(Charsets.UTF_8)

        private const val SALT_SIZE = 16
        private const val IV_SIZE = 12

        private const val KEY_SIZE = 256
        private const val GCM_TAG_LENGTH = 128

        private const val PBKDF2_ITERATIONS = 120_000

        private const val ZIP_MANIFEST = "manifest.json"
        private const val ZIP_ITEMS = "items.json"
        private const val ZIP_IMAGES = "images/"
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    suspend fun exportBackup(outputStream: OutputStream, password: CharArray) {
        val items = repository.getAllItemsForBackup()

        val tempZip = File.createTempFile(
            "copypus_backup_",
            ".zip",
            context.cacheDir
        )

        try {
            createZip(
                zipFile = tempZip,
                items = items
            )

            FileInputStream(tempZip).use { input ->
                encryptStream(
                    inputStream = input,
                    outputStream = outputStream,
                    password = password
                )
            }

            outputStream.flush()

        } finally {
            tempZip.delete()
        }
    }

    suspend fun importBackup(inputStream: InputStream, password: CharArray): ImportResult {

        val decryptedZip = File.createTempFile(
            "copypus_import_",
            ".zip",
            context.cacheDir
        )

        val extractDir = File(
            context.cacheDir,
            "copypus_import_${System.currentTimeMillis()}"
        )

        try {
            FileOutputStream(decryptedZip).use { output ->
                decryptStream(
                    inputStream = inputStream,
                    outputStream = output,
                    password = password
                )
            }

            extractZip(
                zipFile = decryptedZip,
                outputDir = extractDir
            )

            return restoreBackup(extractDir)

        } finally {
            decryptedZip.delete()
            extractDir.deleteRecursively()
        }
    }

    private fun createZip(zipFile: File, items: List<ClipboardItem>) {
        val backupItems = items.map { item ->

            val imageFileName =
                if (
                    item.type == "IMAGE" &&
                    !item.imagePath.isNullOrBlank()
                ) {
                    File(item.imagePath).name
                } else {
                    null
                }

            BackupItem(
                text = item.text,
                imageHash = item.imageHash,
                type = item.type,
                isFavorite = item.isFavorite,
                timestamp = item.timestamp,
                imageFileName = imageFileName
            )
        }

        val manifest = BackupManifest(
            createdAt = System.currentTimeMillis(),
            itemsCount = items.size,
            imagesCount = items.count {
                it.type == "IMAGE"
            }
        )

        ZipOutputStream(
            BufferedOutputStream(
                FileOutputStream(zipFile)
            )
        ).use { zip ->

            writeZipEntry(
                zip = zip,
                name = ZIP_MANIFEST,
                data = json
                    .encodeToString(manifest)
                    .toByteArray(Charsets.UTF_8)
            )

            writeZipEntry(
                zip = zip,
                name = ZIP_ITEMS,
                data = json
                    .encodeToString(backupItems)
                    .toByteArray(Charsets.UTF_8)
            )

            items
                .filter { it.type == "IMAGE" }
                .forEach { item ->
                    val imagePath = item.imagePath
                    val imageHash = item.imageHash

                    if (imagePath.isNullOrBlank() ||
                        imageHash.isNullOrBlank()
                    ) {
                        throw BackupException.MissingImage
                    }

                    val imageFile = File(imagePath)

                    if (!imageFile.isFile) {
                        throw BackupException.MissingImage
                    }

                    val entryName = "$ZIP_IMAGES${imageHash}_${imageFile.name}"

                    zip.putNextEntry(ZipEntry(entryName))

                    FileInputStream(imageFile).use { input ->
                        input.copyTo(zip)
                    }

                    zip.closeEntry()
                }
        }
    }

    private fun writeZipEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun encryptStream(inputStream: InputStream, outputStream: OutputStream,
                              password: CharArray) {
        val secureRandom = SecureRandom()

        val salt = ByteArray(SALT_SIZE).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }

        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key,
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )

        outputStream.write(MAGIC_BYTES)
        outputStream.write(salt)
        outputStream.write(iv)

        val nonClosing = NonClosingOutputStream(outputStream)
        CipherOutputStream(nonClosing, cipher).use { cipherOutput ->
            inputStream.copyTo(cipherOutput)
        }

        outputStream.flush()
    }

    private fun decryptStream(inputStream: InputStream, outputStream: OutputStream,
                              password: CharArray) {
        val magic = ByteArray(MAGIC_BYTES.size)
        inputStream.readFully(magic)

        if (!magic.contentEquals(MAGIC_BYTES)) {
            throw BackupException.InvalidBackup
        }

        val salt = ByteArray(SALT_SIZE)
        val iv = ByteArray(IV_SIZE)

        inputStream.readFully(salt)
        inputStream.readFully(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt),
            GCMParameterSpec(GCM_TAG_LENGTH, iv)
        )

        try {
            CipherInputStream(
                NonClosingInputStream(inputStream),
                cipher
            ).use { decrypted ->
                decrypted.copyTo(outputStream)
            }

            outputStream.flush()
        } catch (e: Exception) {
            if (e.hasCause<AEADBadTagException>() ||
                e.hasCause<BadPaddingException>()
            ) {
                throw BackupException.WrongPassword
            }

            throw e
        }
    }

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        var current: Throwable? = this

        while (current != null) {
            if (current is T) return true
            current = current.cause
        }

        return false
    }

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() {}
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(
            password,
            salt,
            PBKDF2_ITERATIONS,
            KEY_SIZE
        )

        return try {
            val factory =
                SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256"
                )

            val keyBytes =
                factory.generateSecret(spec).encoded

            SecretKeySpec(
                keyBytes,
                "AES"
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun extractZip(zipFile: File, outputDir: File) {
        outputDir.mkdirs()

        val canonicalOutput =
            outputDir.canonicalFile

        ZipInputStream(
            BufferedInputStream(
                FileInputStream(zipFile)
            )
        ).use { zip ->

            var entry = zip.nextEntry

            while (entry != null) {

                val target = File(
                    canonicalOutput,
                    entry.name
                )

                val canonicalTarget =
                    target.canonicalFile

                if (
                    !canonicalTarget.path
                        .startsWith(
                            canonicalOutput.path +
                                    File.separator
                        )
                ) {
                    throw BackupException.InvalidBackup
                }

                if (entry.isDirectory) {
                    canonicalTarget.mkdirs()
                } else {
                    canonicalTarget.parentFile?.mkdirs()

                    FileOutputStream(
                        canonicalTarget
                    ).use { output ->

                        zip.copyTo(output)
                    }
                }

                zip.closeEntry()

                entry = zip.nextEntry
            }
        }
    }

    private suspend fun restoreBackup(directory: File): ImportResult {
        val manifestFile =
            File(
                directory,
                ZIP_MANIFEST
            )

        val itemsFile =
            File(
                directory,
                ZIP_ITEMS
            )

        if (!manifestFile.isFile || !itemsFile.isFile) {
            throw BackupException.InvalidBackup
        }

        val manifest =
            try {
                json.decodeFromString<BackupManifest>(
                    manifestFile.readText()
                )
            } catch (_: Exception) {
                throw BackupException.InvalidBackup
            }

        if (manifest.formatVersion != 1) {
            throw BackupException.UnsupportedVersion
        }

        val backupItems =
            try {
                json.decodeFromString<List<BackupItem>>(
                    itemsFile.readText()
                )
            } catch (_: Exception) {
                throw BackupException.InvalidBackup
            }

        val actualImagesCount = backupItems.count {
            it.type == "IMAGE"
        }

        if (
            manifest.itemsCount != backupItems.size ||
            manifest.imagesCount != actualImagesCount ||
            backupItems.any { it.type != "TEXT" && it.type != "IMAGE" }
        ) {
            throw BackupException.InvalidBackup
        }

        for (item in backupItems) {
            if (item.type == "IMAGE" &&
                (item.imageHash.isNullOrBlank() ||
                        item.imageFileName.isNullOrBlank())
            ) {
                throw BackupException.InvalidBackup
            }
        }

        if (manifest.itemsCount != backupItems.size) {
            throw BackupException.InvalidBackup
        }

        val imagesDir = File(
            context.filesDir,
            "clipboard_images"
        )

        imagesDir.mkdirs()

        var importedItems = 0
        var importedImages = 0



        for (backupItem in backupItems) {

            var imagePath: String? = null

            if (
                backupItem.type == "IMAGE" &&
                !backupItem.imageHash.isNullOrBlank()
            ) {

                val existingImage =
                    repository.getImageByHash(
                        backupItem.imageHash
                    )

                if (existingImage != null) {

                    imagePath =
                        existingImage.imagePath

                } else {

                    val imageEntry =
                        findImageFile(
                            directory = directory,
                            imageHash = backupItem.imageHash
                        )

                    if (imageEntry == null ||
                        !imageEntry.isFile
                    ) {
                        throw BackupException.MissingImage
                    }

                    val extension =
                        imageEntry.extension
                            .lowercase()
                            .ifBlank { "jpg" }

                    val destination = File(
                        imagesDir,
                        "image_${backupItem.imageHash}.$extension"
                    )

                    if (!destination.exists()) {

                        imageEntry.copyTo(
                            destination,
                            overwrite = false
                        )

                        importedImages++
                    }

                    imagePath =
                        destination.absolutePath
                }
            }

            val item = ClipboardItem(
                text = backupItem.text,
                imagePath = imagePath,
                imageHash = backupItem.imageHash,
                type = backupItem.type,
                isFavorite = backupItem.isFavorite,
                timestamp = backupItem.timestamp
            )

            val insertedId =
                repository.insertForBackup(item)

            if (insertedId != -1L) {
                importedItems++
            }
        }



        return ImportResult(
            importedItems = importedItems,
            importedImages = importedImages
        )
    }

    private fun findImageFile(directory: File, imageHash: String): File? {
        val imagesDir =
            File(
                directory,
                ZIP_IMAGES
            )

        if (!imagesDir.isDirectory) {
            return null
        }

        return imagesDir
            .listFiles()
            ?.firstOrNull { file ->
                file.isFile &&
                        file.name.startsWith(
                            "${imageHash}_"
                        )
            }
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0

        while (offset < buffer.size) {

            val count = read(
                buffer,
                offset,
                buffer.size - offset
            )

            if (count == -1) {
                throw BackupException.InvalidBackup
            }

            offset += count
        }
    }

    private class NonClosingOutputStream(private val delegate: OutputStream) : OutputStream() {

        override fun write(
            b: Int
        ) {
            delegate.write(b)
        }

        override fun write(
            b: ByteArray
        ) {
            delegate.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int
        ) {
            delegate.write(
                b,
                off,
                len
            )
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {}
    }
}
