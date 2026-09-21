package com.emilioaugust.copypus

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.emilioaugust.copypus.data.database.AppDatabase
import com.emilioaugust.copypus.data.entity.ClipboardItem
import com.emilioaugust.copypus.data.repository.ClipboardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SaveClipboardActivity : ComponentActivity() {

    private lateinit var repository: ClipboardRepository
    private var hasStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        repository = ClipboardRepository(
            AppDatabase.getInstance(applicationContext).clipboardDao()
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (!hasFocus || hasStarted) return

        hasStarted = true

        lifecycleScope.launch {
            delay(150)
            saveClipboard()
            delay(100)
            finishAndRemoveTask()
        }
    }

    private suspend fun saveClipboard() {
        val clipboardManager =
            getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        try {
            val clip = clipboardManager.primaryClip

            if (clip == null || clip.itemCount == 0) {
                return
            }

            val text = clip.getItemAt(0)
                .coerceToText(this@SaveClipboardActivity)
                ?.toString()
                ?.takeIf { it.isNotBlank() }

            if (text == null) return

            repository.insertItem(
                ClipboardItem(
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
            )

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SaveClipboardActivity,
                    getString(R.string.clipboard_saved_text),
                    Toast.LENGTH_SHORT
                ).show()
            }

        } catch (e: Exception) {
            Log.e(
                "SaveClipboardActivity",
                "Failed to read clipboard",
                e
            )
        }
    }
}