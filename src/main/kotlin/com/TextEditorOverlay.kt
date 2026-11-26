package com.gonobot.gonofiles

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

object TextEditorOverlay {

    val TEXT_EXTENSIONS = setOf(
        "txt", "log", "md", "json", "xml", "html", "htm", "css", "js", "ts",
        "kt", "java", "py", "c", "cpp", "h", "sh", "bat", "ini", "cfg",
        "conf", "properties", "gradle", "yaml", "yml", "toml", "csv", "sql",
        "pro", "gitignore"
    )

    private const val MAX_SAFE_BYTES = 2 * 1024 * 1024L // 2 MB 

    fun isTextFile(file: File): Boolean {
        if (file.isDirectory) return false
        val ext = file.extension.lowercase(Locale.US)
        val name = file.name.lowercase(Locale.US)
        return ext in TEXT_EXTENSIONS || name in setOf("makefile", "dockerfile", "license", "readme")
    }

    fun open(activity: Activity, file: File, onSavedOrClosed: () -> Unit) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        if (rootView.findViewById<View>(R.id.editorRoot) != null) return

        if (file.length() > MAX_SAFE_BYTES) {
            Toast.makeText(
                activity,
                "File larger than 2 MB — opening with external app",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val initialText = try {
            file.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            Toast.makeText(activity, "Cannot read text file", Toast.LENGTH_SHORT).show()
            return
        }

        val editorView = activity.layoutInflater.inflate(R.layout.activity_editor, rootView, false)
        rootView.addView(editorView)

        val btnBack = editorView.findViewById<ImageView>(R.id.btnEditorBack)
        val titleTv = editorView.findViewById<TextView>(R.id.editorTitle)
        val metaTv = editorView.findViewById<TextView>(R.id.editorMeta)
        val btnFind = editorView.findViewById<TextView>(R.id.btnEditorFind)
        val btnWrap = editorView.findViewById<TextView>(R.id.btnEditorWrap)
        val btnSave = editorView.findViewById<TextView>(R.id.btnEditorSave)

        val searchBar = editorView.findViewById<LinearLayout>(R.id.editorSearchBar)
        val searchInput = editorView.findViewById<EditText>(R.id.editorSearchInput)
        val searchCountTv = editorView.findViewById<TextView>(R.id.editorSearchCount)
        val btnFindNext = editorView.findViewById<TextView>(R.id.btnEditorFindNext)
        val btnCloseSearch = editorView.findViewById<TextView>(R.id.btnEditorCloseSearch)

        val lineNumbersTv = editorView.findViewById<TextView>(R.id.editorLineNumbers)
        val contentEt = editorView.findViewById<EditText>(R.id.editorContent)

        val teal = Color.parseColor("#62B1B0")
        val gray = Color.parseColor("#BDBDBD")
        var savedSnapshot = initialText
        var isModified = false
        var wordWrapEnabled = true
        var lastLineCount = -1

        fun updateHeaderAndLines() {
            val currentText = contentEt.text.toString()
            isModified = (currentText != savedSnapshot)
            titleTv.text = if (isModified) "• ${file.name}" else file.name
            btnSave.setTextColor(if (isModified) teal else gray)

            val lines = contentEt.lineCount.coerceAtLeast(1)
            metaTv.text = "UTF-8 • $lines lines • ${currentText.length} chars"
            if (lines != lastLineCount) {
                lastLineCount = lines
                val sb = StringBuilder(lines * 4)
                for (i in 1..lines) {
                    if (i > 1) sb.append('\n')
                    sb.append(i)
                }
                lineNumbersTv.text = sb.toString()
            }
        }

        fun saveFile(): Boolean {
            return try {
                val textToSave = contentEt.text.toString()
                file.writeText(textToSave, Charsets.UTF_8)
                savedSnapshot = textToSave
                updateHeaderAndLines()
                Toast.makeText(activity, "Saved ${file.name}", Toast.LENGTH_SHORT).show()
                true
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to save (read-only storage?)", Toast.LENGTH_SHORT).show()
                false
            }
        }

        fun closeEditorImmediate() {
            rootView.removeView(editorView)
            onSavedOrClosed()
        }

        fun handleBackRequest() {
            if (!isModified) {
                closeEditorImmediate()
                return
            }
            val dlg = AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Unsaved Changes")
            .setMessage("Save changes to ${file.name} before closing?")
            .setNeutralButton("Discard") { _, _ -> closeEditorImmediate() }
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                if (saveFile()) closeEditorImmediate()
            }
            .create()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(gray)
                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.parseColor("#FF6B6B"))
            }
            dlg.show()
        }

        editorView.tag = Runnable { handleBackRequest() }

        contentEt.setText(initialText)
        contentEt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateHeaderAndLines()
                }
        })
        contentEt.post { updateHeaderAndLines() }

        contentEt.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    updateHeaderAndLines()
                }
        })

        btnBack.setOnClickListener { handleBackRequest() }
        btnSave.setOnClickListener { saveFile() }

        btnWrap.setOnClickListener {
            wordWrapEnabled = !wordWrapEnabled
            contentEt.setHorizontallyScrolling(!wordWrapEnabled)
            btnWrap.setTextColor(if (wordWrapEnabled) teal else gray)
            contentEt.post { updateHeaderAndLines() }
        }

        // Search in file
        val matchPositions = mutableListOf<Int>()
        var currentMatchIdx = -1

        fun refreshSearchMatches(jumpToFirst: Boolean) {
            matchPositions.clear()
            currentMatchIdx = -1
            val query = searchInput.text.toString()
            val fullText = contentEt.text.toString()
            if (query.isEmpty()) {
                searchCountTv.text = "0/0"
                return
            }
            var idx = fullText.indexOf(query, 0, ignoreCase = true)
            while (idx >= 0) {
                matchPositions.add(idx)
                idx = fullText.indexOf(query, idx + query.length.coerceAtLeast(1), ignoreCase = true)
            }
            if (matchPositions.isEmpty()) {
                searchCountTv.text = "0/0"
            } else if (jumpToFirst) {
                currentMatchIdx = 0
                val pos = matchPositions[0]
                contentEt.requestFocus()
                contentEt.setSelection(pos, (pos + query.length).coerceAtMost(fullText.length))
                searchCountTv.text = "1/${matchPositions.size}"
            } else {
                searchCountTv.text = "0/${matchPositions.size}"
            }
        }

        btnFind.setOnClickListener {
            if (searchBar.visibility == View.VISIBLE) {
                searchBar.visibility = View.GONE
            } else {
                searchBar.visibility = View.VISIBLE
                searchInput.requestFocus()
            }
        }

        btnCloseSearch.setOnClickListener {
            searchBar.visibility = View.GONE
        }

        searchInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    refreshSearchMatches(jumpToFirst = true)
                }
        })

        btnFindNext.setOnClickListener {
            val query = searchInput.text.toString()
            if (query.isEmpty()) return@setOnClickListener
            if (matchPositions.isEmpty()) refreshSearchMatches(jumpToFirst = false)
            if (matchPositions.isEmpty()) {
                Toast.makeText(activity, "No matches", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            currentMatchIdx = (currentMatchIdx + 1) % matchPositions.size
            val pos = matchPositions[currentMatchIdx]
            val fullText = contentEt.text.toString()
            contentEt.requestFocus()
            contentEt.setSelection(pos, (pos + query.length).coerceAtMost(fullText.length))
            searchCountTv.text = "${currentMatchIdx + 1}/${matchPositions.size}"
        }
    }
}
