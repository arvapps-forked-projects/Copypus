package com.emilioaugust.copypus

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.emilioaugust.copypus.data.database.AppDatabase
import com.emilioaugust.copypus.data.datastore.SettingsDataStore
import com.emilioaugust.copypus.data.entity.ClipboardItem
import com.emilioaugust.copypus.data.repository.ClipboardRepository
import com.emilioaugust.copypus.utils.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class SaveClipboardActivity : ComponentActivity() {

    private lateinit var repository: ClipboardRepository

    companion object {
        const val EXTRA_FROM_ACCESSIBILITY =
            "from_accessibility"

        private const val TAG =
            "SaveClipboardActivity"

        private const val CAPTURE_DELAY_MS = 150L
    }

    override fun attachBaseContext(newBase: Context) {
        val language = runBlocking {
            SettingsDataStore(newBase)
                .language
                .first()
        }

        super.attachBaseContext(
            LocaleHelper.setLocale(
                newBase,
                language.code
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "========== onCreate ==========")
        Log.d(TAG, "intent=$intent")

        val fromAccessibility =
            intent.getBooleanExtra(
                EXTRA_FROM_ACCESSIBILITY,
                false
            )

        Log.d(
            TAG,
            "fromAccessibility=$fromAccessibility"
        )

        repository = ClipboardRepository(
            AppDatabase
                .getInstance(applicationContext)
                .clipboardDao()
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        if (!hasFocus) return
        if (isFinishing) return

        lifecycleScope.launch {
            delay(CAPTURE_DELAY_MS)
            saveClipboard()
            finish()
        }
    }

    private suspend fun saveClipboard() {

        Log.d(
            TAG,
            "========== SAVE CLIPBOARD =========="
        )

        val fromAccessibility =
            intent.getBooleanExtra(
                EXTRA_FROM_ACCESSIBILITY,
                false
            )

        Log.d(
            TAG,
            "fromAccessibility=$fromAccessibility"
        )

        val clipboardManager =
            getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager

        try {

            val clip =
                clipboardManager.primaryClip

            Log.d(
                TAG,
                "primaryClip=$clip"
            )

            if (clip == null) {
                Log.d(
                    TAG,
                    "Clipboard is null"
                )

                return
            }

            Log.d(
                TAG,
                "clip.itemCount=${clip.itemCount}"
            )

            if (clip.itemCount <= 0) {
                Log.d(
                    TAG,
                    "Clipboard has no items"
                )

                return
            }

            val item =
                clip.getItemAt(0)

            Log.d(
                TAG,
                "item=$item"
            )

            val text =
                item
                    .coerceToText(this@SaveClipboardActivity)
                    ?.toString()
                    ?.takeIf {
                        it.isNotBlank()
                    }

            Log.d(
                TAG,
                "text=[${text?.take(200)}]"
            )

            if (text == null) {
                Log.d(
                    TAG,
                    "Clipboard text is empty"
                )

                return
            }

            repository.insertItem(
                ClipboardItem(
                    text = text,
                    timestamp =
                        System.currentTimeMillis()
                )
            )

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SaveClipboardActivity,
                    getString(
                        R.string.clipboard_saved_text
                    ),
                    Toast.LENGTH_SHORT
                ).show()

                finishAndRemoveTask()
            }

            Log.d(
                TAG,
                "Clipboard saved successfully"
            )


            Log.d(
                TAG,
                "===================================="
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Clipboard access denied",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to read clipboard",
                e
            )
        }
    }
}


