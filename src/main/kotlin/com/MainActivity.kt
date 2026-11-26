package com.gonobot.gonofiles

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.StatFs
import android.provider.Settings
import android.content.Intent
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.graphics.Color
import android.text.TextUtils
import android.view.Window
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.EditText
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.Toast
import android.widget.PopupMenu
import android.Manifest
import android.content.pm.PackageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.webkit.MimeTypeMap
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private lateinit var leftList: RecyclerView
    private lateinit var rightList: RecyclerView
    private lateinit var pathText: TextView
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: LinearLayout
    private lateinit var leftHeader: TextView
    private lateinit var rightHeader: TextView
    private lateinit var currentFolderInfo: TextView
    private lateinit var btnBack: android.widget.ImageView
    private lateinit var btnForward: android.widget.ImageView
    private lateinit var btnNew: android.widget.ImageView
    private lateinit var btnMirror: android.widget.ImageView
    private lateinit var btnParent: android.widget.ImageView
    private lateinit var btnMenu: android.widget.ImageView
    private lateinit var normalToolbar: LinearLayout
    private lateinit var selectionToolbar: LinearLayout
    private lateinit var btnSelectAll: android.widget.ImageView
    private lateinit var btnClearSelection: android.widget.ImageView
    private lateinit var btnInvertSelection: android.widget.ImageView

    private val internalRoot = Environment.getExternalStorageDirectory()
    private val systemRoot = File("/")
    private var leftFolder = internalRoot
    private var rightFolder = internalRoot
    private val leftHistory = mutableListOf(internalRoot)
    private val rightHistory = mutableListOf(internalRoot)
    private var leftHistoryIndex = 0
    private var rightHistoryIndex = 0
    private var activePanel = 0
    private var permissionSettingsOpened = false

    private val leftSelected = linkedSetOf<String>()
    private val rightSelected = linkedSetOf<String>()
    private var leftSelectionMode = false
    private var rightSelectionMode = false
    private var leftRangeAnchor: String? = null
    private var rightRangeAnchor: String? = null

    enum class SortBy { NAME, TYPE, DATE, SIZE }
    private var leftSortBy = SortBy.NAME
    private var rightSortBy = SortBy.NAME
    private var leftSortReverse = false
    private var rightSortReverse = false

    private var showHiddenFiles = true
    private var showTrashFiles = false
    private var showFileSizes = true
    private var confirmBeforeQuit = true

    // Auto-refresh
    private var leftObserver: FileObserver? = null
    private var rightObserver: FileObserver? = null

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    data class FileItem(
        val file: File,
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val isParentNav: Boolean,
        val ext: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val subtitleStr: String
    )

    private val thumbExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var leftLoadToken = 0
    private var rightLoadToken = 0
    private var cachedDiskText = "Disk: ..."
    private var lastDiskCheckMs = 0L

    private val recentHighlightedPaths = mutableSetOf<String>()
    
    private val folderScrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    private var bottomSwipeStartX = 0f
    private var bottomSwipeStartY = 0f
    private var trackingBottomSwipe = false

    private fun saveCurrentScrollPosition(panel: Int) {
        val recycler = if (panel == 0) leftList else rightList
        val folder = if (panel == 0) leftFolder else rightFolder
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        if ((recycler.adapter?.itemCount ?: 0) > 0 && recycler.childCount > 0) {
            val pos = lm.findFirstVisibleItemPosition()
            val top = recycler.getChildAt(0)?.top ?: 0
            if (pos != RecyclerView.NO_POSITION) {
                folderScrollPositions["${panel}_${folder.absolutePath}"] = Pair(pos, top)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)

        val builder = android.os.StrictMode.VmPolicy.Builder()
        android.os.StrictMode.setVmPolicy(builder.build())

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        setContentView(R.layout.activity_main)

        leftList = findViewById(R.id.leftFileList)
        rightList = findViewById(R.id.rightFileList)
        leftList.layoutManager = LinearLayoutManager(this)
        rightList.layoutManager = LinearLayoutManager(this)
        leftList.itemAnimator = null
        rightList.itemAnimator = null

        leftList.setHasFixedSize(true)
        rightList.setHasFixedSize(true)
        leftList.setItemViewCacheSize(24)
        rightList.setItemViewCacheSize(24)

        val glideFlingListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_SETTLING) {
                    Glide.with(this@MainActivity).pauseRequests()
                } else {
                    Glide.with(this@MainActivity).resumeRequests()
                }
            }
        }
        leftList.addOnScrollListener(glideFlingListener)
        rightList.addOnScrollListener(glideFlingListener)

        pathText = findViewById(R.id.currentPath)
        pathText.isSingleLine = true
        pathText.ellipsize = TextUtils.TruncateAt.START
        leftPanel = findViewById(R.id.leftPanel)
        rightPanel = findViewById(R.id.rightPanel)
        leftHeader = findViewById(R.id.leftPanelHeader)
        rightHeader = findViewById(R.id.rightPanelHeader)
        currentFolderInfo = findViewById(R.id.currentFolderInfo)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnNew = findViewById(R.id.btnNew)
        btnMirror = findViewById(R.id.btnMirror)
        btnParent = findViewById(R.id.btnParent)
        btnMenu = findViewById(R.id.btnMenu)
        normalToolbar = findViewById(R.id.normalToolbar)
        selectionToolbar = findViewById(R.id.selectionToolbar)
        btnSelectAll = findViewById(R.id.btnSelectAll)
        btnClearSelection = findViewById(R.id.btnClearSelection)
        btnInvertSelection = findViewById(R.id.btnInvertSelection)

        loadSavedSettings()

        pathText.setOnClickListener {
            showStorageAndPathDialog()
        }

        setupPanelSelection()
        setupToolbar()
        setActivePanel(0)
        checkStoragePermission()
        cleanupExpiredTrash()
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
        leftSortBy = runCatching {
            SortBy.valueOf(prefs.getString("left_sort_by", "NAME") ?: "NAME")
        }.getOrDefault(SortBy.NAME)
        rightSortBy = runCatching {
            SortBy.valueOf(prefs.getString("right_sort_by", "NAME") ?: "NAME")
        }.getOrDefault(SortBy.NAME)
        leftSortReverse = prefs.getBoolean("left_sort_rev", false)
        rightSortReverse = prefs.getBoolean("right_sort_rev", false)
        showHiddenFiles = prefs.getBoolean("show_hidden", true)
        showTrashFiles = prefs.getBoolean("show_trash", false)
        showFileSizes = prefs.getBoolean("show_file_sizes", true)
    }

    private fun hasActiveFullScreenOverlay(): Boolean {
        return findViewById<View>(R.id.viewerRoot) != null ||
        findViewById<View>(R.id.zipRoot) != null ||
        findViewById<View>(R.id.editorRoot) != null ||
        findViewById<View>(R.id.trashRoot) != null ||
        findViewById<View>(R.id.bookmarksRoot) != null
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (hasActiveFullScreenOverlay()) {
            trackingBottomSwipe = false
            return super.dispatchTouchEvent(ev)
        }
        if (::normalToolbar.isInitialized && normalToolbar.visibility == View.VISIBLE) {
            val loc = IntArray(2)
            normalToolbar.getLocationOnScreen(loc)
            val toolbarTop = loc[1]
            val toolbarBottom = toolbarTop + normalToolbar.height

            when (ev.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (ev.rawY >= toolbarTop - (12 * resources.displayMetrics.density) && ev.rawY <= toolbarBottom) {
                        bottomSwipeStartX = ev.rawX
                        bottomSwipeStartY = ev.rawY
                        trackingBottomSwipe = true
                    } else {
                        trackingBottomSwipe = false
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (trackingBottomSwipe) {
                        trackingBottomSwipe = false
                        val dy = bottomSwipeStartY - ev.rawY
                        val dx = kotlin.math.abs(ev.rawX - bottomSwipeStartX)
                        if (dy > 38f * resources.displayMetrics.density && dy > dx) {
                            showHistoryDrawer()
                            return true
                        }
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    trackingBottomSwipe = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkStoragePermission() {
        if (hasStorageAccess()) {
            loadBothPanels()
            return
        }
        val teal = Color.parseColor("#62B1B0")
        val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Storage Access Required")
        .setMessage(
            "GB Files needs permission to browse, copy, move, and organize your files.\n\n" +
            "• 100% Offline (Zero internet permission)\n" +
            "• Zero ads, analytics, or tracking\n" +
            "• Your files never leave your device"
        )
        .setCancelable(false)
        .setNegativeButton("Exit") { _, _ -> finish() }
        .setPositiveButton("Grant Access") { _, _ ->
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    permissionSettingsOpened = true
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    permissionSettingsOpened = false
                    Toast.makeText(this, "Cannot open storage settings", Toast.LENGTH_LONG).show()
                }
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }
        .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.parseColor("#BDBDBD"))
        }
        dlg.show()
    }

    override fun onResume() {
        super.onResume()
        if (permissionSettingsOpened) {
            permissionSettingsOpened = false
            if (hasStorageAccess()) {
                loadBothPanels()
                Toast.makeText(this, "Storage access enabled!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        leftObserver?.stopWatching()
        rightObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
        thumbExecutor.shutdownNow()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        findViewById<View>(R.id.trashRoot)?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            loadBothPanels()
            return
        }
        findViewById<View>(R.id.bookmarksRoot)?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            return
        }
        findViewById<View>(R.id.zipRoot)?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            return
        }
        findViewById<View>(R.id.editorRoot)?.let { view ->
            (view.tag as? Runnable)?.run()
            return
        }
        findViewById<View>(R.id.viewerRoot)?.let { view ->
            view.findViewById<android.widget.VideoView>(R.id.viewerVideoView)?.stopPlayback()
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            refreshActiveFileList()
            updateSelectionToolbar()
            return
        }

        val current = getCurrentFolder()
        if (isSelectionModeActive(activePanel, current)) {
            getSelectedItems(activePanel).clear()
            setPanelSelectionMode(activePanel, false)
            if (activePanel == 0) leftRangeAnchor = null else rightRangeAnchor = null
            refreshActiveFileList()
            updateSelectionToolbar()
            return
        }

        val parent = current.parentFile
        if (parent != null && current.absolutePath != internalRoot.absolutePath) {
            navigateTo(parent)
            return
        }

        showQuitConfirmDialog()
    }

    private fun showQuitConfirmDialog() {
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("QUIT")
        .setMessage("Do you want to quit GB Files?")
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Ok") { _, _ ->
            finish()
        }
        .create()

        dialog.setOnShowListener {
            val teal = Color.parseColor("#62B1B0")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        dialog.show()
    }

    private val observerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val leftRefreshRunnable = Runnable {
        showFiles(leftFolder, leftList)
        if (activePanel == 0) updateSelectionToolbar()
    }
    private val rightRefreshRunnable = Runnable {
        showFiles(rightFolder, rightList)
        if (activePanel == 1) updateSelectionToolbar()
    }

    private fun startFolderObservers() {
        leftObserver?.stopWatching()
        rightObserver?.stopWatching()

        val mask = FileObserver.CREATE or
        FileObserver.DELETE or
        FileObserver.MOVED_TO or
        FileObserver.MOVED_FROM

        leftObserver = object : FileObserver(leftFolder.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                observerHandler.removeCallbacks(leftRefreshRunnable)
                observerHandler.postDelayed(leftRefreshRunnable, 300L)
            }
        }.also { it.startWatching() }

        rightObserver = object : FileObserver(rightFolder.absolutePath, mask) {
            override fun onEvent(event: Int, path: String?) {
                observerHandler.removeCallbacks(rightRefreshRunnable)
                observerHandler.postDelayed(rightRefreshRunnable, 300L)
            }
        }.also { it.startWatching() }
    }

    private fun getAvailableStorages(): List<Pair<String, File>> {
        val storages = mutableListOf<Pair<String, File>>()
        storages.add("Internal Storage" to internalRoot)
        try {
            val externalDirs = getExternalFilesDirs(null)
            for (dir in externalDirs) {
                if (dir == null) continue
                val path = dir.absolutePath
                val androidIdx = path.indexOf("/Android/")
                if (androidIdx > 0) {
                    val rootVol = File(path.substring(0, androidIdx))
                    if (rootVol.exists() && rootVol.absolutePath != internalRoot.absolutePath) {
                        val label = "External Storage (${rootVol.name})"
                        if (storages.none { it.second.absolutePath == rootVol.absolutePath }) {
                            storages.add(label to rootVol)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        storages.add("System Root (/)" to systemRoot)
        return storages
    }

    // STORAGE SWITCHER + JUMP TO PATH DIALOG
    private fun showStorageAndPathDialog() {
        val density = resources.displayMetrics.density
        val pad = (18 * density).toInt()
        val teal = Color.parseColor("#62B1B0")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (10 * density).toInt(), pad, 0)
            setBackgroundColor(Color.parseColor("#353434"))
        }

        val storages = getAvailableStorages()
        for ((label, dir) in storages) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val vp = (10 * density).toInt()
                setPadding(0, vp, 0, vp)
            }
            val icon = android.widget.ImageView(this).apply {
                val sz = (26 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(sz, sz)
                setImageResource(R.drawable.folder)
                setColorFilter(teal)
            }
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((10 * density).toInt(), 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleTv = TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.parseColor("#EFEFEF"))
            }
            val pathTv = TextView(this).apply {
                text = dir.absolutePath
                textSize = 11f
                setTextColor(Color.parseColor("#BDBDBD"))
            }
            col.addView(titleTv)
            col.addView(pathTv)
            row.addView(icon)
            row.addView(col)
            container.addView(row)
            row.tag = dir
        }

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                topMargin = (8 * density).toInt()
                bottomMargin = (8 * density).toInt()
            }
            setBackgroundColor(Color.parseColor("#555252"))
        }
        container.addView(divider)

        val currentPathStr = if (getCurrentFolder().absolutePath == "/") "/" else "${getCurrentFolder().absolutePath}/"
        val input = EditText(this).apply {
            setText(currentPathStr)
            setTextColor(Color.parseColor("#EFEFEF"))
            textSize = 15f
            setSelectAllOnFocus(true)
            setBackgroundColor(Color.TRANSPARENT)
        }
        container.addView(input)

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Storage & Path")
        .setView(container)
        .setNeutralButton("PASTE", null)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("OK", null)
        .create()

        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            val targetDir = child.tag as? File
            if (targetDir != null) {
                child.setOnClickListener {
                    dialog.dismiss()
                    navigateTo(targetDir)
                }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(teal)

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!clipText.isNullOrBlank()) {
                    input.setText(clipText.trim())
                    input.setSelection(input.text.length)
                } else {
                    Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val targetPath = input.text.toString().trim()
                if (targetPath.isEmpty()) return@setOnClickListener

                val targetFile = File(targetPath)
                when {
                    targetFile.exists() && targetFile.isDirectory -> {
                        navigateTo(targetFile)
                        dialog.dismiss()
                    }
                    targetFile.exists() && targetFile.isFile && targetFile.parentFile != null -> {
                        navigateTo(targetFile.parentFile!!)
                        dialog.dismiss()
                    }
                    else -> {
                        input.error = "Path does not exist"
                    }
                }
            }
        }
        dialog.show()
    }

    // PANEL SELECTION
    private fun setupPanelSelection() {
        leftPanel.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) setActivePanel(0)
            false
        }
        rightPanel.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) setActivePanel(1)
            false
        }
        leftHeader.setOnClickListener { setActivePanel(0) }
        rightHeader.setOnClickListener { setActivePanel(1) }
        leftList.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) setActivePanel(0)
            false
        }
        rightList.setOnTouchListener { _, event ->
            if (event.action == android.view.MotionEvent.ACTION_DOWN) setActivePanel(1)
            false
        }
    }

    // TOOLBAR
    private fun setupToolbar() {
        btnBack.setOnClickListener { goBack() }
        btnForward.setOnClickListener { goForward() }
        btnNew.setOnClickListener { showCreateNewMenu() }
        btnMirror.setOnClickListener { mirrorPanel() }
        btnParent.setOnClickListener { goToParent() }
        btnMenu.setOnClickListener { showTopMenu() }

        var toolbarDownY = 0f
        normalToolbar.setOnTouchListener { _, event ->
            if (hasActiveFullScreenOverlay()) return@setOnTouchListener false
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    toolbarDownY = event.rawY
                    false
                }
                android.view.MotionEvent.ACTION_UP -> {
                    if (toolbarDownY - event.rawY > 45f * resources.displayMetrics.density) {
                        showHistoryDrawer()
                        true
                    } else false
                }
                else -> false
            }
        }

        btnSelectAll.setOnClickListener { selectAllInCurrentFolder() }
        btnClearSelection.setOnClickListener {
            getSelectedItems(activePanel).clear()
            setPanelSelectionMode(activePanel, false)
            if (activePanel == 0) leftRangeAnchor = null else rightRangeAnchor = null
            refreshActiveFileList()
            updateSelectionToolbar()
        }
        btnInvertSelection.setOnClickListener { invertSelectionInCurrentFolder() }

        updateSelectionToolbar()
    }

    private fun refreshActiveFileList() {
        val recycler = if (activePanel == 0) leftList else rightList
        val adapter = recycler.adapter
        if (adapter != null) {
            adapter.notifyDataSetChanged()
        } else {
            showFiles(getCurrentFolder(), recycler)
        }
        updatePath()
    }

    private fun getVisibleFiles(folder: File): List<File> {
        val rawFiles = folder.listFiles() ?: return emptyList()
        return rawFiles.filter { file ->
            val name = file.name
            val isTrash = name.startsWith(".trashed") || name == ".Gonofiles" || name == ".fvTrash"
            val isHidden = name.startsWith(".") && !isTrash
            val passTrash = if (isTrash) showTrashFiles else true
            val passHidden = if (isHidden) showHiddenFiles else true
            passTrash && passHidden
        }
    }

    private fun selectAllInCurrentFolder() {
        val folder = getCurrentFolder()
        if (!folder.exists() || !folder.isDirectory) return
        val visibleFiles = getVisibleFiles(folder)
        if (visibleFiles.isEmpty()) return

        val selected = getSelectedItems(activePanel)
        val allAlreadySelected = visibleFiles.all { selected.contains(it.absolutePath) }

        if (allAlreadySelected) {
            visibleFiles.forEach { selected.remove(it.absolutePath) }
            if (activePanel == 0) leftRangeAnchor = null else rightRangeAnchor = null
        } else {
            visibleFiles.forEach { selected.add(it.absolutePath) }
        }
        setPanelSelectionMode(activePanel, true)

        refreshActiveFileList()
        updateSelectionToolbar()
    }

    private fun invertSelectionInCurrentFolder() {
        val folder = getCurrentFolder()
        if (!folder.exists() || !folder.isDirectory) return
        val visibleFiles = getVisibleFiles(folder)
        val selected = getSelectedItems(activePanel)

        visibleFiles.forEach { file ->
            val path = file.absolutePath
            if (selected.contains(path)) selected.remove(path) else selected.add(path)
        }
        setPanelSelectionMode(activePanel, true)
        refreshActiveFileList()
        updateSelectionToolbar()
    }

    private fun updatePanelInfo(panel: Int, folder: File) {
        val selectedItems = getSelectedItems(panel)
        val files = if (folder.exists() && folder.isDirectory) folder.listFiles() else null
        val folderCount = files?.count { it.isDirectory }
        val fileCount = files?.count { it.isFile }
        val selectedCount = selectedItems.count { selectedPath ->
            val selectedFile = File(selectedPath)
            selectedFile.exists() && selectedFile.parentFile?.absolutePath == folder.absolutePath
        }

        val countsText = if (folderCount == null || fileCount == null) {
            "Restricted / Unreadable"
        } else {
            buildString {
                append("Folders: $folderCount  Files: $fileCount")
                if (selectedCount > 0) append("  Selected: $selectedCount")
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastDiskCheckMs > 10_000L) {
            lastDiskCheckMs = now
            cachedDiskText = try {
                val stat = StatFs(internalRoot.absolutePath)
                val totalBytes = stat.totalBytes.toDouble()
                val usedBytes = (stat.totalBytes - stat.availableBytes).toDouble()
                val gib = 1024.0 * 1024.0 * 1024.0
                "Disk: ${String.format(Locale.US, "%.2f", usedBytes / gib)}G/" +
                "${String.format(Locale.US, "%.2f", totalBytes / gib)}G"
            } catch (e: Exception) {
                "Disk: unavailable"
            }
        }
        currentFolderInfo.text = "$countsText  $cachedDiskText"
    }

    private fun updateSelectionToolbar() {
        if (!::selectionToolbar.isInitialized || !::normalToolbar.isInitialized) return
        val folder = getCurrentFolder()
        updatePanelInfo(activePanel, folder)
        val hasSelectionHere = isSelectionModeActive(activePanel, folder)
        selectionToolbar.visibility = if (hasSelectionHere) View.VISIBLE else View.GONE
        normalToolbar.visibility = if (hasSelectionHere) View.GONE else View.VISIBLE
    }

    private fun showTopMenu() {
        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")
        val hiddenBox = if (showHiddenFiles) "[\u2713]" else "[  ]"
        val trashBox = if (showTrashFiles) "[\u2713]" else "[  ]"
        val sizeBox = if (showFileSizes) "[\u2713]" else "[  ]"

        val items = listOf(
            "Swap Panel",
            "Sort",
            "Search",
            "History",
            "Bookmarks",
            "Trash",
            "Hidden Files $hiddenBox",
            "Trash Files $trashBox",
            "File Sizes $sizeBox",
            "Help & Gestures",
            "About & Privacy",
            "Support"
        )

        val scroll = android.widget.ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#353434"))
            isVerticalScrollBarEnabled = true
        }
        val listCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val vp = (4 * density).toInt()
            setPadding(0, vp, 0, vp)
        }
        scroll.addView(listCol)

        val popupWidth = (185 * density).toInt()
        val popupHeight = (310 * density).toInt()

        val popupWindow = android.widget.PopupWindow(
            scroll,
            popupWidth,
            popupHeight,
            true
        ).apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#353434")))
            elevation = 12f * density
            isOutsideTouchable = true
        }

        val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
        for (title in items) {
            val tv = TextView(this).apply {
                text = title
                textSize = 14f
                setTextColor(if (title.contains("[\u2713]")) teal else Color.parseColor("#EFEFEF"))
                val hp = (16 * density).toInt()
                val vp = (9 * density).toInt()
                setPadding(hp, vp, hp, vp)
                setOnClickListener {
                    popupWindow.dismiss()
                    when {
                        title == "Swap Panel" -> swapPanels()
                        title == "Sort" -> showSortDialog()
                        title == "Search" -> showSearchDialog()
                        title == "History" -> showHistoryDrawer()
                        title == "Bookmarks" -> showBookmarksDialog()
                        title == "Trash" -> showTrashManagerDialog()
                        title.startsWith("Hidden Files") -> {
                            showHiddenFiles = !showHiddenFiles
                            prefs.edit().putBoolean("show_hidden", showHiddenFiles).apply()
                            loadBothPanels()
                        }
                        title.startsWith("Trash Files") -> {
                            showTrashFiles = !showTrashFiles
                            prefs.edit().putBoolean("show_trash", showTrashFiles).apply()
                            loadBothPanels()
                        }
                        title.startsWith("File Sizes") -> {
                            showFileSizes = !showFileSizes
                            prefs.edit().putBoolean("show_file_sizes", showFileSizes).apply()
                            loadBothPanels()
                        }
                        title == "Help & Gestures" -> {
                            val updateUrl = "https://gonobot.in"
                            val dlg = AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                            .setTitle("Help & Gestures")
                            .setMessage(getString(R.string.help_gestures_text))
                            .setNeutralButton("Check for Update") { _, _ ->
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    Toast.makeText(this@MainActivity, "No browser found to open link", Toast.LENGTH_SHORT).show()
                                }
                            }
                            .setPositiveButton("OK", null)
                            .create()
                            dlg.setOnShowListener {
                                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
                                dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(teal)
                            }
                            dlg.show()
                        }
                        title == "About & Privacy" -> {
                            val buildStatus = if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                                "Debug Build"
                            } else {
                                "Open Source Release"
                            }
                            val fullAbout = "${getString(R.string.about_privacy_text)}\n\n" +
                            "BUILD STATUS: $buildStatus"
                            showInfoTextDialog("About & Privacy Policy", fullAbout)
                        }
                        title == "Support" -> showSupportDialog()
                    }
                }
            }
            listCol.addView(tv)
        }

        val xOffset = btnMenu.width - popupWidth
        popupWindow.showAsDropDown(btnMenu, xOffset, 0)
    }

    private fun showInfoTextDialog(title: String, body: String) {
        val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton("OK", null)
        .create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#62B1B0"))
            dlg.findViewById<TextView>(android.R.id.message)?.apply {
                setTextIsSelectable(true)
                android.text.util.Linkify.addLinks(this, android.text.util.Linkify.WEB_URLS)
                setLinkTextColor(Color.parseColor("#62B1B0"))
            }
        }
        dlg.show()
    }

    private fun showSupportDialog() {
        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")
        val textPrimary = Color.parseColor("#EFEFEF")
        val textSecondary = Color.parseColor("#BDBDBD")

        val primarySupportUrl = "https://gonobot.in/support/"
        val secondarySupportUrl = "https://ko-fi.com/gonobot"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val hPad = (20 * density).toInt()
            setPadding(hPad, (12 * density).toInt(), hPad, (6 * density).toInt())
        }

        val descTv = TextView(this).apply {
            text = "GB Files is built independently with zero ads, zero tracking, and 100% offline privacy.\n\n" +
            "If this app helps you every day, consider supporting its development. " +
            "Tap a link box to copy it, or tap Open to view in your browser."
            textSize = 14f
            setTextColor(textSecondary)
            setLineSpacing(2f * density, 1f)
            setPadding(0, 0, 0, (14 * density).toInt())
        }
        root.addView(descTv)

        fun buildLinkStripRow(label: String, url: String, bottomMarginDp: Int) {
            val labelTv = TextView(this).apply {
                text = label
                textSize = 12f
                setTextColor(teal)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, (5 * density).toInt())
            }
            root.addView(labelTv)

            val rowHeight = (42 * density).toInt()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    rowHeight
                ).apply {
                    bottomMargin = (bottomMarginDp * density).toInt()
                }
            }

            val stripBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#232323"))
                cornerRadius = 8f * density
                setStroke((1 * density).toInt(), Color.parseColor("#4A4A4A"))
            }

            val linkBox = TextView(this).apply {
                text = url
                textSize = 13f
                setTextColor(textPrimary)
                isSingleLine = true
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER_VERTICAL
                background = stripBg
                val hp = (12 * density).toInt()
                setPadding(hp, 0, hp, 0)
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1f
                ).apply {
                    marginEnd = (8 * density).toInt()
                }
                setOnClickListener {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Support Link", url))
                    Toast.makeText(this@MainActivity, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }

            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                setColor(teal)
                cornerRadius = 8f * density
            }

            val openBtn = TextView(this).apply {
                text = "Open"
                textSize = 13f
                setTextColor(Color.parseColor("#1B1B1B"))
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                background = btnBg
                val hp = (16 * density).toInt()
                setPadding(hp, 0, hp, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                setOnClickListener {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(this@MainActivity, "No browser found to open link", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            row.addView(linkBox)
            row.addView(openBtn)
            root.addView(row)
        }

        buildLinkStripRow("Official Support Page (Website)", primarySupportUrl, bottomMarginDp = 12)
        buildLinkStripRow("Support on Ko-fi (PayPal / Card)", secondarySupportUrl, bottomMarginDp = 4)

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Support GB Files")
        .setView(root)
        .setPositiveButton("Close", null)
        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
        }
        dialog.show()
    }

    private fun showSortDialog() {
        val panelName = if (activePanel == 0) "Left Panel" else "Right Panel"
        val currentSort = if (activePanel == 0) leftSortBy else rightSortBy
        val currentRev = if (activePanel == 0) leftSortReverse else rightSortReverse
        val teal = Color.parseColor("#62B1B0")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }

        val radioGroup = RadioGroup(this)
        val options = listOf(
            SortBy.NAME to "Name",
            SortBy.TYPE to "Type",
            SortBy.DATE to "Date & Time",
            SortBy.SIZE to "Size"
        )

        options.forEachIndexed { idx, (enumVal, label) ->
            val rb = RadioButton(this).apply {
                id = idx + 100
                text = label
                setTextColor(Color.parseColor("#EFEFEF"))
                buttonTintList = android.content.res.ColorStateList.valueOf(teal)
                isChecked = (enumVal == currentSort)
            }
            radioGroup.addView(rb)
        }
        layout.addView(radioGroup)

        val reverseCheck = CheckBox(this).apply {
            text = "Reverse"
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
            isChecked = currentRev
        }
        layout.addView(reverseCheck)

        val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Sort ($panelName)")
        .setView(layout)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Done") { _, _ ->
            val selectedSort = when (radioGroup.checkedRadioButtonId) {
                100 -> SortBy.NAME
                101 -> SortBy.TYPE
                102 -> SortBy.DATE
                103 -> SortBy.SIZE
                else -> SortBy.NAME
            }
            val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
            if (activePanel == 0) {
                leftSortBy = selectedSort
                leftSortReverse = reverseCheck.isChecked
                prefs.edit()
                .putString("left_sort_by", leftSortBy.name)
                .putBoolean("left_sort_rev", leftSortReverse)
                .apply()
                showFiles(leftFolder, leftList)
            } else {
                rightSortBy = selectedSort
                rightSortReverse = reverseCheck.isChecked
                prefs.edit()
                .putString("right_sort_by", rightSortBy.name)
                .putBoolean("right_sort_rev", rightSortReverse)
                .apply()
                showFiles(rightFolder, rightList)
            }
        }
        .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        dlg.show()
    }

    private fun buzzHaptic(isStrong: Boolean) {
        try {
            val ms = if (isStrong) 65L else 25L
            val amp = if (isStrong) 255 else 110
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator.vibrate(android.os.VibrationEffect.createOneShot(ms, amp))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ms)
                }
            }
        } catch (_: Exception) { }
    }

    private fun reloadPanel(panel: Int) {
        if (panel == 0) {
            showFiles(leftFolder, leftList)
        } else {
            showFiles(rightFolder, rightList)
        }
        if (panel == activePanel) {
            updatePath()
        }
    }

    private fun loadBothPanels() {
        showFiles(leftFolder, leftList)
        showFiles(rightFolder, rightList)
        updatePath()
        startFolderObservers()
    }

    private fun updatePath() {
        val newPath = getCurrentFolder().absolutePath
        if (pathText.text?.toString() != newPath) {
            pathText.text = newPath
            pathText.alpha = 0.65f
            pathText.translationX = 16f * resources.displayMetrics.density
            pathText.animate().alpha(1f).translationX(0f).setDuration(140L).start()
        }
        updateSelectionToolbar()
    }

    private fun navigateTo(folder: File) {
        recentHighlightedPaths.clear()
        if (!folder.exists() || !folder.isDirectory) return
        val history = getCurrentHistory()
        val index = getHistoryIndex()
        if (folder.absolutePath == getCurrentFolder().absolutePath) return
        saveCurrentScrollPosition(activePanel)

        while (history.size > index + 1) {
            history.removeAt(history.size - 1)
        }
        history.add(folder)
        setHistoryIndex(history.size - 1)
        setCurrentFolder(folder)
        recordHistoryItem(folder)

        getSelectedItems(activePanel).clear()
        setPanelSelectionMode(activePanel, false)
        if (activePanel == 0) leftRangeAnchor = null else rightRangeAnchor = null

        val recycler = if (activePanel == 0) leftList else rightList
        showFiles(folder, recycler)
        updatePath()
        startFolderObservers()
    }

    private fun goBack() {
        val history = getCurrentHistory()
        val index = getHistoryIndex()
        if (index <= 0) {
            Toast.makeText(this, "No previous folder", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentScrollPosition(activePanel)
        val newIndex = index - 1
        setHistoryIndex(newIndex)
        val folder = history[newIndex]
        setCurrentFolder(folder)
        val recycler = if (activePanel == 0) leftList else rightList
        showFiles(folder, recycler)
        updatePath()
        startFolderObservers()
    }

    private fun goForward() {
        val history = getCurrentHistory()
        val index = getHistoryIndex()
        if (index >= history.size - 1) {
            Toast.makeText(this, "No next folder", Toast.LENGTH_SHORT).show()
            return
        }
        saveCurrentScrollPosition(activePanel)
        val newIndex = index + 1
        setHistoryIndex(newIndex)
        val folder = history[newIndex]
        setCurrentFolder(folder)
        val recycler = if (activePanel == 0) leftList else rightList
        showFiles(folder, recycler)
        updatePath()
        startFolderObservers()
    }

    private fun goToParent() {
        val current = getCurrentFolder()
        val parent = current.parentFile
        if (parent == null || current.absolutePath == internalRoot.absolutePath) {
            Toast.makeText(this, "Already at root (/)", Toast.LENGTH_SHORT).show()
            return
        }
        navigateTo(parent)
    }

    private fun mirrorPanel() {
        val sourceFolder = getCurrentFolder()
        if (!sourceFolder.exists() || !sourceFolder.isDirectory) {
            Toast.makeText(this, "Source folder unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val sourceHistory = getCurrentHistory().toList()
        val sourceIndex = getHistoryIndex()
        if (activePanel == 0) {
            rightFolder = sourceFolder
            rightHistory.clear()
            rightHistory.addAll(sourceHistory)
            rightHistoryIndex = sourceIndex
            showFiles(rightFolder, rightList)
        } else {
            leftFolder = sourceFolder
            leftHistory.clear()
            leftHistory.addAll(sourceHistory)
            leftHistoryIndex = sourceIndex
            showFiles(leftFolder, leftList)
        }
        updatePath()
        startFolderObservers()
    }

    private fun swapPanels() {
        val oldLeftFolder = leftFolder
        leftFolder = rightFolder
        rightFolder = oldLeftFolder

        val oldLeftHistory = leftHistory.toList()
        leftHistory.clear()
        leftHistory.addAll(rightHistory)
        rightHistory.clear()
        rightHistory.addAll(oldLeftHistory)

        val oldIndex = leftHistoryIndex
        leftHistoryIndex = rightHistoryIndex
        rightHistoryIndex = oldIndex

        val oldSelMode = leftSelectionMode
        leftSelectionMode = rightSelectionMode
        rightSelectionMode = oldSelMode

        showFiles(leftFolder, leftList)
        showFiles(rightFolder, rightList)
        updatePath()
        startFolderObservers()
    }

    private fun showCreateNewMenu() {
        val popup = PopupMenu(this, btnNew)
        popup.menu.add("New Folder")
        popup.menu.add("New File")
        popup.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "New Folder" -> {
                    createNewFolder()
                    true
                }
                "New File" -> {
                    createNewFile()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun createNewFile() {
        val input = EditText(this).apply {
            hint = "File name (e.g. notes.txt)"
            setText("untitled.txt")
            setTextColor(Color.parseColor("#EFEFEF"))
        }
        val teal = Color.parseColor("#62B1B0")
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Create new file")
        .setView(input)
        .setPositiveButton("Create", null)
        .setNegativeButton("Cancel", null)
        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            input.setSelection(0, "untitled".length)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "Enter a file name"
                    return@setOnClickListener
                }
                if (name.contains("/") || name.contains("\\") || name == "." || name == "..") {
                    input.error = "Invalid file name"
                    return@setOnClickListener
                }
                val newFile = File(getCurrentFolder(), name)
                if (newFile.exists()) {
                    input.error = "This name already exists"
                    return@setOnClickListener
                }
                try {
                    if (newFile.createNewFile()) {
                        recentHighlightedPaths.clear()
                        recentHighlightedPaths.add(newFile.absolutePath)
                        reloadPanel(activePanel)
                        dialog.dismiss()
                        if (TextEditorOverlay.isTextFile(newFile)) {
                            TextEditorOverlay.open(this, newFile) {
                                reloadPanel(activePanel)
                            }
                        }
                    } else {
                        Toast.makeText(this, "Could not create file", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot write to this folder", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private fun createNewFolder() {
        val input = EditText(this).apply {
            hint = "Folder name"
            setTextColor(Color.parseColor("#EFEFEF"))
        }
        val teal = Color.parseColor("#62B1B0")
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Create new folder")
        .setView(input)
        .setPositiveButton("Create", null)
        .setNegativeButton("Cancel", null)
        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "Enter a folder name"
                    return@setOnClickListener
                }
                if (name.contains("/") || name.contains("\\") || name == "." || name == "..") {
                    input.error = "Invalid folder name"
                    return@setOnClickListener
                }
                val newFolder = File(getCurrentFolder(), name)
                if (newFolder.exists()) {
                    input.error = "This name already exists"
                    return@setOnClickListener
                }
                if (newFolder.mkdir()) {
                    recentHighlightedPaths.clear()
                    recentHighlightedPaths.add(newFolder.absolutePath)
                    reloadPanel(activePanel)
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Could not create folder", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    private class FileRowViewHolder(
        val rowLayout: LinearLayout,
        val iconHolder: android.widget.ImageView,
        val nameView: TextView,
        val dateView: TextView
    ) : RecyclerView.ViewHolder(rowLayout)

    private fun showFiles(folder: File, recycler: RecyclerView) {
        val audioExts = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
        val panel = if (recycler == leftList) 0 else 1
        val density = resources.displayMetrics.density
        val currentToken = if (panel == 0) ++leftLoadToken else ++rightLoadToken
        if (panel == activePanel) updatePanelInfo(panel, folder)

        if (!folder.exists() || !folder.isDirectory) {
            recycler.adapter = null
            return
        }

        val sortBy = if (panel == 0) leftSortBy else rightSortBy
        val reverse = if (panel == 0) leftSortReverse else rightSortReverse
        val showHidden = showHiddenFiles
        val showTrash = showTrashFiles
        val includeSize = showFileSizes

        uiScope.launch {
            val (sortedItems, realFilesOnly, isRestricted) = withContext(Dispatchers.IO) {
                val rawList = folder.listFiles()
                val restricted = (rawList == null)
                val safeRaw = rawList ?: emptyArray()

                val filtered = safeRaw.filter { f ->
                    val name = f.name
                    val isTrash = name.startsWith(".trashed") || name == ".Gonofiles" || name == ".fvTrash"
                    val isHidden = name.startsWith(".") && !isTrash
                    (if (isTrash) showTrash else true) && (if (isHidden) showHidden else true)
                }

                val needStatsNow = (sortBy == SortBy.DATE || sortBy == SortBy.SIZE)
                val items = filtered.map { f ->
                    val isDir = f.isDirectory
                    val mod = if (needStatsNow) f.lastModified() else 0L
                    val size = if (needStatsNow && !isDir) f.length() else 0L
                    FileItem(
                        file = f,
                        name = f.name,
                        path = f.absolutePath,
                        isDirectory = isDir,
                        isParentNav = false,
                        ext = if (isDir) "" else f.extension.lowercase(),
                        sizeBytes = size,
                        lastModified = mod,
                        subtitleStr = ""
                    )
                }

                val itemComparator = Comparator<FileItem> { a, b ->
                    val cmp = when (sortBy) {
                        SortBy.NAME -> a.name.lowercase().compareTo(b.name.lowercase())
                        SortBy.TYPE -> a.ext.compareTo(b.ext)
                        SortBy.DATE -> a.lastModified.compareTo(b.lastModified)
                        SortBy.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                    }
                    if (reverse) -cmp else cmp
                }

                val sorted = items.sortedWith(
                    compareByDescending<FileItem> { it.isDirectory }.then(itemComparator)
                ).toMutableList()

                if (folder.absolutePath != internalRoot.absolutePath && folder.parentFile != null) {
                    sorted.add(
                        0,
                        FileItem(
                            file = folder.parentFile!!,
                            name = "..",
                            path = folder.parentFile!!.absolutePath,
                            isDirectory = true,
                            isParentNav = true,
                            ext = "",
                            sizeBytes = 0L,
                            lastModified = 0L,
                            subtitleStr = ""
                        )
                    )
                }

                val realOnly = sorted.filter { !it.isParentNav }.map { it.file }
                val isAndroidScopedRestricted = restricted || (
                    Build.VERSION.SDK_INT >= 30 &&
                    safeRaw.isEmpty() &&
                    (folder.absolutePath.endsWith("/Android/data") ||
                        folder.absolutePath.endsWith("/Android/obb") ||
                        folder.absolutePath.contains("/Android/data/") ||
                        folder.absolutePath.contains("/Android/obb/"))
                )
                Triple(sorted, realOnly, isAndroidScopedRestricted)
            }

            val activeToken = if (panel == 0) leftLoadToken else rightLoadToken
            if (activeToken != currentToken) return@launch

            if (isRestricted) {
                val density = resources.displayMetrics.density
                val teal = Color.parseColor("#62B1B0")
                recycler.adapter = object : RecyclerView.Adapter<FileRowViewHolder>() {
                    override fun getItemCount(): Int = 1

                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FileRowViewHolder {
                        val box = LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            val p = (20 * density).toInt()
                            setPadding(p, (48 * density).toInt(), p, p)
                            layoutParams = RecyclerView.LayoutParams(
                                RecyclerView.LayoutParams.MATCH_PARENT,
                                RecyclerView.LayoutParams.WRAP_CONTENT
                            )
                        }
                        val icon = android.widget.ImageView(this@MainActivity).apply {
                            val sz = (40 * density).toInt()
                            layoutParams = LinearLayout.LayoutParams(sz, sz)
                            setImageResource(R.drawable.folder)
                            setColorFilter(teal)
                        }
                        val titleTv = TextView(this@MainActivity).apply {
                            text = "Restricted by Android"
                            textSize = 15f
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#EFEFEF"))
                            setPadding(0, (12 * density).toInt(), 0, (6 * density).toInt())
                        }
                        val descTv = TextView(this@MainActivity).apply {
                            text = "Android 11+ blocks third-party file managers from reading Android/data and Android/obb directly.\n\nTap below to view with Android's built-in Files app."
                            textSize = 12f
                            gravity = Gravity.CENTER
                            setTextColor(Color.parseColor("#BDBDBD"))
                            setPadding(0, 0, 0, (16 * density).toInt())
                        }
                        val openBtn = TextView(this@MainActivity).apply {
                            text = "OPEN SYSTEM FILES"
                            textSize = 13f
                            setTextColor(Color.BLACK)
                            setBackgroundColor(teal)
                            val hp = (16 * density).toInt()
                            val vp = (10 * density).toInt()
                            setPadding(hp, vp, hp, vp)
                            setOnClickListener {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(
                                            Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid"),
                                            "vnd.android.document/directory"
                                        )
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        startActivity(Intent("android.provider.action.BROWSE"))
                                    } catch (_: Exception) {
                                        Toast.makeText(this@MainActivity, "System Files app unavailable", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        box.addView(icon)
                        box.addView(titleTv)
                        box.addView(descTv)
                        box.addView(openBtn)
                        return FileRowViewHolder(box, icon, titleTv, descTv)
                    }

                    override fun onBindViewHolder(holder: FileRowViewHolder, position: Int) {}
                }
                return@launch
            }

            val swipeThreshold = 55f * density
            val touchSlop = android.view.ViewConfiguration.get(this@MainActivity).scaledTouchSlop
            val maxDrag = 90f * density
            val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
            val videoExts = setOf("mp4", "mkv", "avi", "mov", "3gp")

            val lm = recycler.layoutManager as? LinearLayoutManager
            if (recycler.tag == folder.absolutePath && recycler.adapter != null && recycler.childCount > 0 && lm != null) {
                val pos = lm.findFirstVisibleItemPosition()
                val top = recycler.getChildAt(0)?.top ?: 0
                if (pos != RecyclerView.NO_POSITION) {
                    folderScrollPositions["${panel}_${folder.absolutePath}"] = Pair(pos, top)
                }
            }
            recycler.tag = folder.absolutePath

            recycler.adapter = object : RecyclerView.Adapter<FileRowViewHolder>() {
                override fun getItemCount(): Int = sortedItems.size

                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FileRowViewHolder {
                    val rowLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isLongClickable = true
                        val hPad = (6 * density).toInt()
                        val vPad = (6 * density).toInt()
                        setPadding(hPad, vPad, hPad, vPad)
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            (52 * density).toInt()
                        )
                    }
                    val iconHolder = android.widget.ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (32 * density).toInt(),
                            (32 * density).toInt()
                        )
                    }
                    val textColumn = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((6 * density).toInt(), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                        )
                    }
                    val nameView = TextView(this@MainActivity).apply {
                        textSize = 14f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }
                    val dateView = TextView(this@MainActivity).apply {
                        textSize = 10f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    textColumn.addView(nameView)
                    textColumn.addView(dateView)
                    rowLayout.addView(iconHolder)
                    rowLayout.addView(textColumn)
                    return FileRowViewHolder(rowLayout, iconHolder, nameView, dateView)
                }

                override fun onBindViewHolder(holder: FileRowViewHolder, position: Int) {
                    val item = sortedItems[position]
                    val rowLayout = holder.rowLayout
                    val iconHolder = holder.iconHolder
                    val nameView = holder.nameView
                    val dateView = holder.dateView

                    rowLayout.translationX = 0f
                    Glide.with(this@MainActivity).clear(iconHolder)
                    iconHolder.tag = null
                    iconHolder.clearColorFilter()

                    if (item.isParentNav) {
                        val p = (4 * density).toInt()
                        iconHolder.setPadding(p, p, p, p)
                        iconHolder.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        iconHolder.setImageResource(R.drawable.folder)
                        iconHolder.setColorFilter(Color.parseColor("#62B1B0"))
                        nameView.text = ".."
                        nameView.setTextColor(Color.parseColor("#62B1B0"))
                        dateView.visibility = View.GONE
                        rowLayout.setBackgroundColor(Color.TRANSPARENT)
                        rowLayout.setOnTouchListener(null)
                        rowLayout.setOnLongClickListener(null)
                        rowLayout.setOnClickListener {
                            setActivePanel(panel)
                            val parentDir = folder.parentFile
                            if (parentDir != null && parentDir.exists()) {
                                navigateTo(parentDir)
                            }
                        }
                        return
                    }

                    dateView.visibility = View.VISIBLE
                    val selectedItems = getSelectedItems(panel)
                    val isSelected = selectedItems.contains(item.path)

                    val isImage = !item.isDirectory && item.ext in imageExts
                    val isApk = !item.isDirectory && item.ext == "apk"
                    val isVideo = !item.isDirectory && item.ext in videoExts

                    if (isImage || isVideo) {
                        iconHolder.setPadding(0, 0, 0, 0)
                        iconHolder.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        Glide.with(this@MainActivity)
                        .load(item.file)
                        .override(96, 96)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .placeholder(R.drawable.files)
                        .error(R.drawable.files)
                        .into(iconHolder)
                    } else if (isApk) {
                        iconHolder.setPadding(0, 0, 0, 0)
                        iconHolder.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        iconHolder.setImageResource(R.drawable.files)
                        loadThumbnailAsync(item.file, isApk = true, isVideo = false, imageView = iconHolder)
                    } else {
                        val p = (4 * density).toInt()
                        iconHolder.setPadding(p, p, p, p)
                        iconHolder.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        if (item.isDirectory) {
                            iconHolder.setImageResource(R.drawable.folder)
                            iconHolder.setColorFilter(Color.parseColor("#62B1B0"))
                        } else {
                            iconHolder.setImageResource(R.drawable.files)
                            iconHolder.setColorFilter(Color.parseColor("#BDBDBD"))
                        }
                    }

                    val isNewlyTransferred = recentHighlightedPaths.contains(item.path)
                    nameView.text = item.name
                    nameView.setTextColor(
                        when {
                            isSelected -> Color.WHITE
                            isNewlyTransferred -> Color.parseColor("#62B1B0")
                            else -> Color.rgb(239, 239, 239)
                        }
                    )
                    val rowSub = if (item.subtitleStr.isNotEmpty()) {
                        item.subtitleStr
                    } else {
                        val dStr = dateFormatter.format(Date(item.file.lastModified()))
                        if (!item.isDirectory && includeSize) {
                            "${formatCompactSize(item.file.length())}  •  $dStr"
                        } else {
                            dStr
                        }
                    }
                    dateView.text = rowSub
                    dateView.setTextColor(if (isSelected) Color.rgb(210, 235, 235) else Color.rgb(165, 165, 165))
                    updateRowAppearance(rowLayout, isSelected)

                    rowLayout.setOnClickListener {
                        setActivePanel(panel)
                        if (isSelectionModeActive(panel, folder)) {
                            toggleItemSelection(item.file, panel, recycler, folder)
                        } else if (item.isDirectory) {
                            navigateTo(item.file)
                        } else {
                            recordHistoryItem(item.file)
                            when (item.ext) {
                                "zip" -> openZipViewer(item.file)
                                in audioExts -> openMediaViewer(item.file, "audio")
                                in imageExts -> openMediaViewer(item.file, "image")
                                in videoExts -> openMediaViewer(item.file, "video")
                                "pdf" -> openMediaViewer(item.file, "pdf")
                                "apk" -> installApkFile(item.file)
                                else -> {
                                    if (TextEditorOverlay.isTextFile(item.file) && item.file.length() <= 2 * 1024 * 1024L) {
                                        TextEditorOverlay.open(this@MainActivity, item.file) {
                                            loadBothPanels()
                                        }
                                    } else {
                                        openFileWith(item.file)
                                    }
                                }
                            }
                        }
                    }

                    rowLayout.setOnLongClickListener {
                        setActivePanel(panel)
                        if (!selectedItems.contains(item.path)) {
                            toggleItemSelection(item.file, panel, recycler, folder)
                        } else {
                            showFileOperationsPopup(panel)
                        }
                        true
                    }

                    var downX = 0f
                    var downY = 0f
                    var draggingRight = false
                    rowLayout.setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                downX = event.rawX
                                downY = event.rawY
                                draggingRight = false
                                setActivePanel(panel)
                                false
                            }
                            android.view.MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - downX
                                val dy = event.rawY - downY
                                if (!draggingRight) {
                                    if (kotlin.math.abs(dy) > touchSlop) return@setOnTouchListener false
                                    if (dx > touchSlop * 1.8f && dx > kotlin.math.abs(dy) * 2.0f) {
                                        draggingRight = true
                                        view.cancelLongPress()
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                    }
                                }
                                if (draggingRight) {
                                    view.cancelLongPress()
                                    view.translationX = dx.coerceAtMost(maxDrag)
                                    view.setBackgroundColor(Color.rgb(65, 105, 103))
                                    true
                                } else false
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                if (draggingRight) {
                                    val dx = event.rawX - downX
                                    draggingRight = false
                                    view.cancelLongPress()
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                    if (dx >= swipeThreshold) {
                                        selectRangeBySwipe(item.file, realFilesOnly, panel)
                                    }
                                    view.animate().translationX(0f).setDuration(120).withEndAction {
                                        recycler.adapter?.notifyDataSetChanged()
                                    }.start()
                                    true
                                } else false
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                if (draggingRight) {
                                    draggingRight = false
                                    view.cancelLongPress()
                                    view.parent?.requestDisallowInterceptTouchEvent(false)
                                    view.translationX = 0f
                                    updateRowAppearance(
                                        view as LinearLayout,
                                        selectedItems.contains(item.path)
                                    )
                                }
                                false
                            }
                            else -> false
                        }
                    }
                }
            }

            val savedScroll = folderScrollPositions["${panel}_${folder.absolutePath}"]
            if (savedScroll != null && lm != null) {
                val targetPos = savedScroll.first.coerceIn(0, (sortedItems.size - 1).coerceAtLeast(0))
                lm.scrollToPositionWithOffset(targetPos, savedScroll.second)
            }
        }
    }

    private fun updateRowAppearance(row: LinearLayout, selected: Boolean) {
        if (selected) {
            row.setBackgroundColor(Color.rgb(55, 91, 90))
        } else {
            row.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    private fun getSelectedItems(panel: Int): MutableSet<String> {
        return if (panel == 0) leftSelected else rightSelected
    }

    private fun isPanelSelectionMode(panel: Int): Boolean {
        return if (panel == 0) leftSelectionMode else rightSelectionMode
    }

    private fun setPanelSelectionMode(panel: Int, enabled: Boolean) {
        if (panel == 0) leftSelectionMode = enabled else rightSelectionMode = enabled
    }

    private fun isSelectionModeActive(panel: Int, folder: File): Boolean {
        if (isPanelSelectionMode(panel)) return true
        val selected = getSelectedItems(panel)
        val currentFolderPath = folder.absolutePath
        return selected.any { selectedPath ->
            File(selectedPath).parentFile?.absolutePath == currentFolderPath
        }
    }

    private fun toggleItemSelection(
        file: File,
        panel: Int,
        recycler: RecyclerView,
        folder: File
    ) {
        recentHighlightedPaths.clear()
        // Any single tap resets the swipe-range anchor without clearing existing selections
        if (panel == 0) leftRangeAnchor = null else rightRangeAnchor = null

        val selected = getSelectedItems(panel)
        val path = file.absolutePath
        if (selected.contains(path)) {
            selected.remove(path)
        } else {
            selected.add(path)
            setPanelSelectionMode(panel, true)
        }
        recycler.adapter?.notifyDataSetChanged()
        updateSelectionToolbar()
    }

    private fun selectRangeBySwipe(
        file: File,
        sortedFiles: List<File>,
        panel: Int
    ) {
        val selected = getSelectedItems(panel)
        val currentPath = file.absolutePath
        val anchor = if (panel == 0) leftRangeAnchor else rightRangeAnchor
        val anchorIndex = sortedFiles.indexOfFirst { it.absolutePath == anchor }
        val targetIndex = sortedFiles.indexOfFirst { it.absolutePath == currentPath }

        if (anchor == null || anchorIndex < 0 || targetIndex < 0 || anchor == currentPath) {
            // FIRST SWIPE: Select anchor item + short subtle buzz
            selected.add(currentPath)
            if (panel == 0) leftRangeAnchor = currentPath else rightRangeAnchor = currentPath
            buzzHaptic(isStrong = false)
        } else {
            // SECOND SWIPE: Select entire range between Swipe 1 & Swipe 2 + strong buzz + reset anchor
            val first = minOf(anchorIndex, targetIndex)
            val last = maxOf(anchorIndex, targetIndex)
            for (i in first..last) {
                selected.add(sortedFiles[i].absolutePath)
            }
            if (panel == 0) leftRangeAnchor = null else rightRangeAnchor = null
            buzzHaptic(isStrong = true)
        }
        setPanelSelectionMode(panel, true)
        updateSelectionToolbar()
    }

    private fun setActivePanel(panel: Int) {
        val target = if (panel == 0) 0 else 1
        if (activePanel == target && ::leftHeader.isInitialized) return
        activePanel = target
        leftHeader.setTextColor(if (activePanel == 0) Color.rgb(98, 177, 176) else Color.rgb(150, 150, 150))
        rightHeader.setTextColor(if (activePanel == 1) Color.rgb(98, 177, 176) else Color.rgb(150, 150, 150))
        updatePath()
        updateSelectionToolbar()
    }

    private fun getCurrentFolder(): File {
        return if (activePanel == 0) leftFolder else rightFolder
    }

    private fun setCurrentFolder(folder: File) {
        if (activePanel == 0) leftFolder = folder else rightFolder = folder
    }

    private fun getCurrentHistory(): MutableList<File> {
        return if (activePanel == 0) leftHistory else rightHistory
    }

    private fun getHistoryIndex(): Int {
        return if (activePanel == 0) leftHistoryIndex else rightHistoryIndex
    }

    private fun setHistoryIndex(index: Int) {
        if (activePanel == 0) leftHistoryIndex = index else rightHistoryIndex = index
    }

    private fun showFileOperationsPopup(panel: Int) {
        val currentFolder = if (panel == 0) leftFolder else rightFolder
        val targetFolder = if (panel == 0) rightFolder else leftFolder
        val selectedFiles = getSelectedItems(panel)
        .map { File(it) }
        .filter { it.exists() && it.parentFile?.absolutePath == currentFolder.absolutePath }

        if (selectedFiles.isEmpty()) return

        val hasFolder = selectedFiles.any { it.isDirectory }
        val isSingle = selectedFiles.size == 1
        val singleFile = if (isSingle) selectedFiles[0] else null
        val isSingleZip = isSingle && singleFile != null && !singleFile.isDirectory &&
        singleFile.extension.lowercase() == "zip"
        val isSameDirectory = leftFolder.absolutePath == rightFolder.absolutePath

        data class OpItem(val label: String, val enabled: Boolean)
        val options = mutableListOf<OpItem>()
        options.add(OpItem("Copy", true))
        options.add(OpItem("Move", !isSameDirectory))
        options.add(OpItem("Delete", true))
        options.add(OpItem("Info", true))
        options.add(OpItem("Bookmark", true))
        options.add(OpItem("Rename", isSingle))
        options.add(OpItem("Open with...", isSingle && !hasFolder))
        options.add(OpItem("Share", !hasFolder))
        if (isSingleZip) {
            options.add(OpItem("Extract to...", true))
        }
        options.add(OpItem("Compress", true))

        val dialogContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#353434"))
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
        }

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setView(dialogContainer)
        .create()

        for (op in options) {
            val tv = TextView(this).apply {
                text = op.label
                textSize = 16f
                setTextColor(if (op.enabled) Color.parseColor("#EFEFEF") else Color.parseColor("#777777"))
                val hPad = (24 * resources.displayMetrics.density).toInt()
                val vPad = (14 * resources.displayMetrics.density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                isEnabled = op.enabled
                setOnClickListener {
                    dialog.dismiss()
                    handleFileOperation(op.label, selectedFiles, targetFolder, panel)
                }
            }
            dialogContainer.addView(tv)
        }

        dialog.show()
    }

    private fun handleFileOperation(
        action: String,
        files: List<File>,
        targetFolder: File,
        panel: Int
    ) {
        when (action) {
            "Copy" -> showCopyOrMoveConfirmDialog(files, targetFolder, isMove = false, panel = panel)
            "Move" -> showCopyOrMoveConfirmDialog(files, targetFolder, isMove = true, panel = panel)
            "Delete" -> showDeleteConfirmDialog(files, panel)
            "Rename" -> if (files.size == 1) showRenameDialog(files[0], panel)
            "Info" -> showInfoDialog(files)
            "Open with..." -> if (files.size == 1) openFileWith(files[0])
            "Share" -> shareFiles(files)
            "Bookmark" -> toggleBookmarks(files, panel)
            "Extract to..." -> if (files.size == 1) showExtractDialog(files[0], panel)
            "Compress" -> showCompressDialog(files, panel)
        }
    }

    private fun showCopyOrMoveConfirmDialog(
        files: List<File>,
        targetFolder: File,
        isMove: Boolean,
        panel: Int
    ) {
        val title = if (isMove) "Move" else "Copy"
        val verb = if (isMove) "move" else "copy"
        val targetDesc = if (files.size == 1) {
            val f = files[0]
            val kind = if (f.isDirectory) "folder" else "file"
            "$kind ${f.name}"
        } else {
            "${files.size} items"
        }

        val message = "Do you want to $verb $targetDesc into\n${targetFolder.absolutePath}/ ?"

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle(title)
        .setMessage(message)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("OK") { _, _ ->
            performCopyOrMove(files, targetFolder, isMove, panel)
        }
        .create()

        dialog.setOnShowListener {
            val teal = Color.parseColor("#62B1B0")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        dialog.show()
    }

    private fun showDeleteConfirmDialog(files: List<File>, panel: Int) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val teal = Color.parseColor("#62B1B0")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, 0)
        }

        val targetDesc = if (files.size == 1) {
            val f = files[0]
            val kind = if (f.isDirectory) "folder" else "file"
            "$kind ${f.name}"
        } else {
            "${files.size} items"
        }

        val promptText = TextView(this).apply {
            text = "Do you want to move $targetDesc to the recycle bin?"
            textSize = 15f
            setTextColor(Color.parseColor("#EFEFEF"))
        }
        container.addView(promptText)

        val sizeText = TextView(this).apply {
            text = "Calculating size..."
            textSize = 13f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
        }
        container.addView(sizeText)

        val trashCheckBox = CheckBox(this).apply {
            text = "Move to recycle bin"
            textSize = 14f
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
            isChecked = true
            setOnCheckedChangeListener { _, isChecked ->
                promptText.text = if (isChecked) {
                    "Do you want to move $targetDesc to the recycle bin?"
                } else {
                    "Do you want to permanently delete $targetDesc?"
                }
            }
        }
        container.addView(trashCheckBox)

        uiScope.launch {
            val totalBytes = withContext(Dispatchers.IO) {
                var bytes = 0L
                fun calc(f: File) {
                    if (f.isDirectory) f.listFiles()?.forEach { calc(it) }
                    else bytes += f.length()
                }
                files.forEach { calc(it) }
                bytes
            }
            sizeText.text = "Size ${formatCompactSize(totalBytes)}"
        }

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Delete")
        .setView(container)
        .setNegativeButton("CANCEL", null)
        .setPositiveButton("OK") { _, _ ->
            performDelete(files, moveToTrash = trashCheckBox.isChecked, panel = panel)
        }
        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        dialog.show()
    }

    private fun formatCompactSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2fG", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.2fM", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.2fK", bytes / kb)
            else -> "${bytes}B"
        }
    }

    private fun performDelete(files: List<File>, moveToTrash: Boolean, panel: Int) {
        leftObserver?.stopWatching()
        rightObserver?.stopWatching()
        uiScope.launch {
            val successCount = withContext(Dispatchers.IO) {
                val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
                val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
                if (moveToTrash) {
                    trashFilesDir.mkdirs()
                    trashMetaDir.mkdirs()
                }

                var count = 0
                for (src in files) {
                    if (moveToTrash) {
                        val trashId = "${System.currentTimeMillis()}_${src.name}"
                        val destFile = File(trashFilesDir, trashId)
                        val moved = if (src.renameTo(destFile)) true
                        else copyRecursively(src, destFile) && src.deleteRecursively()

                        if (moved) {
                            val metaFile = File(trashMetaDir, "$trashId.meta")
                            metaFile.writeText(
                                "originalPath=${src.absolutePath}\n" +
                                "originalName=${src.name}\n" +
                                "deletedAt=${System.currentTimeMillis()}\n" +
                                "isDirectory=${src.isDirectory}"
                            )
                            count++
                        }
                    } else {
                        val deleted = if (src.isDirectory) src.deleteRecursively() else src.delete()
                        if (deleted) count++
                    }
                }
                count
            }

            getSelectedItems(panel).clear()
            setPanelSelectionMode(panel, false)
            loadBothPanels()
            val msg = if (moveToTrash) "Moved $successCount item(s) to Trash"
            else "Deleted $successCount item(s)"
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun moveSingleFileToTrash(target: File) {
        val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
        val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
        trashFilesDir.mkdirs()
        trashMetaDir.mkdirs()
        val trashId = "${System.currentTimeMillis()}_${target.name}"
        val destFile = File(trashFilesDir, trashId)
        val moved = if (target.renameTo(destFile)) true
        else copyRecursively(target, destFile) && target.deleteRecursively()
        if (moved) {
            File(trashMetaDir, "$trashId.meta").writeText(
                "originalPath=${target.absolutePath}\n" +
                "originalName=${target.name}\n" +
                "deletedAt=${System.currentTimeMillis()}\n" +
                "isDirectory=${target.isDirectory}"
            )
        }
    }

    private fun askConflictResolution(
        fileName: String,
        isMove: Boolean,
        onResult: (mode: Int, recycleReplaced: Boolean, applyAll: Boolean) -> Unit,
        onCancel: () -> Unit
    ) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val teal = Color.parseColor("#62B1B0")
        val verb = if (isMove) "Move" else "Copy"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (10 * density).toInt(), pad, 0)
        }

        val fileTv = TextView(this).apply {
            text = fileName
            textSize = 15f
            setTextColor(Color.parseColor("#EFEFEF"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        container.addView(fileTv)

        val radioGroup = RadioGroup(this)
        val rbReplace = RadioButton(this).apply {
            id = 8101
            text = "$verb and replace"
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
        }
        val rbSkip = RadioButton(this).apply {
            id = 8102
            text = "Skip this file"
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
        }
        val rbKeepBoth = RadioButton(this).apply {
            id = 8103
            text = "$verb but keep both files"
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
            isChecked = true
        }
        radioGroup.addView(rbReplace)
        radioGroup.addView(rbSkip)
        radioGroup.addView(rbKeepBoth)
        container.addView(radioGroup)

        val chkRecycle = CheckBox(this).apply {
            text = "Move replaced file to recycle bin"
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
            isChecked = true
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val chkApplyAll = CheckBox(this).apply {
            text = "Apply to subsequent conflicts"
            setTextColor(Color.parseColor("#EFEFEF"))
            buttonTintList = android.content.res.ColorStateList.valueOf(teal)
            isChecked = false
        }
        container.addView(chkRecycle)
        container.addView(chkApplyAll)

        val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("File already exists")
        .setView(container)
        .setCancelable(false)
        .setNegativeButton("CANCEL") { _, _ -> onCancel() }
        .setPositiveButton("OK") { _, _ ->
            val mode = when (radioGroup.checkedRadioButtonId) {
                8101 -> 0
                8102 -> 1
                else -> 2
            }
            onResult(mode, chkRecycle.isChecked, chkApplyAll.isChecked)
        }
        .create()

        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        dlg.show()
    }

    private fun performCopyOrMove(
        files: List<File>,
        targetFolder: File,
        isMove: Boolean,
        panel: Int
    ) {
        if (!targetFolder.exists() || !targetFolder.isDirectory) {
            Toast.makeText(this, "Destination folder unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        leftObserver?.stopWatching()
        rightObserver?.stopWatching()

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val teal = Color.parseColor("#62B1B0")

        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (14 * density).toInt(), pad, (8 * density).toInt())
        }
        val statusTv = TextView(this).apply {
            text = if (isMove) "Preparing to move..." else "Preparing to copy..."
            textSize = 14f
            setTextColor(Color.parseColor("#EFEFEF"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val countTv = TextView(this).apply {
            text = "0 / ${files.size}"
            textSize = 12f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        }
        val progressBar = android.widget.ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = files.size.coerceAtLeast(1)
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(teal)
        }
        progressContainer.addView(statusTv)
        progressContainer.addView(countTv)
        progressContainer.addView(progressBar)

        var transferJob: Job? = null
        val progressDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle(if (isMove) "Moving files" else "Copying files")
        .setView(progressContainer)
        .setCancelable(false)
        .setNegativeButton("CANCEL") { _, _ ->
            transferJob?.cancel()
        }
        .create()

        progressDialog.setOnShowListener {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        progressDialog.show()

        recentHighlightedPaths.clear()
        val newlyCreatedPaths = mutableSetOf<String>()
        var successCount = 0
        var savedMode: Int? = null
        var savedRecycle = true
        var lastUiUpdateMs = 0L

        suspend fun promptConflictSuspend(fileName: String): Triple<Int, Boolean, Boolean>? =
        suspendCancellableCoroutine { cont ->
            askConflictResolution(
                fileName = fileName,
                isMove = isMove,
                onResult = { mode, recycleReplaced, applyAll ->
                    if (cont.isActive) cont.resumeWith(Result.success(Triple(mode, recycleReplaced, applyAll)))
                },
                onCancel = {
                    if (cont.isActive) cont.resumeWith(Result.success(null))
                }
            )
        }

        transferJob = uiScope.launch {
            try {
                for ((idx, src) in files.withIndex()) {
                    if (!isActive) break

                    val now = System.currentTimeMillis()
                    if (idx == 0 || idx == files.lastIndex || now - lastUiUpdateMs >= 60L) {
                        lastUiUpdateMs = now
                        statusTv.text = src.name
                        countTv.text = "${idx + 1} / ${files.size}"
                        progressBar.progress = idx + 1
                    }

                    val directDest = File(targetFolder, src.name)
                    if (src.isDirectory && directDest.absolutePath.startsWith(src.absolutePath + File.separator)) {
                        continue
                    }

                    var mode = 2
                    var recycleReplaced = false

                    if (directDest.exists()) {
                        if (savedMode != null) {
                            mode = savedMode!!
                            recycleReplaced = savedRecycle
                        } else {
                            progressDialog.hide()
                            val choice = promptConflictSuspend(src.name)
                            if (choice == null) break
                            mode = choice.first
                            recycleReplaced = choice.second
                            if (choice.third) {
                                savedMode = mode
                                savedRecycle = recycleReplaced
                            }
                            progressDialog.show()
                        }
                    }

                    val transferredPath = withContext(Dispatchers.IO) {
                        val finalDest: File? = when {
                            !directDest.exists() -> directDest
                            mode == 1 -> null
                            mode == 0 -> {
                                if (recycleReplaced) {
                                    moveSingleFileToTrash(directDest)
                                } else {
                                    if (directDest.isDirectory) directDest.deleteRecursively()
                                    else directDest.delete()
                                }
                                directDest
                            }
                            else -> getUniqueDestination(targetFolder, src.name)
                        }

                        if (finalDest != null) {
                            val ok = if (isMove) {
                                if (src.renameTo(finalDest)) true
                                else copyRecursively(src, finalDest) && src.deleteRecursively()
                            } else {
                                copyRecursively(src, finalDest)
                            }
                            if (ok) finalDest.absolutePath else null
                        } else null
                    }

                    if (transferredPath != null) {
                        successCount++
                        newlyCreatedPaths.add(transferredPath)
                    }
                }
            } finally {
                if (progressDialog.isShowing) progressDialog.dismiss()
                recentHighlightedPaths.clear()
                recentHighlightedPaths.addAll(newlyCreatedPaths)
                getSelectedItems(panel).clear()
                setPanelSelectionMode(panel, false)
                loadBothPanels()
                val verb = if (isMove) "Moved" else "Copied"
                Toast.makeText(this@MainActivity, "$verb $successCount item(s)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun copyRecursively(src: File, dest: File): Boolean {
        return try {
            if (src.isDirectory) {
                if (!dest.exists() && !dest.mkdirs()) return false
                src.listFiles()?.forEach { child ->
                    if (!copyRecursively(child, File(dest, child.name))) return false
                }
                true
            } else {
                src.inputStream().use { input ->
                    dest.outputStream().use { output ->
                        input.copyTo(output, bufferSize = 65536)
                    }
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun getUniqueDestination(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dotIdx = name.lastIndexOf('.')
        val base = if (dotIdx > 0) name.substring(0, dotIdx) else name
        val ext = if (dotIdx > 0) name.substring(dotIdx) else ""
        var count = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_($count)$ext")
            count++
        }
        return candidate
    }

    // RENAME DIALOG
    private fun showRenameDialog(file: File, panel: Int) {
        val teal = Color.parseColor("#62B1B0")
        val input = EditText(this).apply {
            setText(file.name)
            setTextColor(Color.parseColor("#EFEFEF"))
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Rename")
        .setView(input)
        .setPositiveButton("OK", null)
        .setNegativeButton("Cancel", null)
        .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isEmpty() || newName.contains("/") || newName.contains("\\")) {
                    input.error = "Invalid name"
                    return@setOnClickListener
                }
                val newFile = File(file.parentFile, newName)
                if (newFile.exists()) {
                    input.error = "Name already exists"
                    return@setOnClickListener
                }
                if (file.renameTo(newFile)) {
                    recentHighlightedPaths.clear()
                    recentHighlightedPaths.add(newFile.absolutePath)
                    getSelectedItems(panel).clear()
                    setPanelSelectionMode(panel, false)
                    loadBothPanels()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    // INFO DIALOG
    private fun showInfoDialog(files: List<File>) {
        uiScope.launch {
            val message = withContext(Dispatchers.IO) {
                var totalBytes = 0L
                var fileCount = 0
                var folderCount = 0

                fun scan(f: File) {
                    if (f.isDirectory) {
                        folderCount++
                        f.listFiles()?.forEach { scan(it) }
                    } else {
                        fileCount++
                        totalBytes += f.length()
                    }
                }

                if (files.size == 1) {
                    val single = files[0]
                    if (single.isDirectory) {
                        single.listFiles()?.forEach { scan(it) }
                    } else {
                        totalBytes = single.length()
                    }
                } else {
                    files.forEach { scan(it) }
                }

                if (files.size == 1) {
                    val f = files[0]
                    val typeStr = when {
                        f.isDirectory -> "Folder"
                        f.extension.isNotEmpty() -> f.extension.uppercase()
                        else -> "File"
                    }
                    val modifiedStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(Date(f.lastModified()))

                    buildString {
                        append("Name: ${f.name}\n\n")
                        append("Path: ${f.parent ?: "/"}\n\n")
                        append("Type: $typeStr\n\n")
                        append("Size: ${formatReadableSize(totalBytes)}\n\n")
                        append("Modified: $modifiedStr")
                        if (f.isDirectory) {
                            append("\n\nFile Count: $fileCount\n")
                            append("Folder Count: $folderCount")
                        }
                    }
                } else {
                    "Size: ${formatReadableSize(totalBytes)}\n\n" +
                    "Items: ${files.size} ($folderCount folders, $fileCount files)"
                }
            }

            val dlg = AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Properties")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#62B1B0"))
            }
            dlg.show()
        }
    }

    private fun formatReadableSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB (%d B)", bytes / gb, bytes)
            bytes >= mb -> String.format(Locale.US, "%.2f MB (%d B)", bytes / mb, bytes)
            bytes >= kb -> String.format(Locale.US, "%.2f KB (%d B)", bytes / kb, bytes)
            else -> "$bytes B"
        }
    }

    private fun getFileUri(file: File): Uri {
        return try {
            val clazz = Class.forName("androidx.core.content.FileProvider")
            val method = clazz.getMethod("getUriForFile", Context::class.java, String::class.java, File::class.java)
            method.invoke(null, this, "$packageName.fileprovider", file) as Uri
        } catch (e: Exception) {
            try {
                val clazz = Class.forName("android.support.v4.content.FileProvider")
                val method = clazz.getMethod("getUriForFile", Context::class.java, String::class.java, File::class.java)
                method.invoke(null, this, "$packageName.fileprovider", file) as Uri
            } catch (e2: Exception) {
                Uri.fromFile(file)
            }
        }
    }

    private fun getMimeType(file: File): String {
        val ext = file.extension.lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun openFileWith(file: File) {
        try {
            val uri = getFileUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, getMimeType(file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open with..."))
        } catch (e: Exception) {
            Toast.makeText(this, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFiles(files: List<File>) {
        try {
            if (files.size == 1) {
                val f = files[0]
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = getMimeType(f)
                    putExtra(Intent.EXTRA_STREAM, getFileUri(f))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share via"))
            } else {
                val uris = ArrayList<Uri>()
                files.forEach { uris.add(getFileUri(it)) }
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share via"))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to share selected files", Toast.LENGTH_SHORT).show()
        }
    }

    private fun cleanupExpiredTrash() {
        uiScope.launch(Dispatchers.IO) {
            val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
            val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
            if (!trashMetaDir.exists()) return@launch

            val thirtyDaysMs = 30L * 24L * 60L * 60L * 1000L
            val now = System.currentTimeMillis()

            trashMetaDir.listFiles()?.forEach { metaFile ->
                val props = readMetaFile(metaFile)
                val deletedAt = props["deletedAt"]?.toLongOrNull() ?: now
                if (now - deletedAt >= thirtyDaysMs) {
                    val trashId = metaFile.name.removeSuffix(".meta")
                    val targetFile = File(trashFilesDir, trashId)
                    if (targetFile.exists()) {
                        if (targetFile.isDirectory) targetFile.deleteRecursively()
                        else targetFile.delete()
                    }
                    metaFile.delete()
                }
            }
        }
    }

    private fun readMetaFile(metaFile: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (!metaFile.exists()) return map
        metaFile.readLines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                map[line.substring(0, idx)] = line.substring(idx + 1)
            }
        }
        return map
    }

    private fun showTrashManagerDialog() {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (rootView.findViewById<View>(R.id.trashRoot) != null) return

        val trashView = layoutInflater.inflate(R.layout.activity_trash, rootView, false)
        trashView.isClickable = true
        trashView.isFocusable = true
        rootView.addView(trashView)

        val btnBack = trashView.findViewById<android.widget.ImageView>(R.id.btnTrashBack)
        val trashTitle = trashView.findViewById<TextView>(R.id.trashTitle)
        val btnMenu = trashView.findViewById<TextView>(R.id.btnTrashMenu)
        val listLayout = trashView.findViewById<LinearLayout>(R.id.trashListContainer)
        val btnEmpty = trashView.findViewById<LinearLayout>(R.id.btnEmptyTrash)
        val footerBar = btnEmpty.parent as LinearLayout

        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")
        val textPrimary = Color.parseColor("#EFEFEF")
        val textSecondary = Color.parseColor("#BDBDBD")
        val trashDateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val thirtyDaysMs = 30L * 24L * 60L * 60L * 1000L

        val selectedTrashIds = linkedSetOf<String>()

        val btnBulkRestore = TextView(this).apply {
            text = "Restore"
            textSize = 15f
            setTextColor(teal)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val btnBulkDelete = TextView(this).apply {
            text = "Delete"
            textSize = 15f
            setTextColor(Color.parseColor("#FF6B6B"))
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        footerBar.addView(btnBulkRestore)
        footerBar.addView(btnBulkDelete)

        fun updateTrashFooterAndTitle(totalCount: Int) {
            if (selectedTrashIds.isNotEmpty()) {
                trashTitle.text = "Selected: ${selectedTrashIds.size}"
                btnEmpty.visibility = View.GONE
                btnBulkRestore.visibility = View.VISIBLE
                btnBulkDelete.visibility = View.VISIBLE
            } else {
                trashTitle.text = if (totalCount == 0) "Trash" else "Trash ($totalCount)"
                btnEmpty.visibility = View.VISIBLE
                btnBulkRestore.visibility = View.GONE
                btnBulkDelete.visibility = View.GONE
            }
        }

        btnBack.setOnClickListener {
            if (selectedTrashIds.isNotEmpty()) {
                selectedTrashIds.clear()
                showTrashManagerDialog()
                rootView.removeView(trashView)
            } else {
                rootView.removeView(trashView)
                loadBothPanels()
            }
        }

        lateinit var refreshTrashList: () -> Unit

        refreshTrashList = {
            listLayout.removeAllViews()
            val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
            val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
            val metaFiles = trashMetaDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

            val validIds = metaFiles.map { it.name.removeSuffix(".meta") }.toSet()
            selectedTrashIds.retainAll(validIds)

            updateTrashFooterAndTitle(metaFiles.size)

            if (metaFiles.isEmpty()) {
                val emptyMsg = TextView(this).apply {
                    text = "Trash is empty.\nDeleted items stay here for 30 days."
                    textSize = 15f
                    setTextColor(textSecondary)
                    gravity = Gravity.CENTER
                    setPadding(0, (80 * density).toInt(), 0, 0)
                }
                listLayout.addView(emptyMsg)
            } else {
                val now = System.currentTimeMillis()
                for (metaFile in metaFiles) {
                    val props = readMetaFile(metaFile)
                    val trashId = metaFile.name.removeSuffix(".meta")
                    val trashedFile = File(trashFilesDir, trashId)
                    val origPath = props["originalPath"] ?: continue
                    val origName = props["originalName"] ?: trashedFile.name
                    val deletedAt = props["deletedAt"]?.toLongOrNull() ?: now
                    val daysLeft = ((thirtyDaysMs - (now - deletedAt)) / (24L * 60L * 60L * 1000L)).coerceAtLeast(0)
                    val dateStr = trashDateFmt.format(Date(deletedAt))
                    val isSelected = selectedTrashIds.contains(trashId)

                    val infoMetric = if (trashedFile.isDirectory) {
                        val count = trashedFile.listFiles()?.size ?: 0
                        if (count == 1) "1 item" else "$count items"
                    } else {
                        formatCompactSize(trashedFile.length())
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isLongClickable = true
                        val vPad = (12 * density).toInt()
                        val hPad = (8 * density).toInt()
                        setPadding(hPad, vPad, hPad, vPad)
                        setBackgroundColor(if (isSelected) Color.rgb(55, 91, 90) else Color.TRANSPARENT)
                    }

                    val origExt = File(origName).extension.lowercase()
                    val isImgOrVid = !trashedFile.isDirectory && origExt in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "avi", "mov", "3gp")
                    val isApk = !trashedFile.isDirectory && origExt == "apk"
                    val iconIv = android.widget.ImageView(this).apply {
                        val iconSize = (34 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        when {
                            trashedFile.isDirectory -> {
                                val pad = (4 * density).toInt()
                                setPadding(pad, pad, pad, pad)
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                setImageResource(R.drawable.folder)
                                setColorFilter(teal)
                            }
                            isImgOrVid && trashedFile.exists() -> {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                Glide.with(this@MainActivity)
                                .load(trashedFile)
                                .override(96, 96)
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .placeholder(R.drawable.files)
                                .error(R.drawable.files)
                                .into(this)
                            }
                            isApk && trashedFile.exists() -> {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                setImageResource(R.drawable.files)
                                loadThumbnailAsync(trashedFile, isApk = true, isVideo = false, imageView = this)
                            }
                            else -> {
                                val pad = (4 * density).toInt()
                                setPadding(pad, pad, pad, pad)
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                setImageResource(R.drawable.files)
                                setColorFilter(textSecondary)
                            }
                        }
                    }

                    val col = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((10 * density).toInt(), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val nameTv = TextView(this).apply {
                        text = origName
                        textSize = 16f
                        setTextColor(if (isSelected) Color.WHITE else textPrimary)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }

                    val metaTv = TextView(this).apply {
                        text = "$infoMetric | $dateStr | $daysLeft days remaining"
                        textSize = 12f
                        setTextColor(if (isSelected) Color.rgb(210, 235, 235) else textSecondary)
                    }

                    col.addView(nameTv)
                    col.addView(metaTv)
                    row.addView(iconIv)
                    row.addView(col)

                    row.setOnClickListener {
                        if (selectedTrashIds.isNotEmpty()) {
                            if (selectedTrashIds.contains(trashId)) selectedTrashIds.remove(trashId)
                            else selectedTrashIds.add(trashId)
                            refreshTrashList()
                        } else {
                            showTrashItemActionDialog(trashedFile, metaFile, origPath, origName) {
                                refreshTrashList()
                            }
                        }
                    }

                    row.setOnLongClickListener {
                        if (selectedTrashIds.contains(trashId)) selectedTrashIds.remove(trashId)
                        else selectedTrashIds.add(trashId)
                        refreshTrashList()
                        true
                    }

                    listLayout.addView(row)
                }
            }
        }

        btnBulkRestore.setOnClickListener {
            val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
            val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
            val toRestore = selectedTrashIds.toList()
            selectedTrashIds.clear()
            for (id in toRestore) {
                val metaFile = File(trashMetaDir, "$id.meta")
                val trashedFile = File(trashFilesDir, id)
                val props = readMetaFile(metaFile)
                val origPath = props["originalPath"] ?: continue
                val origName = props["originalName"] ?: trashedFile.name
                restoreTrashedItem(trashedFile, metaFile, origPath, origName)
            }
            refreshTrashList()
        }

        btnBulkDelete.setOnClickListener {
            val count = selectedTrashIds.size
            val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Delete Permanently")
            .setMessage("Permanently delete $count selected item(s)?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
                val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
                for (id in selectedTrashIds.toList()) {
                    val tf = File(trashFilesDir, id)
                    if (tf.exists()) {
                        if (tf.isDirectory) tf.deleteRecursively() else tf.delete()
                    }
                    File(trashMetaDir, "$id.meta").delete()
                }
                selectedTrashIds.clear()
                refreshTrashList()
                Toast.makeText(this, "Deleted $count item(s)", Toast.LENGTH_SHORT).show()
            }
            .create()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#FF6B6B"))
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            }
            dlg.show()
        }

        fun confirmEmptyTrash() {
            val trashFilesDir = File(internalRoot, ".Gonofiles/Trash/Files")
            val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
            val count = trashMetaDir.listFiles()?.size ?: 0
            if (count == 0) {
                Toast.makeText(this, "Trash is already empty", Toast.LENGTH_SHORT).show()
                return
            }
            val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Empty trash")
            .setMessage("Permanently delete all $count item(s) in Trash?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Empty trash") { _, _ ->
                trashFilesDir.deleteRecursively()
                trashMetaDir.deleteRecursively()
                selectedTrashIds.clear()
                refreshTrashList()
                Toast.makeText(this, "Trash emptied", Toast.LENGTH_SHORT).show()
            }
            .create()
            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#FF6B6B"))
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            }
            dlg.show()
        }

        btnEmpty.setOnClickListener { confirmEmptyTrash() }
        btnMenu.setOnClickListener {
            val trashMetaDir = File(internalRoot, ".Gonofiles/Trash/Metadata")
            val metaFiles = trashMetaDir.listFiles() ?: emptyArray()
            val popup = PopupMenu(this, btnMenu)
            if (metaFiles.isNotEmpty()) {
                val allSelected = metaFiles.all { selectedTrashIds.contains(it.name.removeSuffix(".meta")) }
                popup.menu.add(if (allSelected) "Deselect all" else "Select all")
            }
            popup.menu.add("Empty trash")
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "Select all" -> {
                        metaFiles.forEach { selectedTrashIds.add(it.name.removeSuffix(".meta")) }
                        refreshTrashList()
                        true
                    }
                    "Deselect all" -> {
                        selectedTrashIds.clear()
                        refreshTrashList()
                        true
                    }
                    "Empty trash" -> {
                        confirmEmptyTrash()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        refreshTrashList()
    }

    private fun showTrashItemActionDialog(
        trashedFile: File,
        metaFile: File,
        origPath: String,
        origName: String,
        onUpdated: () -> Unit
    ) {
        val options = arrayOf("Restore", "Delete Permanently")
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle(origName)
        .setItems(options) { _, which ->
            when (which) {
                0 -> {
                    restoreTrashedItem(trashedFile, metaFile, origPath, origName)
                    onUpdated()
                }
                1 -> {
                    if (trashedFile.exists()) {
                        if (trashedFile.isDirectory) trashedFile.deleteRecursively()
                        else trashedFile.delete()
                    }
                    metaFile.delete()
                    onUpdated()
                    Toast.makeText(this, "Permanently deleted", Toast.LENGTH_SHORT).show()
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
    }

    private fun restoreTrashedItem(
        trashedFile: File,
        metaFile: File,
        origPath: String,
        origName: String
    ) {
        if (!trashedFile.exists()) {
            metaFile.delete()
            Toast.makeText(this, "Trashed file missing", Toast.LENGTH_SHORT).show()
            return
        }

        val origParent = File(origPath).parentFile
        if (origParent != null && origParent.exists() && origParent.isDirectory) {
            val safeDest = getUniqueDestination(origParent, origName)
            val restored = if (trashedFile.renameTo(safeDest)) true
            else copyRecursively(trashedFile, safeDest) && trashedFile.deleteRecursively()

            if (restored) {
                metaFile.delete()
                loadBothPanels()
                Toast.makeText(this, "Restored to ${origParent.absolutePath}", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Restore failed", Toast.LENGTH_SHORT).show()
            }
        } else {
            val activeDir = getCurrentFolder()
            val choices = arrayOf(
                "Restore to current active panel (${activeDir.name})",
                "Recreate original folder (${origParent?.absolutePath ?: "/"})"
            )
            AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Original folder not found")
            .setItems(choices) { _, choice ->
                val targetDir = if (choice == 0) activeDir else {
                    origParent?.mkdirs()
                    origParent ?: activeDir
                }
                val safeDest = getUniqueDestination(targetDir, origName)
                val restored = if (trashedFile.renameTo(safeDest)) true
                else copyRecursively(trashedFile, safeDest) && trashedFile.deleteRecursively()

                if (restored) {
                    metaFile.delete()
                    loadBothPanels()
                    Toast.makeText(this, "Restored to ${targetDir.absolutePath}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        }
    }

    private fun getSavedBookmarks(): MutableSet<String> {
        val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
        return prefs.getStringSet("bookmarks", emptySet())?.toMutableSet() ?: mutableSetOf()
    }

    private fun saveBookmarks(bookmarks: Set<String>) {
        val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("bookmarks", bookmarks).apply()
    }

    private fun toggleBookmarks(files: List<File>, panel: Int) {
        val bookmarks = getSavedBookmarks()
        var added = 0
        var removed = 0

        for (f in files) {
            val path = f.absolutePath
            if (bookmarks.contains(path)) {
                bookmarks.remove(path)
                removed++
            } else {
                bookmarks.add(path)
                added++
            }
        }

        saveBookmarks(bookmarks)
        getSelectedItems(panel).clear()
        setPanelSelectionMode(panel, false)
        refreshActiveFileList()
        updateSelectionToolbar()

        val msg = when {
            added > 0 && removed == 0 -> "Added $added bookmark(s)"
            removed > 0 && added == 0 -> "Removed $removed bookmark(s)"
            else -> "Updated bookmarks (+$added / -$removed)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarksDialog() {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (rootView.findViewById<View>(R.id.bookmarksRoot) != null) return

        val bmView = layoutInflater.inflate(R.layout.activity_bookmarks, rootView, false)
        bmView.isClickable = true
        bmView.isFocusable = true
        rootView.addView(bmView)

        val btnBack = bmView.findViewById<android.widget.ImageView>(R.id.btnBookmarksBack)
        val tabFolders = bmView.findViewById<TextView>(R.id.tabFolders)
        val tabFiles = bmView.findViewById<TextView>(R.id.tabFiles)
        val tabApps = bmView.findViewById<TextView>(R.id.tabApps)
        val tabImages = bmView.findViewById<TextView>(R.id.tabImages)
        val itemsContainer = bmView.findViewById<LinearLayout>(R.id.bookmarksListContainer)

        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")
        val gray = Color.parseColor("#BDBDBD")
        val categories = arrayOf("Folders", "Files", "Apps", "Images")
        val tabs = listOf(tabFolders, tabFiles, tabApps, tabImages)
        val selectedBookmarks = linkedSetOf<String>()

        lateinit var renderCategory: (Int) -> Unit

        btnBack.setOnClickListener {
            if (selectedBookmarks.isNotEmpty()) {
                selectedBookmarks.clear()
                renderCategory(tabs.indexOfFirst { it.currentTextColor == teal }.coerceAtLeast(0))
            } else {
                rootView.removeView(bmView)
            }
        }

        fun confirmRemoveBookmarks(catIndex: Int) {
            if (selectedBookmarks.isEmpty()) return
            val targetLabel = if (selectedBookmarks.size == 1) {
                File(selectedBookmarks.first()).name
            } else {
                "${selectedBookmarks.size}"
            }

            val dlg = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setMessage("Want to remove $targetLabel bookmark?")
            .setNegativeButton("No", null)
            .setPositiveButton("Yes") { _, _ ->
                val updated = getSavedBookmarks()
                updated.removeAll(selectedBookmarks)
                saveBookmarks(updated)
                selectedBookmarks.clear()
                renderCategory(catIndex)
            }
            .create()

            dlg.setOnShowListener {
                dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            }
            dlg.show()
        }

        renderCategory = { catIndex ->
            tabs.forEachIndexed { idx, tv ->
                tv.setTextColor(if (idx == catIndex) teal else gray)
                tv.setTypeface(null, if (idx == catIndex) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            itemsContainer.removeAllViews()

            val allSaved = getSavedBookmarks()
            .map { File(it) }
            .filter { it.exists() }

            val filtered = allSaved.filter { file ->
                val ext = file.extension.lowercase()
                when (catIndex) {
                    0 -> file.isDirectory
                    1 -> !file.isDirectory && ext != "apk" && ext !in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                    2 -> !file.isDirectory && ext == "apk"
                    3 -> !file.isDirectory && ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                    else -> false
                }
            }

            val validPaths = filtered.map { it.absolutePath }.toSet()
            selectedBookmarks.retainAll(validPaths)

            if (filtered.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "No bookmarks in ${categories[catIndex]}"
                    textSize = 15f
                    setTextColor(gray)
                    gravity = Gravity.CENTER
                    setPadding(0, (80 * density).toInt(), 0, 0)
                }
                itemsContainer.addView(emptyTv)
            } else {
                for (file in filtered) {
                    val path = file.absolutePath
                    val isSelected = selectedBookmarks.contains(path)

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isLongClickable = true
                        val vPad = (12 * density).toInt()
                        val hPad = (8 * density).toInt()
                        setPadding(hPad, vPad, hPad, vPad)
                        setBackgroundColor(if (isSelected) Color.rgb(55, 91, 90) else Color.TRANSPARENT)
                    }

                    val ext = file.extension.lowercase()
                    val isImgOrVid = !file.isDirectory && ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "mp4", "mkv", "avi", "mov", "3gp")
                    val isApk = !file.isDirectory && ext == "apk"

                    val iconIv = android.widget.ImageView(this).apply {
                        val iconSize = (34 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                        when {
                            catIndex == 0 || file.isDirectory -> {
                                val pad = (4 * density).toInt()
                                setPadding(pad, pad, pad, pad)
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                setImageResource(R.drawable.folder)
                                setColorFilter(teal)
                            }
                            isImgOrVid -> {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                Glide.with(this@MainActivity)
                                .load(file)
                                .override(96, 96)
                                .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                                .placeholder(R.drawable.files)
                                .error(R.drawable.files)
                                .into(this)
                            }
                            isApk -> {
                                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                setImageResource(R.drawable.files)
                                loadThumbnailAsync(file, isApk = true, isVideo = false, imageView = this)
                            }
                            else -> {
                                val pad = (4 * density).toInt()
                                setPadding(pad, pad, pad, pad)
                                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                setImageResource(R.drawable.files)
                                setColorFilter(gray)
                            }
                        }
                    }

                    val col = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((10 * density).toInt(), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val nameTv = TextView(this).apply {
                        text = file.name
                        textSize = 16f
                        setTextColor(if (isSelected) Color.WHITE else Color.parseColor("#EFEFEF"))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                    }

                    val pathTv = TextView(this).apply {
                        text = path
                        textSize = 12f
                        setTextColor(if (isSelected) Color.rgb(210, 235, 235) else gray)
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.START
                    }

                    col.addView(nameTv)
                    col.addView(pathTv)
                    row.addView(iconIv)
                    row.addView(col)

                    row.setOnClickListener {
                        if (selectedBookmarks.isNotEmpty()) {
                            if (selectedBookmarks.contains(path)) selectedBookmarks.remove(path)
                            else selectedBookmarks.add(path)
                            renderCategory(catIndex)
                        } else {
                            rootView.removeView(bmView)
                            if (file.isDirectory) {
                                navigateTo(file)
                            } else if (file.parentFile != null) {
                                navigateTo(file.parentFile!!)
                            }
                        }
                    }

                    row.setOnLongClickListener {
                        if (!selectedBookmarks.contains(path)) {
                            selectedBookmarks.add(path)
                            renderCategory(catIndex)
                        } else {
                            confirmRemoveBookmarks(catIndex)
                        }
                        true
                    }

                    itemsContainer.addView(row)
                }
            }
        }

        tabFolders.setOnClickListener {
            selectedBookmarks.clear()
            renderCategory(0)
        }
        tabFiles.setOnClickListener {
            selectedBookmarks.clear()
            renderCategory(1)
        }
        tabApps.setOnClickListener {
            selectedBookmarks.clear()
            renderCategory(2)
        }
        tabImages.setOnClickListener {
            selectedBookmarks.clear()
            renderCategory(3)
        }

        renderCategory(0)
    }

    // ZIP COMPRESS, EXTRACT, VIEWER & SEARCH
    private fun showCompressDialog(files: List<File>, panel: Int) {
        val currentDir = if (panel == 0) leftFolder else rightFolder
        var chosenOutputDir = currentDir
        val defaultName = "${currentDir.name}.zip"

        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, 0)
        }

        val nameInput = EditText(this).apply {
            setText(defaultName)
            setTextColor(Color.parseColor("#EFEFEF"))
            textSize = 16f
            requestFocus()
        }
        container.addView(nameInput)

        val destLabel = TextView(this).apply {
            text = "Destination:\n${chosenOutputDir.absolutePath}/"
            textSize = 13f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, (14 * density).toInt(), 0, (4 * density).toInt())
        }
        container.addView(destLabel)

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Compress")
        .setView(container)
        .setNegativeButton("Cancel", null)
        .setNeutralButton("Select Folder", null)
        .setPositiveButton("OK", null)
        .create()

        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        dialog.setOnShowListener {
            val teal = Color.parseColor("#62B1B0")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(teal)

            val dotIndex = defaultName.lastIndexOf('.')
            if (dotIndex > 0) {
                nameInput.setSelection(0, dotIndex)
            } else {
                nameInput.selectAll()
            }

            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                showFolderPickerDialog(chosenOutputDir) { pickedDir ->
                    chosenOutputDir = pickedDir
                    destLabel.text = "Destination:\n${chosenOutputDir.absolutePath}/"
                }
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                var zipName = nameInput.text.toString().trim()
                if (zipName.isEmpty()) {
                    nameInput.error = "Enter archive name"
                    return@setOnClickListener
                }
                if (!zipName.lowercase().endsWith(".zip")) zipName += ".zip"
                val outFile = getUniqueDestination(chosenOutputDir, zipName)
                dialog.dismiss()
                performZipCompress(files, outFile, panel)
            }
        }
        dialog.show()
    }

    private fun showFolderPickerDialog(initialDir: File, onFolderPicked: (File) -> Unit) {
        var browseDir = if (initialDir.exists() && initialDir.isDirectory) initialDir else internalRoot
        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")
        val textPrimary = Color.parseColor("#EFEFEF")
        val textSecondary = Color.parseColor("#BDBDBD")

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#353434"))
            val hPad = (16 * density).toInt()
            setPadding(hPad, (10 * density).toInt(), hPad, (6 * density).toInt())
        }

        val currentFolderTitle = TextView(this).apply {
            text = browseDir.name
            textSize = 16f
            setTextColor(textPrimary)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        val currentFolderPath = TextView(this).apply {
            text = browseDir.absolutePath
            textSize = 12f
            setTextColor(teal)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        wrapper.addView(currentFolderTitle)
        wrapper.addView(currentFolderPath)

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (320 * density).toInt()
            )
        }
        val folderList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(folderList)
        wrapper.addView(scroll)

        lateinit var renderFolders: (File) -> Unit
        renderFolders = { dir ->
            browseDir = dir
            currentFolderTitle.text = dir.name.ifEmpty { "/" }
            currentFolderPath.text = dir.absolutePath
            folderList.removeAllViews()

            val parent = dir.parentFile
            if (parent != null && dir.absolutePath != systemRoot.absolutePath) {
                val upRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val p = (10 * density).toInt()
                    setPadding(0, p, 0, p)
                    setOnClickListener { renderFolders(parent) }
                }
                val upIcon = android.widget.ImageView(this).apply {
                    val sz = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    setImageResource(R.drawable.folder)
                    setColorFilter(teal)
                }
                val upText = TextView(this).apply {
                    text = "  .."
                    textSize = 15f
                    setTextColor(teal)
                }
                upRow.addView(upIcon)
                upRow.addView(upText)
                folderList.addView(upRow)
            }

            val subFolders = (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name != ".Gonofiles" }
            .sortedBy { it.name.lowercase() }

            if (subFolders.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "No subfolders"
                    textSize = 13f
                    setTextColor(textSecondary)
                    setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
                }
                folderList.addView(emptyTv)
            }

            for (sub in subFolders) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val p = (10 * density).toInt()
                    setPadding(0, p, 0, p)
                    setOnClickListener { renderFolders(sub) }
                }
                val iconIv = android.widget.ImageView(this).apply {
                    val sz = (24 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    setImageResource(R.drawable.folder)
                    setColorFilter(teal)
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((10 * density).toInt(), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(this).apply {
                    text = sub.name
                    textSize = 15f
                    setTextColor(textPrimary)
                }
                val tagTv = TextView(this).apply {
                    text = "<DIR>"
                    textSize = 11f
                    setTextColor(textSecondary)
                }
                col.addView(nameTv)
                col.addView(tagTv)
                row.addView(iconIv)
                row.addView(col)
                folderList.addView(row)
            }
        }

        val pickerDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Select destination folder")
        .setView(wrapper)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("OK") { _, _ ->
            onFolderPicked(browseDir)
        }
        .create()

        pickerDialog.setOnShowListener {
            pickerDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            pickerDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }

        renderFolders(browseDir)
        pickerDialog.show()
    }

    private fun performZipCompress(files: List<File>, outZipFile: File, panel: Int) {
        Toast.makeText(this, "Compressing...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    java.util.zip.ZipOutputStream(java.io.BufferedOutputStream(outZipFile.outputStream())).use { zos ->
                        fun addEntry(file: File, entryPath: String) {
                            if (file.isDirectory) {
                                val dirName = if (entryPath.endsWith("/")) entryPath else "$entryPath/"
                                zos.putNextEntry(java.util.zip.ZipEntry(dirName))
                                zos.closeEntry()
                                file.listFiles()?.forEach { child ->
                                    addEntry(child, "$dirName${child.name}")
                                }
                            } else {
                                zos.putNextEntry(java.util.zip.ZipEntry(entryPath))
                                file.inputStream().use { input -> input.copyTo(zos, bufferSize = 65536) }
                                zos.closeEntry()
                            }
                        }
                        for (f in files) {
                            addEntry(f, f.name)
                        }
                    }
                    true
                } catch (e: Exception) {
                    outZipFile.delete()
                    false
                }
            }

            if (ok) {
                recentHighlightedPaths.clear()
                recentHighlightedPaths.add(outZipFile.absolutePath)
            }
            getSelectedItems(panel).clear()
            setPanelSelectionMode(panel, false)
            loadBothPanels()
            Toast.makeText(
                this@MainActivity,
                if (ok) "Created ${outZipFile.name}" else "Compression failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showExtractDialog(zipFile: File, panel: Int) {
        val currentDir = if (panel == 0) leftFolder else rightFolder
        val subFolder = File(currentDir, zipFile.nameWithoutExtension)
        val choices = arrayOf(
            "Extract to ${subFolder.name}/",
            "Extract here (${currentDir.name}/)"
        )
        AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Extract ${zipFile.name}")
        .setItems(choices) { _, which ->
            val destDir = if (which == 0) getUniqueDestination(currentDir, zipFile.nameWithoutExtension) else currentDir
            performZipExtract(zipFile, destDir, panel)
        }
        .setNegativeButton("Cancel", null)
        .show()
    }

    private fun performZipExtract(zipFile: File, destDir: File, panel: Int) {
        Toast.makeText(this, "Extracting...", Toast.LENGTH_SHORT).show()
        uiScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    if (!destDir.exists()) destDir.mkdirs()
                    val canonicalDest = destDir.canonicalPath + File.separator
                    java.util.zip.ZipInputStream(java.io.BufferedInputStream(zipFile.inputStream())).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val outFile = File(destDir, entry.name)
                            if (!outFile.canonicalPath.startsWith(canonicalDest)) {
                                throw SecurityException("Invalid zip entry")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { out -> zis.copyTo(out, bufferSize = 65536) }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    true
                } catch (e: Exception) {
                    false
                }
            }

            getSelectedItems(panel).clear()
            setPanelSelectionMode(panel, false)
            loadBothPanels()
            Toast.makeText(
                this@MainActivity,
                if (ok) "Extracted to ${destDir.name}" else "Extraction failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun openZipViewer(zipFile: File) {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (rootView.findViewById<View>(R.id.zipRoot) != null) return

        val zipView = layoutInflater.inflate(R.layout.activity_zip, rootView, false)
        zipView.isClickable = true
        zipView.isFocusable = true
        rootView.addView(zipView)

        val btnBack = zipView.findViewById<android.widget.ImageView>(R.id.btnZipBack)
        val titleTv = zipView.findViewById<TextView>(R.id.zipArchiveTitle)
        val pathTv = zipView.findViewById<TextView>(R.id.zipInternalPath)
        val btnExtractAll = zipView.findViewById<TextView>(R.id.btnZipExtractAll)
        val container = zipView.findViewById<LinearLayout>(R.id.zipEntriesContainer)

        titleTv.text = zipFile.name
        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")
        var currentInternalDir = ""

        data class ZipNode(val name: String, val fullPath: String, val isDir: Boolean, val size: Long, val time: Long)
        val allEntries = mutableListOf<java.util.zip.ZipEntry>()

        try {
            java.util.zip.ZipFile(zipFile).use { zf ->
                val entries = zf.entries()
                while (entries.hasMoreElements()) {
                    allEntries.add(entries.nextElement())
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot read ZIP archive", Toast.LENGTH_SHORT).show()
            rootView.removeView(zipView)
            return
        }

        btnExtractAll.setOnClickListener {
            rootView.removeView(zipView)
            showExtractDialog(zipFile, activePanel)
        }

        lateinit var renderZipDir: (String) -> Unit

        btnBack.setOnClickListener {
            if (currentInternalDir.isNotEmpty()) {
                val trimmed = currentInternalDir.trimEnd('/')
                val parent = if (trimmed.contains('/')) trimmed.substringBeforeLast('/') + "/" else ""
                renderZipDir(parent)
            } else {
                rootView.removeView(zipView)
            }
        }

        renderZipDir = { dirPrefix ->
            currentInternalDir = dirPrefix
            pathTv.text = if (dirPrefix.isEmpty()) "/" else "/$dirPrefix"
            container.removeAllViews()

            if (dirPrefix.isNotEmpty()) {
                val upTv = TextView(this).apply {
                    text = ".."
                    textSize = 15f
                    setTextColor(teal)
                    val pad = (10 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                    setOnClickListener {
                        val trimmed = dirPrefix.trimEnd('/')
                        val parent = if (trimmed.contains('/')) trimmed.substringBeforeLast('/') + "/" else ""
                        renderZipDir(parent)
                    }
                }
                container.addView(upTv)
            }

            val nodesMap = linkedMapOf<String, ZipNode>()
            for (entry in allEntries) {
                val name = entry.name
                if (!name.startsWith(dirPrefix) || name == dirPrefix) continue
                val remainder = name.removePrefix(dirPrefix)
                val slashIdx = remainder.indexOf('/')
                if (slashIdx >= 0) {
                    val folderName = remainder.substring(0, slashIdx)
                    val fullFolderPath = "$dirPrefix$folderName/"
                    if (!nodesMap.containsKey(fullFolderPath)) {
                        nodesMap[fullFolderPath] = ZipNode(folderName, fullFolderPath, true, 0L, entry.time)
                    }
                } else {
                    nodesMap[name] = ZipNode(remainder, name, entry.isDirectory, entry.size, entry.time)
                }
            }

            val sortedNodes = nodesMap.values.sortedWith(
                compareByDescending<ZipNode> { it.isDir }.thenBy { it.name.lowercase() }
            )

            if (sortedNodes.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "Empty archive folder"
                    textSize = 14f
                    setTextColor(Color.parseColor("#BDBDBD"))
                    setPadding(12, 24, 12, 24)
                }
                container.addView(emptyTv)
            }

            for (node in sortedNodes) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val pad = (10 * density).toInt()
                    setPadding(pad, pad, pad, pad)
                }

                val iconIv = android.widget.ImageView(this).apply {
                    val sz = (26 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(sz, sz)
                    if (node.isDir) {
                        setImageResource(R.drawable.folder)
                        setColorFilter(teal)
                    } else {
                        setImageResource(R.drawable.files)
                        setColorFilter(Color.parseColor("#BDBDBD"))
                    }
                }

                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((10 * density).toInt(), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameTv = TextView(this).apply {
                    text = node.name
                    textSize = 15f
                    setTextColor(Color.parseColor("#EFEFEF"))
                }

                val subTv = TextView(this).apply {
                    text = if (node.isDir) "Folder" else "${formatCompactSize(node.size)}  •  ${dateFormatter.format(Date(node.time))}"
                    textSize = 11f
                    setTextColor(Color.parseColor("#BDBDBD"))
                }

                col.addView(nameTv)
                col.addView(subTv)
                row.addView(iconIv)
                row.addView(col)

                row.setOnClickListener {
                    if (node.isDir) {
                        renderZipDir(node.fullPath)
                    } else {
                        val targetDir = getCurrentFolder()
                        val outFile = getUniqueDestination(targetDir, node.name)
                        uiScope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                try {
                                    java.util.zip.ZipFile(zipFile).use { zf ->
                                        val ze = zf.getEntry(node.fullPath)
                                        if (ze != null) {
                                            zf.getInputStream(ze).use { input ->
                                                outFile.outputStream().use { out -> input.copyTo(out, bufferSize = 65536) }
                                            }
                                        }
                                    }
                                    true
                                } catch (e: Exception) {
                                    false
                                }
                            }
                            if (ok) {
                                loadBothPanels()
                                Toast.makeText(this@MainActivity, "Extracted ${node.name} to ${targetDir.name}/", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "Failed to extract ${node.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                container.addView(row)
            }
        }

        renderZipDir("")
    }

    // SEARCH BY NAME
    private fun showSearchDialog() {
        val searchRoot = getCurrentFolder()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val input = EditText(this).apply {
            hint = "Search in ${searchRoot.name}..."
            setHintTextColor(Color.parseColor("#9E9E9E"))
            setTextColor(Color.parseColor("#EFEFEF"))
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * density).toInt(), pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Search")
        .setView(container)
        .setNegativeButton("Cancel", null)
        .setPositiveButton("Search") { _, _ ->
            val query = input.text.toString().trim().lowercase()
            if (query.isNotEmpty()) performSearch(searchRoot, query)
        }
        .create()

        dialog.setOnShowListener {
            val teal = Color.parseColor("#62B1B0")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        dialog.show()
    }

    private fun performSearch(rootDir: File, query: String) {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val teal = Color.parseColor("#62B1B0")

        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (14 * density).toInt(), pad, (8 * density).toInt())
        }
        val statusTv = TextView(this).apply {
            text = "Scanning ${rootDir.name}..."
            textSize = 14f
            setTextColor(Color.parseColor("#EFEFEF"))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val countTv = TextView(this).apply {
            text = "Matches found: 0"
            textSize = 12f
            setTextColor(Color.parseColor("#BDBDBD"))
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        }
        val progressBar = android.widget.ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(teal)
        }
        progressContainer.addView(statusTv)
        progressContainer.addView(countTv)
        progressContainer.addView(progressBar)

        var searchJob: Job? = null
        val loadingDialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Searching \"$query\"")
        .setView(progressContainer)
        .setCancelable(false)
        .setNegativeButton("CANCEL") { _, _ ->
            searchJob?.cancel()
        }
        .create()

        loadingDialog.setOnShowListener {
            loadingDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
        }
        loadingDialog.show()

        searchJob = uiScope.launch {
            var canceled = false
            val matches = try {
                withContext(Dispatchers.IO) {
                    val found = mutableListOf<File>()
                    var lastUiMs = 0L
                    fun scan(dir: File) {
                        if (!isActive || found.size >= 500) return
                        val now = System.currentTimeMillis()
                        if (now - lastUiMs >= 80L) {
                            lastUiMs = now
                            val folderName = dir.name
                            val foundCount = found.size
                            runOnUiThread {
                                statusTv.text = "Scanning: $folderName"
                                countTv.text = "Matches found: $foundCount"
                            }
                        }
                        val children = dir.listFiles() ?: return
                        for (child in children) {
                            if (!isActive || found.size >= 500) return
                            if (child.name == ".Gonofiles") continue
                            if (child.name.lowercase().contains(query)) {
                                found.add(child)
                            }
                            if (child.isDirectory) scan(child)
                        }
                    }
                    scan(rootDir)
                    // Sort all folders at the top, then alphabetically by name
                    found.sortedWith(
                        compareByDescending<File> { it.isDirectory }
                        .thenBy { it.name.lowercase() }
                    )
                }
            } catch (_: Exception) {
                canceled = true
                emptyList()
            } finally {
                if (loadingDialog.isShowing) loadingDialog.dismiss()
            }

            if (canceled || !isActive) return@launch

            if (matches.isEmpty()) {
                Toast.makeText(this@MainActivity, "No matches found for \"$query\"", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
            val videoExts = setOf("mp4", "mkv", "avi", "mov", "3gp")

            val recycler = RecyclerView(this@MainActivity).apply {
                layoutManager = LinearLayoutManager(this@MainActivity)
                itemAnimator = null
                setHasFixedSize(true)
                setItemViewCacheSize(20)
                val hPad = (16 * density).toInt()
                val vPad = (8 * density).toInt()
                setPadding(hPad, vPad, hPad, vPad)
                clipToPadding = false
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (420 * density).toInt()
                )
            }

            val resDialog = AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle("Results (${matches.size})")
            .setView(recycler)
            .setPositiveButton("Close", null)
            .create()

            resDialog.setOnShowListener {
                resDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
            }

            recycler.adapter = object : RecyclerView.Adapter<FileRowViewHolder>() {
                override fun getItemCount(): Int = matches.size

                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): FileRowViewHolder {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        val vp = (8 * density).toInt()
                        setPadding(0, vp, 0, vp)
                        layoutParams = RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            (54 * density).toInt()
                        )
                    }
                    val iconIv = android.widget.ImageView(this@MainActivity).apply {
                        val sz = (32 * density).toInt()
                        layoutParams = LinearLayout.LayoutParams(sz, sz)
                    }
                    val col = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((10 * density).toInt(), 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val nameTv = TextView(this@MainActivity).apply {
                        textSize = 15f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        setTextColor(Color.parseColor("#EFEFEF"))
                    }
                    val pathTv = TextView(this@MainActivity).apply {
                        textSize = 11f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.START
                        setTextColor(Color.parseColor("#BDBDBD"))
                    }
                    col.addView(nameTv)
                    col.addView(pathTv)
                    row.addView(iconIv)
                    row.addView(col)
                    return FileRowViewHolder(row, iconIv, nameTv, pathTv)
                }

                override fun onBindViewHolder(holder: FileRowViewHolder, position: Int) {
                    val match = matches[position]
                    val iconIv = holder.iconHolder
                    val ext = if (match.isDirectory) "" else match.extension.lowercase()
                    val isImage = !match.isDirectory && ext in imageExts
                    val isVideo = !match.isDirectory && ext in videoExts
                    val isApk = !match.isDirectory && ext == "apk"

                    Glide.with(this@MainActivity).clear(iconIv)
                    iconIv.tag = null
                    iconIv.clearColorFilter()

                    if (isImage || isVideo) {
                        iconIv.setPadding(0, 0, 0, 0)
                        iconIv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        Glide.with(this@MainActivity)
                        .load(match)
                        .override(96, 96)
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .placeholder(R.drawable.files)
                        .error(R.drawable.files)
                        .into(iconIv)
                    } else if (isApk) {
                        iconIv.setPadding(0, 0, 0, 0)
                        iconIv.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        iconIv.setImageResource(R.drawable.files)
                        loadThumbnailAsync(match, isApk = true, isVideo = false, imageView = iconIv)
                    } else {
                        val p = (3 * density).toInt()
                        iconIv.setPadding(p, p, p, p)
                        iconIv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        if (match.isDirectory) {
                            iconIv.setImageResource(R.drawable.folder)
                            iconIv.setColorFilter(teal)
                        } else {
                            iconIv.setImageResource(R.drawable.files)
                            iconIv.setColorFilter(Color.parseColor("#BDBDBD"))
                        }
                    }

                    holder.nameView.text = match.name
                    holder.dateView.text = match.parent ?: "/"

                    holder.rowLayout.setOnClickListener {
                        resDialog.dismiss()
                        if (match.isDirectory) navigateTo(match)
                        else match.parentFile?.let { navigateTo(it) }
                    }
                }
            }

            resDialog.show()
        }
    }

    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(120)

    private fun loadThumbnailAsync(
        file: File,
        isApk: Boolean,
        isVideo: Boolean,
        imageView: android.widget.ImageView
    ) {
        val key = "${file.absolutePath}_${file.lastModified()}"
        imageView.tag = key
        thumbCache.get(key)?.let {
            imageView.clearColorFilter()
            imageView.setImageBitmap(it)
            return
        }

        thumbExecutor.execute {
            if (imageView.tag != key) return@execute
            val bmp: android.graphics.Bitmap? = try {
                if (isApk) {
                    val pm = packageManager
                    @Suppress("DEPRECATION")
                    val pkgInfo = pm.getPackageArchiveInfo(file.absolutePath, 0)
                    pkgInfo?.applicationInfo?.let { appInfo ->
                        appInfo.sourceDir = file.absolutePath
                        appInfo.publicSourceDir = file.absolutePath
                        val drawable = appInfo.loadIcon(pm)
                        val w = drawable.intrinsicWidth.coerceAtLeast(64)
                        val h = drawable.intrinsicHeight.coerceAtLeast(64)
                        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(b)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        b
                    }
                } else null
            } catch (e: Exception) {
                null
            }

            if (bmp != null) {
                thumbCache.put(key, bmp)
                runOnUiThread {
                    if (imageView.tag == key) {
                        imageView.clearColorFilter()
                        imageView.setImageBitmap(bmp)
                    }
                }
            }
        }
    }

    // DIRECT APK INSTALLER
    private fun installApkFile(apkFile: File) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(getFileUri(apkFile), "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to launch APK installer", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMediaViewer(file: File, mode: String) {
        val rootView = findViewById<android.view.ViewGroup>(android.R.id.content)
        if (rootView.findViewById<View>(R.id.viewerRoot) != null) return

        val viewer = layoutInflater.inflate(R.layout.activity_viewer, rootView, false)
        viewer.isClickable = true
        viewer.isFocusable = true
        rootView.addView(viewer)

        val overlayLayer = viewer.findViewById<View>(R.id.viewerOverlayLayer)
        val btnBack = viewer.findViewById<android.widget.ImageView>(R.id.btnViewerBack)
        val btnViewerMenu = viewer.findViewById<android.widget.ImageView>(R.id.btnViewerMenu)
        btnViewerMenu.visibility = View.GONE
        val titleTv = viewer.findViewById<TextView>(R.id.viewerTitle)
        val subtitleTv = viewer.findViewById<TextView>(R.id.viewerSubtitle)
        val imageView = viewer.findViewById<android.widget.ImageView>(R.id.viewerImageView)
        val videoView = viewer.findViewById<android.widget.VideoView>(R.id.viewerVideoView)
        val videoTouchCatcher = viewer.findViewById<View>(R.id.videoTouchCatcher)

        val imageBottomBar = viewer.findViewById<LinearLayout>(R.id.imageBottomBar)
        val chkImageSelect = viewer.findViewById<CheckBox>(R.id.chkImageSelect)
        val imageCounterTv = viewer.findViewById<TextView>(R.id.imageCounterTv)
        val btnImageShare = viewer.findViewById<android.widget.ImageView>(R.id.btnImageShare)

        val videoBottomBar = viewer.findViewById<LinearLayout>(R.id.videoBottomBar)
        val videoSeekBar = viewer.findViewById<android.widget.SeekBar>(R.id.videoSeekBar)
        val videoCurrentTime = viewer.findViewById<TextView>(R.id.videoCurrentTime)
        val videoTotalTime = viewer.findViewById<TextView>(R.id.videoTotalTime)
        val btnVidPrev = viewer.findViewById<android.widget.ImageView>(R.id.btnVidPrev)
        val btnVidBack10 = viewer.findViewById<android.widget.ImageView>(R.id.btnVidBack10)
        val btnVidPlayPause = viewer.findViewById<android.widget.ImageView>(R.id.btnVidPlayPause)
        val btnVidFwd10 = viewer.findViewById<android.widget.ImageView>(R.id.btnVidFwd10)
        val btnVidNext = viewer.findViewById<android.widget.ImageView>(R.id.btnVidNext)
        val btnVideoLock = viewer.findViewById<android.widget.ImageView>(R.id.btnVideoLock)
        val btnVideoRotate = viewer.findViewById<android.widget.ImageView>(R.id.btnVideoRotate)
        val btnVideoUnlockOnly = viewer.findViewById<android.widget.ImageView>(R.id.btnVideoUnlockOnly)

        val pdfBar = viewer.findViewById<LinearLayout>(R.id.pdfControlsBar)
        val pageTv = viewer.findViewById<TextView>(R.id.pdfPageIndicator)
        val btnPdfPrev = viewer.findViewById<TextView>(R.id.btnPdfPrev)
        val btnPdfNext = viewer.findViewById<TextView>(R.id.btnPdfNext)
        val pdfSeekBar = viewer.findViewById<android.widget.SeekBar>(R.id.pdfPageSeekBar)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var controlsVisible = true
        var isVideoLocked = false
        var isLandscape = false

        var pdfRenderer: android.graphics.pdf.PdfRenderer? = null
        var parcelFd: android.os.ParcelFileDescriptor? = null

        @Suppress("DEPRECATION")
        fun enterImmersiveMode() {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }

        val fadeOutRunnable = Runnable {
            if (controlsVisible) {
                controlsVisible = false
                overlayLayer.animate().alpha(0f).setDuration(220).withEndAction {
                    overlayLayer.visibility = View.GONE
                }.start()
            }
        }

        fun showControlsTemporarily() {
            handler.removeCallbacks(fadeOutRunnable)
            controlsVisible = true
            overlayLayer.visibility = View.VISIBLE
            overlayLayer.animate().alpha(1f).setDuration(150).start()
            if (mode == "image" || mode == "video") {
                handler.postDelayed(fadeOutRunnable, 2800)
            }
        }

        fun toggleControls() {
            handler.removeCallbacks(fadeOutRunnable)
            if (controlsVisible) {
                controlsVisible = false
                overlayLayer.animate().alpha(0f).setDuration(200).withEndAction {
                    overlayLayer.visibility = View.GONE
                }.start()
            } else {
                showControlsTemporarily()
            }
        }

        fun closeViewer() {
            handler.removeCallbacksAndMessages(null)
            try {
                videoView.stopPlayback()
                pdfRenderer?.close()
                parcelFd?.close()
            } catch (_: Exception) { }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            rootView.removeView(viewer)
            loadBothPanels()
            updateSelectionToolbar()
        }

        btnVideoLock.visibility = View.GONE
        btnVideoRotate.visibility = View.GONE
        btnVideoUnlockOnly.visibility = View.GONE
        videoTouchCatcher.visibility = View.GONE
        btnBack.setOnClickListener { closeViewer() }

        if (mode == "image" || mode == "video") {
            enterImmersiveMode()
            handler.postDelayed(fadeOutRunnable, 1600)
        }

        val activeSortBy = if (activePanel == 0) leftSortBy else rightSortBy
        val activeSortRev = if (activePanel == 0) leftSortReverse else rightSortReverse
        fun sortMediaList(files: List<File>): List<File> {
            val sorted = when (activeSortBy) {
                SortBy.NAME -> files.sortedBy { it.name.lowercase() }
                SortBy.TYPE -> files.sortedBy { it.extension.lowercase() }
                SortBy.DATE -> files.sortedBy { it.lastModified() }
                SortBy.SIZE -> files.sortedBy { it.length() }
            }
            return if (activeSortRev) sorted.reversed() else sorted
        }

        fun showViewerOptionsPopup(
            targetFile: File,
            onRenamed: (File) -> Unit,
            onDeleted: () -> Unit
        ) {
            handler.removeCallbacks(fadeOutRunnable)
            val density = resources.displayMetrics.density
            val teal = Color.parseColor("#62B1B0")

            val listCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val vp = (4 * density).toInt()
                setPadding(0, vp, 0, vp)
            }

            val popupWidth = (145 * density).toInt()
            val popupWindow = android.widget.PopupWindow(
                listCol,
                popupWidth,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                true
            ).apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.parseColor("#353434")))
                elevation = 12f * density
                isOutsideTouchable = true
                setOnDismissListener { showControlsTemporarily() }
            }

            for (label in listOf("Rename", "Info", "Delete")) {
                val tv = TextView(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(if (label == "Delete") Color.parseColor("#FF6B6B") else Color.parseColor("#EFEFEF"))
                    val hp = (16 * density).toInt()
                    val vp = (10 * density).toInt()
                    setPadding(hp, vp, hp, vp)
                    setOnClickListener {
                        popupWindow.dismiss()
                        when (label) {
                            "Info" -> showInfoDialog(listOf(targetFile))
                            "Rename" -> {
                                val input = EditText(this@MainActivity).apply {
                                    setText(targetFile.name)
                                    setTextColor(Color.parseColor("#EFEFEF"))
                                    setSelectAllOnFocus(true)
                                }
                                val dlg = AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle("Rename")
                                .setView(input)
                                .setPositiveButton("OK", null)
                                .setNegativeButton("Cancel", null)
                                .create()
                                dlg.setOnShowListener {
                                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
                                    dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
                                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                        val newName = input.text.toString().trim()
                                        if (newName.isEmpty() || newName.contains("/") || newName.contains("\\")) {
                                            input.error = "Invalid name"
                                            return@setOnClickListener
                                        }
                                        val newFile = File(targetFile.parentFile, newName)
                                        if (newFile.exists()) {
                                            input.error = "Name already exists"
                                            return@setOnClickListener
                                        }
                                        if (targetFile.renameTo(newFile)) {
                                            recentHighlightedPaths.clear()
                                            recentHighlightedPaths.add(newFile.absolutePath)
                                            loadBothPanels()
                                            dlg.dismiss()
                                            onRenamed(newFile)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Rename failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                dlg.show()
                            }
                            "Delete" -> {
                                val pad = (20 * density).toInt()
                                val box = LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(pad, (12 * density).toInt(), pad, 0)
                                }
                                val promptTv = TextView(this@MainActivity).apply {
                                    text = "Do you want to move ${targetFile.name} to the recycle bin?"
                                    textSize = 15f
                                    setTextColor(Color.parseColor("#EFEFEF"))
                                    setPadding(0, 0, 0, (10 * density).toInt())
                                }
                                val trashChk = CheckBox(this@MainActivity).apply {
                                    text = "Move to recycle bin"
                                    textSize = 14f
                                    setTextColor(Color.parseColor("#EFEFEF"))
                                    buttonTintList = android.content.res.ColorStateList.valueOf(teal)
                                    isChecked = true
                                    setOnCheckedChangeListener { _, checked ->
                                        promptTv.text = if (checked) {
                                            "Do you want to move ${targetFile.name} to the recycle bin?"
                                        } else {
                                            "Do you want to permanently delete ${targetFile.name}?"
                                        }
                                    }
                                }
                                box.addView(promptTv)
                                box.addView(trashChk)

                                val dlg = AlertDialog.Builder(this@MainActivity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                .setTitle("Delete")
                                .setView(box)
                                .setNegativeButton("CANCEL", null)
                                .setPositiveButton("OK") { _, _ ->
                                    val moveToTrash = trashChk.isChecked
                                    uiScope.launch {
                                        val ok = withContext(Dispatchers.IO) {
                                            if (moveToTrash) {
                                                moveSingleFileToTrash(targetFile)
                                                !targetFile.exists()
                                            } else {
                                                targetFile.delete()
                                            }
                                        }
                                        if (ok) {
                                            getSelectedItems(activePanel).remove(targetFile.absolutePath)
                                            Toast.makeText(
                                                this@MainActivity,
                                                if (moveToTrash) "Moved to Trash" else "Deleted",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onDeleted()
                                        } else {
                                            Toast.makeText(this@MainActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                                .create()
                                dlg.setOnShowListener {
                                    dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(teal)
                                    dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
                                }
                                dlg.show()
                            }
                        }
                    }
                }
                listCol.addView(tv)
            }

            val xOffset = btnViewerMenu.width - popupWidth
            popupWindow.showAsDropDown(btnViewerMenu, xOffset, 0)
        }

        when (mode) {
            "image" -> {
                imageView.visibility = View.VISIBLE
                imageBottomBar.visibility = View.VISIBLE
                subtitleTv.visibility = View.VISIBLE

                val parentDir = file.parentFile ?: getCurrentFolder()
                val imageExts = listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")
                val imageList = getVisibleFiles(parentDir)
                .filter { !it.isDirectory && it.extension.lowercase() in imageExts }
                .let { sortMediaList(it) }
                .ifEmpty { listOf(file) }
                .toMutableList()

                var currentIdx = imageList.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
                val panelSelected = getSelectedItems(activePanel)

                fun displayImageAt(index: Int) {
                    currentIdx = index.coerceIn(0, imageList.size - 1)
                    val imgFile = imageList[currentIdx]
                    titleTv.text = imgFile.name
                    imageCounterTv.text = "${currentIdx + 1}/${imageList.size}"

                    btnViewerMenu.visibility = View.VISIBLE
                    btnViewerMenu.setOnClickListener {
                        showViewerOptionsPopup(
                            targetFile = imgFile,
                            onRenamed = { renamedFile ->
                                imageList[currentIdx] = renamedFile
                                titleTv.text = renamedFile.name
                            },
                            onDeleted = {
                                imageList.removeAt(currentIdx)
                                if (imageList.isEmpty()) {
                                    closeViewer()
                                } else {
                                    displayImageAt(currentIdx.coerceAtMost(imageList.size - 1))
                                }
                            }
                        )
                    }

                    chkImageSelect.setOnCheckedChangeListener(null)
                    chkImageSelect.isChecked = panelSelected.contains(imgFile.absolutePath)
                    chkImageSelect.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                            panelSelected.add(imgFile.absolutePath)
                            setPanelSelectionMode(activePanel, true)
                        } else {
                            panelSelected.remove(imgFile.absolutePath)
                        }
                    }

                    btnImageShare.setOnClickListener {
                        shareFiles(listOf(imgFile))
                    }

                    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(imgFile.absolutePath, opts)
                    subtitleTv.text = "${dateFormatter.format(Date(imgFile.lastModified()))}  " +
                    "${formatCompactSize(imgFile.length())}  (${opts.outWidth}x${opts.outHeight})"

                    Glide.with(this@MainActivity)
                    .load(imgFile)
                    .fitCenter()
                    .into(imageView)
                }

                var touchDownX = 0f
                var touchDownY = 0f
                var isSwiping = false
                val swipeMin = 55f * resources.displayMetrics.density
                val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop
                imageView.isClickable = true
                imageView.setOnTouchListener { _, event ->
                    when (event.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            touchDownX = event.rawX
                            touchDownY = event.rawY
                            isSwiping = false
                            true
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - touchDownX
                            val dy = event.rawY - touchDownY
                            if (!isSwiping && kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                isSwiping = true
                            }
                            if (isSwiping) {
                                val atEdge = (dx > 0 && currentIdx == 0) || (dx < 0 && currentIdx == imageList.size - 1)
                                imageView.translationX = if (atEdge) dx * 0.25f else dx
                            }
                            true
                        }
                        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                            val dx = event.rawX - touchDownX
                            val dy = event.rawY - touchDownY
                            val screenW = viewer.width.takeIf { it > 0 }?.toFloat() ?: resources.displayMetrics.widthPixels.toFloat()
                            if (isSwiping && dx < -swipeMin && currentIdx < imageList.size - 1) {
                                imageView.animate().translationX(-screenW).setDuration(140).withEndAction {
                                    displayImageAt(currentIdx + 1)
                                    imageView.translationX = screenW
                                    imageView.animate().translationX(0f).setDuration(160).start()
                                }.start()
                            } else if (isSwiping && dx > swipeMin && currentIdx > 0) {
                                imageView.animate().translationX(screenW).setDuration(140).withEndAction {
                                    displayImageAt(currentIdx - 1)
                                    imageView.translationX = -screenW
                                    imageView.animate().translationX(0f).setDuration(160).start()
                                }.start()
                            } else {
                                imageView.animate().translationX(0f).setDuration(140).start()
                                if (!isSwiping && kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop) {
                                    toggleControls()
                                }
                            }
                            isSwiping = false
                            true
                        }
                        else -> true
                    }
                }

                displayImageAt(currentIdx)
            }

            "video" -> {
                videoView.visibility = View.VISIBLE
                videoTouchCatcher.visibility = View.VISIBLE
                videoBottomBar.visibility = View.VISIBLE
                btnVideoLock.visibility = View.VISIBLE
                btnVideoRotate.visibility = View.VISIBLE

                val parentDir = file.parentFile ?: getCurrentFolder()
                val videoExts = listOf("mp4", "mkv", "avi", "mov", "3gp")
                val videoList = getVisibleFiles(parentDir)
                .filter { !it.isDirectory && it.extension.lowercase() in videoExts }
                .let { sortMediaList(it) }
                .ifEmpty { listOf(file) }
                .toMutableList()

                var vidIdx = videoList.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
                var isSeeking = false

                fun formatMs(ms: Int): String {
                    val totalSec = (ms / 1000).coerceAtLeast(0)
                    val mins = totalSec / 60
                    val secs = totalSec % 60
                    return String.format(Locale.US, "%02d:%02d", mins, secs)
                }

                val progressUpdater = object : Runnable {
                    override fun run() {
                        try {
                            if (videoView.isPlaying && !isSeeking) {
                                val pos = videoView.currentPosition
                                videoSeekBar.progress = pos
                                videoCurrentTime.text = formatMs(pos)
                            }
                        } catch (_: Exception) { }
                        handler.postDelayed(this, 500)
                    }
                }

                fun playVideoAt(index: Int) {
                    vidIdx = index.coerceIn(0, videoList.size - 1)
                    val vidFile = videoList[vidIdx]
                    titleTv.text = vidFile.name

                    btnViewerMenu.visibility = View.VISIBLE
                    btnViewerMenu.setOnClickListener {
                        showViewerOptionsPopup(
                            targetFile = vidFile,
                            onRenamed = { renamedFile ->
                                videoList[vidIdx] = renamedFile
                                titleTv.text = renamedFile.name
                            },
                            onDeleted = {
                                videoView.stopPlayback()
                                videoList.removeAt(vidIdx)
                                if (videoList.isEmpty()) {
                                    closeViewer()
                                } else {
                                    playVideoAt(vidIdx.coerceAtMost(videoList.size - 1))
                                }
                            }
                        )
                    }

                    btnVidPlayPause.setImageResource(R.drawable.pause)
                    handler.removeCallbacks(progressUpdater)
                    videoView.stopPlayback()
                    videoSeekBar.progress = 0
                    videoCurrentTime.text = "00:00"
                    videoView.setVideoPath(vidFile.absolutePath)
                    videoView.setOnPreparedListener { mp ->
                        mp.setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                        )
                        mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                        val dur = mp.duration
                        videoSeekBar.max = dur
                        videoTotalTime.text = formatMs(dur)
                        videoView.start()
                        handler.post(progressUpdater)
                    }
                }

                btnVidPlayPause.setOnClickListener {
                    if (videoView.isPlaying) {
                        videoView.pause()
                        btnVidPlayPause.setImageResource(R.drawable.play_arrow)
                    } else {
                        videoView.start()
                        btnVidPlayPause.setImageResource(R.drawable.pause)
                    }
                    showControlsTemporarily()
                }

                btnVidBack10.setOnClickListener {
                    val target = (videoView.currentPosition - 10000).coerceAtLeast(0)
                    videoView.seekTo(target)
                    videoSeekBar.progress = target
                    videoCurrentTime.text = formatMs(target)
                    showControlsTemporarily()
                }

                btnVidFwd10.setOnClickListener {
                    val target = (videoView.currentPosition + 10000).coerceAtMost(videoView.duration)
                    videoView.seekTo(target)
                    videoSeekBar.progress = target
                    videoCurrentTime.text = formatMs(target)
                    showControlsTemporarily()
                }

                btnVidPrev.setOnClickListener {
                    if (vidIdx > 0) playVideoAt(vidIdx - 1)
                    else Toast.makeText(this, "First video", Toast.LENGTH_SHORT).show()
                    showControlsTemporarily()
                }

                btnVidNext.setOnClickListener {
                    if (vidIdx < videoList.size - 1) playVideoAt(vidIdx + 1)
                    else Toast.makeText(this, "Last video", Toast.LENGTH_SHORT).show()
                    showControlsTemporarily()
                }

                videoSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) {
                                videoCurrentTime.text = formatMs(progress)
                            }
                        }
                        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                            isSeeking = true
                            handler.removeCallbacks(fadeOutRunnable)
                        }
                        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                            isSeeking = false
                            videoView.seekTo(sb?.progress ?: 0)
                            showControlsTemporarily()
                        }
                })

                btnVideoLock.setOnClickListener {
                    isVideoLocked = true
                    handler.removeCallbacks(fadeOutRunnable)
                    overlayLayer.visibility = View.GONE
                    btnVideoUnlockOnly.visibility = View.VISIBLE
                }

                btnVideoUnlockOnly.setOnClickListener {
                    isVideoLocked = false
                    btnVideoUnlockOnly.visibility = View.GONE
                    showControlsTemporarily()
                }

                btnVideoRotate.setOnClickListener {
                    isLandscape = !isLandscape
                    requestedOrientation = if (isLandscape) {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }
                    showControlsTemporarily()
                }

                videoTouchCatcher.setOnClickListener {
                    if (isVideoLocked) {
                        btnVideoUnlockOnly.visibility =
                        if (btnVideoUnlockOnly.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    } else {
                        toggleControls()
                    }
                }

                playVideoAt(vidIdx)
            }

            "pdf" -> {
                titleTv.text = file.name
                imageView.visibility = View.VISIBLE
                pdfBar.visibility = View.VISIBLE
                try {
                    parcelFd = android.os.ParcelFileDescriptor.open(
                        file,
                        android.os.ParcelFileDescriptor.MODE_READ_ONLY
                    )
                    pdfRenderer = android.graphics.pdf.PdfRenderer(parcelFd!!)
                    val totalPages = pdfRenderer!!.pageCount
                    var currentPageIdx = 0
                    val maxTicks = 1000
                    pdfSeekBar.max = if (totalPages > 1) maxTicks else 0

                    fun renderPdfPage(idx: Int, syncSlider: Boolean) {
                        if (totalPages <= 0) return
                        currentPageIdx = idx.coerceIn(0, totalPages - 1)
                        pageTv.text = "${currentPageIdx + 1} / $totalPages"
                        if (syncSlider && totalPages > 1) {
                            pdfSeekBar.progress = (currentPageIdx * maxTicks) / (totalPages - 1)
                        }
                        val page = pdfRenderer!!.openPage(currentPageIdx)
                        val scale = 2
                        val bmp = android.graphics.Bitmap.createBitmap(
                            page.width * scale,
                            page.height * scale,
                            android.graphics.Bitmap.Config.ARGB_8888
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        imageView.setImageBitmap(bmp)
                    }

                    btnPdfPrev.setOnClickListener {
                        if (currentPageIdx > 0) renderPdfPage(currentPageIdx - 1, syncSlider = true)
                    }

                    btnPdfNext.setOnClickListener {
                        if (currentPageIdx < totalPages - 1) renderPdfPage(currentPageIdx + 1, syncSlider = true)
                    }

                    pdfSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                                if (fromUser && totalPages > 1) {
                                    val previewIdx = ((progress.toFloat() / maxTicks) * (totalPages - 1))
                                    .toInt()
                                    .coerceIn(0, totalPages - 1)
                                    pageTv.text = "${previewIdx + 1} / $totalPages"
                                }
                            }
                            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                                if (totalPages > 1) {
                                    val targetIdx = (((sb?.progress ?: 0).toFloat() / maxTicks) * (totalPages - 1))
                                    .toInt()
                                    .coerceIn(0, totalPages - 1)
                                    renderPdfPage(targetIdx, syncSlider = false)
                                }
                            }
                    })

                    renderPdfPage(0, syncSlider = true)
                } catch (e: Exception) {
                    Toast.makeText(this, "Cannot open PDF file", Toast.LENGTH_SHORT).show()
                    closeViewer()
                }
            }

            "audio" -> {
                titleTv.text = file.name
                subtitleTv.visibility = View.VISIBLE
                subtitleTv.text = "Audio Player • ${formatCompactSize(file.length())}"
                imageView.visibility = View.VISIBLE
                imageView.setImageResource(R.drawable.files)
                imageView.setColorFilter(Color.parseColor("#62B1B0"))
                val pad = (110 * resources.displayMetrics.density).toInt()
                imageView.setPadding(pad, pad, pad, pad)
                videoBottomBar.visibility = View.VISIBLE
                btnVidPrev.visibility = View.INVISIBLE
                btnVidNext.visibility = View.INVISIBLE

                var mediaPlayer: android.media.MediaPlayer? = android.media.MediaPlayer().apply {
                    setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                    )
                    setDataSource(file.absolutePath)
                    prepare()
                    start()
                }

                fun formatMs(ms: Int): String {
                    val totalSec = (ms / 1000).coerceAtLeast(0)
                    return String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
                }

                videoSeekBar.max = mediaPlayer?.duration ?: 0
                videoTotalTime.text = formatMs(mediaPlayer?.duration ?: 0)
                btnVidPlayPause.setImageResource(R.drawable.pause)

                val audioTick = object : Runnable {
                    override fun run() {
                        val mp = mediaPlayer ?: return
                        if (mp.isPlaying) {
                            videoSeekBar.progress = mp.currentPosition
                            videoCurrentTime.text = formatMs(mp.currentPosition)
                        }
                        handler.postDelayed(this, 500)
                    }
                }
                handler.post(audioTick)

                btnVidPlayPause.setOnClickListener {
                    val mp = mediaPlayer ?: return@setOnClickListener
                    if (mp.isPlaying) {
                        mp.pause()
                        btnVidPlayPause.setImageResource(R.drawable.play_arrow)
                    } else {
                        mp.start()
                        btnVidPlayPause.setImageResource(R.drawable.pause)
                    }
                }
                btnVidBack10.setOnClickListener {
                    val mp = mediaPlayer ?: return@setOnClickListener
                    mp.seekTo((mp.currentPosition - 10000).coerceAtLeast(0))
                }
                btnVidFwd10.setOnClickListener {
                    val mp = mediaPlayer ?: return@setOnClickListener
                    mp.seekTo((mp.currentPosition + 10000).coerceAtMost(mp.duration))
                }
                videoSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                            if (fromUser) videoCurrentTime.text = formatMs(progress)
                        }
                        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                            mediaPlayer?.seekTo(sb?.progress ?: 0)
                        }
                })
                btnBack.setOnClickListener {
                    try { mediaPlayer?.stop(); mediaPlayer?.release() } catch (_: Exception) {}
                    mediaPlayer = null
                    closeViewer()
                }
            }
        }
    }

    // BOTTOM SWIPE-UP HISTORY DRAWER
    private fun recordHistoryItem(file: File) {
        val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("recent_history", "") ?: ""
        val list = if (raw.isEmpty()) mutableListOf() else raw.split("\n").toMutableList()
        list.remove(file.absolutePath)
        list.add(0, file.absolutePath)
        while (list.size > 50) {
            list.removeAt(list.size - 1)
        }
        prefs.edit().putString("recent_history", list.joinToString("\n")).apply()
    }

    private fun showHistoryDrawer() {
        val prefs = getSharedPreferences("gonofiles_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("recent_history", "") ?: ""
        val items = raw.split("\n").filter { it.isNotBlank() && File(it).exists() }

        val density = resources.displayMetrics.density
        val teal = Color.parseColor("#62B1B0")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#353434"))
            val pad = (16 * density).toInt()
            setPadding(pad, (10 * density).toInt(), pad, 0)
        }

        val scroll = android.widget.ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (340 * density).toInt()
            )
        }
        val listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listLayout)
        container.addView(scroll)

        val dialog = AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
        .setTitle("Recent History (${items.size}/50)")
        .setView(container)
        .setNegativeButton("Close", null)
        .setNeutralButton("Clear") { _, _ ->
            prefs.edit().remove("recent_history").apply()
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
        }
        .create()

        if (items.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No recent history yet.\nSwipe up from the bottom bar anytime."
                textSize = 14f
                setTextColor(Color.parseColor("#BDBDBD"))
                setPadding(0, (24 * density).toInt(), 0, (24 * density).toInt())
            }
            listLayout.addView(emptyTv)
        } else {
            for (path in items) {
                val f = File(path)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val vp = (10 * density).toInt()
                    setPadding(0, vp, 0, vp)
                }
                val iconIv = android.widget.ImageView(this).apply {
                    val iconSize = (30 * density).toInt()
                    val pad = (3 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setPadding(pad, pad, pad, pad)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    if (f.isDirectory) {
                        setImageResource(R.drawable.folder)
                        setColorFilter(teal)
                    } else {
                        setImageResource(R.drawable.files)
                        setColorFilter(Color.parseColor("#BDBDBD"))
                    }
                }
                val col = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((10 * density).toInt(), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val nameTv = TextView(this).apply {
                    text = f.name
                    textSize = 15f
                    setTextColor(Color.parseColor("#EFEFEF"))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.MIDDLE
                }
                val pathTv = TextView(this).apply {
                    text = f.absolutePath
                    textSize = 11f
                    setTextColor(Color.parseColor("#BDBDBD"))
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.START
                }
                col.addView(nameTv)
                col.addView(pathTv)
                row.addView(iconIv)
                row.addView(col)
                row.setOnClickListener {
                    dialog.dismiss()
                    if (f.isDirectory) {
                        navigateTo(f)
                    } else {
                        f.parentFile?.let { navigateTo(it) }
                    }
                }
                listLayout.addView(row)
            }
        }

        dialog.window?.setGravity(Gravity.BOTTOM)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(teal)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.parseColor("#FF6B6B"))
        }
        dialog.show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            thumbCache.evictAll()
            Glide.get(this).clearMemory()
        }
    }
}
