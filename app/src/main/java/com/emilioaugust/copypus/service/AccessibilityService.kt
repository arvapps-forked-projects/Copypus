package com.emilioaugust.copypus.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.emilioaugust.copypus.data.repository.ClipboardRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.emilioaugust.copypus.R
import com.emilioaugust.copypus.SaveClipboardActivity
import com.emilioaugust.copypus.data.database.AppDatabase
import com.emilioaugust.copypus.data.entity.ClipboardItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object ServiceLocator {
    @Volatile private var initialized = false
    lateinit var repository: ClipboardRepository
        private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val db = AppDatabase.getInstance(context.applicationContext)
            repository = ClipboardRepository(db.clipboardDao())
            initialized = true
        }
    }
}

class ClipboardAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository: ClipboardRepository
        get() {
            ServiceLocator.init(applicationContext)
            return ServiceLocator.repository
        }

    @Volatile private var lastSelectedText: String? = null
    @Volatile private var lastSelectedPackage: String? = null

    @Volatile private var lastSelectedAt: Long = 0L

    private val SELECTION_TTL_MS = 60_000L // 60 sec

    private val handler = Handler(Looper.getMainLooper())
    private var pendingSave: Runnable? = null

    companion object {
        private const val TAG = "ClipAlly"
        private const val SAVE_DELAY_MS = 700L

        private val COPY_LABELS = setOf(
            "copy", "copy to clipboard", "copy link", "copy url",
            "copy image", "copy photo", "copy picture",

            "копировать", "скопировать", "копировать ссылку",
            "копировать изображение", "копировать картинку",

            "kopieren", "link kopieren", "bild kopieren",

            "copiar", "copiar enlace", "copiar imagen",

            "copier", "copier le lien", "copier l'image",

            "copia", "copia link", "copia immagine",

            "kopiuj", "kopiuj link",

            "复制", "复制链接", "复制图片",

            "コピー", "リンクをコピー", "画像をコピー",

            "복사", "링크 복사", "이미지 복사",

            "kopyala", "bağlantıyı kopyala"
        )

        private val COPY_VIEW_IDS = setOf(
            "copy", "action_copy", "menu_copy",
            "text_copy", "copy_text", "copy_link",
            "action_copy_link", "copy_image", "action_copy_image"
        )
    }

    private lateinit var copyLabel: String
    private lateinit var cutLabel: String


    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")

        copyLabel = getString(android.R.string.copy).lowercase()
        cutLabel = getString(android.R.string.cut).lowercase()

        ClipboardForegroundService.start(this)
        Log.d(TAG, "Foreground service start requested")

    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString()

        if (pkg == packageName) {
            Log.v(TAG, "Skip own package event")
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ->
                handleTextSelection(event)

            AccessibilityEvent.TYPE_VIEW_CLICKED ->
                handleClick(event)
        }
    }

    private fun handleTextSelection(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()

        val eventText = event.text
            ?.joinToString("")
            ?: ""

        val from = event.fromIndex
        val to = event.toIndex

        val source = event.source

        var selected: String? = null

        if (source != null) {
            val sourceText = source.text?.toString()
            val start = source.textSelectionStart
            val end = source.textSelectionEnd

            if (
                !sourceText.isNullOrEmpty() &&
                start >= 0 &&
                end > start &&
                end <= sourceText.length
            ) {
                selected = sourceText.substring(start, end)

            }
        }


        if (
            selected.isNullOrBlank() &&
            from >= 0 &&
            to > from &&
            eventText.isNotEmpty() &&
            to <= eventText.length
        ) {
            selected = eventText.substring(from, to)

        }

        if (selected.isNullOrBlank() && source != null) {
            val fromNode = extractSelectedText(source)

            if (!fromNode.isNullOrBlank()) {
                selected = fromNode
            }
        }

        if (selected.isNullOrBlank()) {
            return
        }

        lastSelectedText = selected
        lastSelectedPackage = pkg
        lastSelectedAt = System.currentTimeMillis()

        cancelPendingSave()

    }
    private fun extractSelectedText(node: AccessibilityNodeInfo): String? {
        val full = node.text?.toString()
        val selStart = node.textSelectionStart
        val selEnd = node.textSelectionEnd

        if (full != null && selStart >= 0 && selEnd > selStart && selEnd <= full.length) {
            return full.substring(selStart, selEnd)
        }

        // Fallback — ищем в детях
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = child.text?.toString() ?: continue
            val cs = child.textSelectionStart
            val ce = child.textSelectionEnd
            if (cs >= 0 && ce > cs && ce <= t.length) {
                return t.substring(cs, ce)
            }
        }
        return null
    }

    private fun handleClick(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()

        val eventText = event.text
            ?.joinToString(" ")
            ?.trim()
            ?: ""

        val description = event.contentDescription
            ?.toString()
            ?.trim()
            ?: ""

        val source = event.source

        val sourceText =
            source?.text?.toString()?.trim() ?: ""

        val sourceDescription =
            source?.contentDescription?.toString()?.trim() ?: ""

        val sourceId =
            source?.viewIdResourceName
                ?.substringAfterLast('/')
                ?.lowercase()

        val isCopy = isCopyLabel(eventText) ||
                isCopyLabel(description) ||
                isCopyLabel(sourceText) ||
                isCopyLabel(sourceDescription) ||
                (sourceId != null && COPY_VIEW_IDS.contains(sourceId))

        if (!isCopy) {
            return
        }

        val selectionText = lastSelectedText
        val selectionPackage = lastSelectedPackage

        val selectionAge =
            if (lastSelectedAt > 0L) {
                System.currentTimeMillis() - lastSelectedAt
            } else {
                Long.MAX_VALUE
            }

        val hasValidSelection =
            !selectionText.isNullOrBlank() &&
                    selectionPackage == pkg &&
                    selectionAge <= SELECTION_TTL_MS

        if (hasValidSelection) {
            schedulePendingSave()
        } else {
            launchClipboardFallbackActivity()

            lastSelectedText = null
            lastSelectedPackage = null
            lastSelectedAt = 0L

            cancelPendingSave()
        }
    }

    private fun launchClipboardFallbackActivity() {
        val options = ActivityOptions.makeCustomAnimation(
            this,
            0,
            0
        ).toBundle()

        scope.launch {
            delay(150)

            withContext(Dispatchers.Main) {
                val intent = Intent(
                    this@ClipboardAccessibilityService,
                    SaveClipboardActivity::class.java
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                    putExtra(
                        SaveClipboardActivity.EXTRA_FROM_ACCESSIBILITY,
                        true
                    )
                }

                try {
                    startActivity(intent, options)

                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "FALLBACK: failed to start Activity",
                        e
                    )
                }
            }
        }
    }

    private fun findSelectedNode(node: AccessibilityNodeInfo?,
                                 depth: Int = 0): AccessibilityNodeInfo? {

        if (node == null) return null
        if (depth > 15) return null

        try {

            val text = node.text
            val start = node.textSelectionStart
            val end = node.textSelectionEnd

            if (
                !text.isNullOrBlank() &&
                start >= 0 &&
                end > start &&
                end <= text.length
            ) {

                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {

                val child = node.getChild(i) ?: continue

                try {

                    val result = findSelectedNode(
                        node = child,
                        depth = depth + 1
                    )

                    if (result != null) {
                        return result
                    }

                } finally {
                    child.recycle()
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "findSelectedNode error",
                e
            )
        }

        return null
    }

    private fun searchSelection(node: AccessibilityNodeInfo?, depth: Int): String? {
        if (node == null || depth > 8) return null

        val text = node.text?.toString()
        val s = node.textSelectionStart
        val e = node.textSelectionEnd
        if (text != null && s >= 0 && e > s && e <= text.length) {
            return text.substring(s, e)
        }

        for (i in 0 until node.childCount) {
            searchSelection(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun schedulePendingSave() {
        cancelPendingSave()
        val r = Runnable {
            pendingSave = null
            persistLastSelection()
        }
        pendingSave = r
        handler.postDelayed(r, SAVE_DELAY_MS)
    }

    private fun cancelPendingSave() {
        pendingSave?.let { handler.removeCallbacks(it) }
        pendingSave = null
    }

    private fun persistLastSelection() {
        val text = lastSelectedText ?: run {
            return
        }

        if (text.isBlank()) return

        val age = System.currentTimeMillis() - lastSelectedAt

        if (age > SELECTION_TTL_MS) {
            return
        }

        scope.launch {
            try {
                repository.insertItem(
                    ClipboardItem(
                        text = text,
                        timestamp = System.currentTimeMillis()
                    )
                )

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ClipboardAccessibilityService,
                        getString(R.string.clipboard_saved_text),
                        Toast.LENGTH_SHORT
                    ).show()
                }

                Log.d(
                    TAG,
                    "Saved (${text.length}): ${text.take(40)}…"
                )

            } catch (e: Exception) {
                Log.e(TAG, "save failed", e)
            }
        }
    }

    private fun isCopyLabel(text: String): Boolean {
        if (text.isBlank()) return false
        val normalized = text.trim().lowercase()

        if (normalized == copyLabel || normalized == cutLabel) return true

        return COPY_LABELS.any { label ->
            normalized == label.lowercase()
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        cancelPendingSave()
        scope.cancel()
        super.onDestroy()
    }
}