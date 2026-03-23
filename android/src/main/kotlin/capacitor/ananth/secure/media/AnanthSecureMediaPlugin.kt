package com.ananth.picsecure.media

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.WindowManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Logger
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback
import com.getcapacitor.PermissionState
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

@CapacitorPlugin(
    name = "AnanthSecureMedia",
    permissions = [
        Permission(
            strings = [
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.READ_MEDIA_VIDEO"
            ],
            alias = "media"
        ),
        Permission(
            strings = ["android.permission.READ_MEDIA_AUDIO"],
            alias = "audio"
        ),
        Permission(
            strings = ["android.permission.READ_EXTERNAL_STORAGE"],
            alias = "storage"
        ),
        Permission(
            strings = ["android.permission.CAMERA"],
            alias = "camera"
        )
    ]
)
class AnanthSecureMediaPlugin : Plugin() {

    @Volatile private var isDestroyed = false

    private lateinit var prefs: SharedPreferences
    private var lastUnlockTime: Long = 0
    private val AUTO_LOCK_TIME = 5 * 60 * 1000L
    private var currentPhotoPath: String? = null

    // Single thread — prevents all race conditions and OOM crashes
    private val mediaExecutor = Executors.newSingleThreadExecutor()

    private lateinit var photoLauncher: ActivityResultLauncher<Intent>
    private lateinit var videoLauncher: ActivityResultLauncher<Intent>
    private lateinit var allFilesLauncher: ActivityResultLauncher<Intent>

    private var photoCall: PluginCall? = null
    private var videoCall: PluginCall? = null
    private var allFilesCall: PluginCall? = null

    private var pendingAction: (() -> Unit)? = null
    private var pendingCall: PluginCall? = null

    private val docExtensions = setOf(
        "pdf","doc","docx","xls","xlsx","ppt","pptx",
        "txt","csv","zip","rar","7z","apk","json","xml"
    )

    private val extToMime = mapOf(
        "pdf"  to "application/pdf",
        "doc"  to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls"  to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt"  to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt"  to "text/plain",
        "csv"  to "text/csv",
        "zip"  to "application/zip",
        "rar"  to "application/x-rar-compressed",
        "7z"   to "application/x-7z-compressed",
        "apk"  to "application/vnd.android.package-archive",
        "json" to "application/json",
        "xml"  to "application/xml"
    )

    /* ─── resolveWebPath ─────────────────────────────────── */
    private fun resolveWebPath(contentUri: Uri): String {
        return try {
            val proj = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME)
            context.contentResolver.query(contentUri, proj, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val dataCol = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataCol != -1) {
                        val path = c.getString(dataCol)
                        if (!path.isNullOrEmpty() && File(path).exists()) return "file://$path"
                    }
                    // DATA column empty — copy to cache (small files only, max 10MB)
                    val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val fileName = if (nameCol != -1) c.getString(nameCol) else null
                        ?: "media_${System.currentTimeMillis()}"
                    val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val fileSize = if (sizeCol != -1) c.getLong(sizeCol) else Long.MAX_VALUE
                    // Only cache files under 10MB to prevent OOM
                    if (fileSize < 10 * 1024 * 1024) {
                        try {
                            val cacheFile = File(context.cacheDir, "cap_${fileName.hashCode()}_$fileName")
                            if (!cacheFile.exists()) {
                                context.contentResolver.openInputStream(contentUri)?.use { input ->
                                    FileOutputStream(cacheFile).use { out -> input.copyTo(out) }
                                }
                            }
                            if (cacheFile.exists()) return "file://${cacheFile.absolutePath}"
                        } catch (e: Exception) {
                            Logger.warn("AnanthSecureMedia", "cache copy failed: ${e.message}")
                        }
                    }
                }
            }
            contentUri.toString()
        } catch (e: Exception) {
            contentUri.toString()
        }
    }

    /* ─── lifecycle ──────────────────────────────────────── */
    override fun load() {
        prefs = context.getSharedPreferences("picsecure_settings", Activity.MODE_PRIVATE)
        val act = activity ?: return
        if (act.isFinishing || act.isDestroyed) return
        try {
            act.runOnUiThread {
                val a = activity ?: return@runOnUiThread
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                if (!::photoLauncher.isInitialized) {
                    photoLauncher = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        try { handlePhotoResult(it) } catch (e: Exception) { Logger.warn("AnanthSecureMedia", "photo: ${e.message}") }
                    }
                }
                if (!::videoLauncher.isInitialized) {
                    videoLauncher = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        try { handleVideoResult(it) } catch (e: Exception) { Logger.warn("AnanthSecureMedia", "video: ${e.message}") }
                    }
                }
                if (!::allFilesLauncher.isInitialized) {
                    allFilesLauncher = a.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { _ ->
                        val call = allFilesCall ?: return@registerForActivityResult
                        allFilesCall = null
                        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                            android.os.Environment.isExternalStorageManager() else true
                        call.resolve(JSObject().put("granted", granted))
                    }
                }
            }
        } catch (e: Exception) {
            Logger.warn("AnanthSecureMedia", "load error: ${e.message}")
        }
    }

    override fun handleOnDestroy() {
        isDestroyed = true
        mediaExecutor.shutdownNow()
    }

    /* ─── safe helpers ───────────────────────────────────── */
    private fun safeResolve(call: PluginCall, data: JSObject) {
        if (isDestroyed) return
        try {
            activity?.runOnUiThread {
                try { call.resolve(data) } catch (e: Exception) { Logger.warn("AnanthSecureMedia", "resolve: ${e.message}") }
            }
        } catch (e: Exception) { Logger.warn("AnanthSecureMedia", "safeResolve: ${e.message}") }
    }

    private fun safeReject(call: PluginCall, message: String) {
        if (isDestroyed) return
        try {
            activity?.runOnUiThread {
                try { call.reject(message) } catch (e: Exception) { Logger.warn("AnanthSecureMedia", "reject: ${e.message}") }
            }
        } catch (e: Exception) { Logger.warn("AnanthSecureMedia", "safeReject: ${e.message}") }
    }

    /* ─── settings ───────────────────────────────────────── */
    @PluginMethod
    fun setSecuritySettings(call: PluginCall) {
        val antiScreenshot = call.getBoolean("antiScreenshot") ?: false
        val hideMedia      = call.getBoolean("hideMedia")      ?: false
        val autoLock       = call.getBoolean("autoLock")       ?: false
        prefs.edit()
            .putBoolean("antiScreenshot", antiScreenshot)
            .putBoolean("hideMedia", hideMedia)
            .putBoolean("autoLock", autoLock)
            .apply()
        if (antiScreenshot) enableSecureScreenInternal() else disableSecureScreenInternal()
        safeResolve(call, JSObject().put("saved", true))
    }

    /* ─── auto lock ──────────────────────────────────────── */
    private fun isVaultLocked(): Boolean {
        if (!prefs.getBoolean("autoLock", false)) return false
        return System.currentTimeMillis() - lastUnlockTime > prefs.getLong("autoLockTimeout", AUTO_LOCK_TIME)
    }

    /* ─── permissions ────────────────────────────────────── */
    private fun isMediaPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) getPermissionState("media") == PermissionState.GRANTED
        else getPermissionState("storage") == PermissionState.GRANTED

    private fun ensureMediaPermission(call: PluginCall, action: () -> Unit) {
        if (isMediaPermissionGranted()) { action(); return }
        pendingAction = action
        pendingCall   = call
        if (Build.VERSION.SDK_INT >= 33) requestPermissionForAlias("media", call, "mediaPermissionCallback")
        else requestPermissionForAlias("storage", call, "mediaPermissionCallback")
    }

    @PermissionCallback
    private fun mediaPermissionCallback(call: PluginCall) {
        val granted = isMediaPermissionGranted()
        val action  = pendingAction
        val pending = pendingCall
        pendingAction = null; pendingCall = null
        if (granted && !isDestroyed) action?.invoke()
        else pending?.let { safeReject(it, "Media permission denied") }
    }

    private fun hasAllFilesPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            android.os.Environment.isExternalStorageManager()
        else getPermissionState("storage") == PermissionState.GRANTED

    @PluginMethod
    fun checkAllFilesPermission(call: PluginCall) {
        call.resolve(JSObject().put("granted", hasAllFilesPermission()))
    }

    @PluginMethod
    fun requestAllFilesPermission(call: PluginCall) {
        if (hasAllFilesPermission()) { call.resolve(JSObject().put("granted", true)); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                allFilesCall = call
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                allFilesLauncher.launch(intent)
            } catch (e: Exception) {
                try {
                    allFilesCall = call
                    allFilesLauncher.launch(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (e2: Exception) {
                    call.resolve(JSObject().put("granted", false))
                }
            }
        } else {
            requestPermissionForAlias("storage", call, "allFilesPermissionCallback")
        }
    }

    @PermissionCallback
    private fun allFilesPermissionCallback(call: PluginCall) {
        call.resolve(JSObject().put("granted", getPermissionState("storage") == PermissionState.GRANTED))
    }

    /* ─── GET PHOTOS ─────────────────────────────────────── */
    @PluginMethod
    fun getPhotos(call: PluginCall) {
        ensureMediaPermission(call) {
            val limit  = call.getInt("limit")  ?: 100
            val offset = call.getInt("offset") ?: 0
            mediaExecutor.execute {
                if (isDestroyed) return@execute
                try {
                    val results = JSArray()
                    val uri     = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val proj = arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DISPLAY_NAME,
                        MediaStore.Images.Media.SIZE,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Images.Media.DATA
                    )
                    val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val b = Bundle().apply {
                            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
                            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                        }
                        context.contentResolver.query(uri, proj, b, null)
                    } else {
                        context.contentResolver.query(uri, proj, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
                    }
                    cursor?.use { c ->
                        val idIdx    = c.getColumnIndex(MediaStore.Images.Media._ID)
                        val nameIdx  = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val sizeIdx  = c.getColumnIndex(MediaStore.Images.Media.SIZE)
                        val dateIdx  = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        val albumIdx = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                        val dataIdx  = c.getColumnIndex(MediaStore.Images.Media.DATA)
                        if (idIdx == -1 || nameIdx == -1) return@use
                        var skipped = 0; var added = 0
                        while (c.moveToNext() && added < limit) {
                            if (isDestroyed) break
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && skipped < offset) { skipped++; continue }
                            val id   = c.getLong(idIdx)
                            val contentUri = ContentUris.withAppendedId(uri, id)
                            // Use DATA path directly — avoids expensive resolveWebPath for images
                            val dataPath = if (dataIdx != -1 && !c.isNull(dataIdx)) c.getString(dataIdx) else null
                            val webPath = if (!dataPath.isNullOrEmpty() && File(dataPath).exists())
                                "file://$dataPath" else contentUri.toString()
                            results.put(JSObject()
                                .put("id",        id.toString())
                                .put("name",      c.getString(nameIdx) ?: "unknown")
                                .put("uri",       contentUri.toString())
                                .put("webPath",   webPath)
                                .put("size",      if (sizeIdx  != -1) c.getLong(sizeIdx)  else 0L)
                                .put("createdAt", if (dateIdx  != -1) c.getLong(dateIdx)  else 0L)
                                .put("album",     if (albumIdx != -1 && !c.isNull(albumIdx)) c.getString(albumIdx) else "Unknown"))
                            added++
                        }
                    }
                    safeResolve(call, JSObject().put("items", results))
                } catch (e: Exception) {
                    Logger.warn("AnanthSecureMedia", "getPhotos error: ${e.message}")
                    safeResolve(call, JSObject().put("items", JSArray()))
                }
            }
        }
    }

    /* ─── GET VIDEOS ─────────────────────────────────────── */
    @PluginMethod
    fun getVideos(call: PluginCall) {
        ensureMediaPermission(call) {
            val limit  = call.getInt("limit")  ?: 100
            val offset = call.getInt("offset") ?: 0
            mediaExecutor.execute {
                if (isDestroyed) return@execute
                try {
                    val results = JSArray()
                    val uri     = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    val proj = arrayOf(
                        MediaStore.Video.Media._ID,
                        MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.SIZE,
                        MediaStore.Video.Media.DATE_ADDED,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.DATA
                    )
                    val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val b = Bundle().apply {
                            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Video.Media.DATE_ADDED))
                            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                        }
                        context.contentResolver.query(uri, proj, b, null)
                    } else {
                        context.contentResolver.query(uri, proj, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC")
                    }
                    cursor?.use { c ->
                        val idIdx    = c.getColumnIndex(MediaStore.Video.Media._ID)
                        val nameIdx  = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                        val sizeIdx  = c.getColumnIndex(MediaStore.Video.Media.SIZE)
                        val dateIdx  = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                        val albumIdx = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                        val dataIdx  = c.getColumnIndex(MediaStore.Video.Media.DATA)
                        if (idIdx == -1 || nameIdx == -1) return@use
                        var skipped = 0; var added = 0
                        while (c.moveToNext() && added < limit) {
                            if (isDestroyed) break
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && skipped < offset) { skipped++; continue }
                            val id = c.getLong(idIdx)
                            val contentUri = ContentUris.withAppendedId(uri, id)
                            val dataPath = if (dataIdx != -1 && !c.isNull(dataIdx)) c.getString(dataIdx) else null
                            val webPath = if (!dataPath.isNullOrEmpty() && File(dataPath).exists())
                                "file://$dataPath" else contentUri.toString()
                            results.put(JSObject()
                                .put("id",        id.toString())
                                .put("name",      c.getString(nameIdx) ?: "unknown")
                                .put("uri",       contentUri.toString())
                                .put("webPath",   webPath)
                                .put("size",      if (sizeIdx  != -1) c.getLong(sizeIdx)  else 0L)
                                .put("createdAt", if (dateIdx  != -1) c.getLong(dateIdx)  else 0L)
                                .put("album",     if (albumIdx != -1 && !c.isNull(albumIdx)) c.getString(albumIdx) else "Unknown"))
                            added++
                        }
                    }
                    safeResolve(call, JSObject().put("items", results))
                } catch (e: Exception) {
                    Logger.warn("AnanthSecureMedia", "getVideos error: ${e.message}")
                    safeResolve(call, JSObject().put("items", JSArray()))
                }
            }
        }
    }

    /* ─── GET AUDIO ──────────────────────────────────────── */
    @PluginMethod
    fun getAudio(call: PluginCall) {
        ensureMediaPermission(call) {
            val limit  = call.getInt("limit")  ?: 100
            val offset = call.getInt("offset") ?: 0
            mediaExecutor.execute {
                if (isDestroyed) return@execute
                try {
                    val results = JSArray()
                    val uri     = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    val proj = arrayOf(
                        MediaStore.Audio.Media._ID,
                        MediaStore.Audio.Media.DISPLAY_NAME,
                        MediaStore.Audio.Media.SIZE,
                        MediaStore.Audio.Media.DATE_ADDED,
                        MediaStore.Audio.Media.ALBUM,
                        MediaStore.Audio.Media.DATA
                    )
                    val cursor: Cursor? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val b = Bundle().apply {
                            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Audio.Media.DATE_ADDED))
                            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                        }
                        context.contentResolver.query(uri, proj, b, null)
                    } else {
                        context.contentResolver.query(uri, proj, null, null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")
                    }
                    cursor?.use { c ->
                        val idIdx    = c.getColumnIndex(MediaStore.Audio.Media._ID)
                        val nameIdx  = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                        val sizeIdx  = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                        val dateIdx  = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                        val albumIdx = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                        val dataIdx  = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (idIdx == -1 || nameIdx == -1) return@use
                        var skipped = 0; var added = 0
                        while (c.moveToNext() && added < limit) {
                            if (isDestroyed) break
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && skipped < offset) { skipped++; continue }
                            val id = c.getLong(idIdx)
                            val contentUri = ContentUris.withAppendedId(uri, id)
                            val dataPath = if (dataIdx != -1 && !c.isNull(dataIdx)) c.getString(dataIdx) else null
                            val webPath = if (!dataPath.isNullOrEmpty() && File(dataPath).exists())
                                "file://$dataPath" else contentUri.toString()
                            results.put(JSObject()
                                .put("id",        id.toString())
                                .put("name",      c.getString(nameIdx) ?: "unknown")
                                .put("uri",       contentUri.toString())
                                .put("webPath",   webPath)
                                .put("size",      if (sizeIdx  != -1) c.getLong(sizeIdx)  else 0L)
                                .put("createdAt", if (dateIdx  != -1) c.getLong(dateIdx)  else 0L)
                                .put("album",     if (albumIdx != -1 && !c.isNull(albumIdx)) c.getString(albumIdx) else "Unknown")
                                .put("thumbnail", webPath))
                            added++
                        }
                    }
                    safeResolve(call, JSObject().put("items", results))
                } catch (e: Exception) {
                    Logger.warn("AnanthSecureMedia", "getAudio error: ${e.message}")
                    safeResolve(call, JSObject().put("items", JSArray()))
                }
            }
        }
    }

    /* ─── GET DOCUMENTS ──────────────────────────────────── */
    @PluginMethod
    fun getDocuments(call: PluginCall) {
        ensureMediaPermission(call) {
            val limit  = call.getInt("limit")  ?: 50
            val offset = call.getInt("offset") ?: 0
            mediaExecutor.execute {
                if (isDestroyed) return@execute
                try {
                    val allItems = mutableListOf<JSObject>()
                    val resolver = context.contentResolver
                    val extStorage = android.os.Environment.getExternalStorageDirectory()
                        ?: File("/storage/emulated/0")

                    val hasAll = hasAllFilesPermission()
                    Logger.warn("AnanthSecureMedia", "getDocuments hasAllFiles=$hasAll SDK=${Build.VERSION.SDK_INT}")

                    if (hasAll) {
                        // Full device scan
                        Logger.warn("AnanthSecureMedia", "Full scan from ${extStorage.absolutePath}")
                        scanDirectoryForDocs(extStorage, allItems, 0, 8)
                        Logger.warn("AnanthSecureMedia", "Full scan found ${allItems.size}")
                    } else {
                        // Strategy B: MediaStore Downloads
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            try {
                                val dlProj = arrayOf(
                                    MediaStore.Downloads._ID,
                                    MediaStore.Downloads.DISPLAY_NAME,
                                    MediaStore.Downloads.SIZE,
                                    MediaStore.Downloads.DATE_ADDED,
                                    MediaStore.Downloads.MIME_TYPE,
                                    MediaStore.Downloads.DATA
                                )
                                val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val b = Bundle().apply {
                                        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Downloads.DATE_ADDED))
                                        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
                                    }
                                    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlProj, b, null)
                                } else {
                                    resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, dlProj, null, null, "${MediaStore.Downloads.DATE_ADDED} DESC")
                                }
                                cursor?.use { c ->
                                    Logger.warn("AnanthSecureMedia", "Downloads cursor: ${c.count}")
                                    val idCol   = c.getColumnIndex(MediaStore.Downloads._ID)
                                    val nameCol = c.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                                    val sizeCol = c.getColumnIndex(MediaStore.Downloads.SIZE)
                                    val dateCol = c.getColumnIndex(MediaStore.Downloads.DATE_ADDED)
                                    val mimeCol = c.getColumnIndex(MediaStore.Downloads.MIME_TYPE)
                                    val dataCol = c.getColumnIndex(MediaStore.Downloads.DATA)
                                    while (c.moveToNext()) {
                                        if (isDestroyed) break
                                        val name = if (nameCol != -1) c.getString(nameCol) ?: "" else ""
                                        val ext  = name.substringAfterLast('.', "").lowercase()
                                        if (ext !in docExtensions) continue
                                        val id = if (idCol != -1) c.getLong(idCol) else continue
                                        val mime = if (mimeCol != -1 && !c.isNull(mimeCol)) c.getString(mimeCol) ?: extToMime[ext] ?: "" else extToMime[ext] ?: ""
                                        val dataPath = if (dataCol != -1 && !c.isNull(dataCol)) c.getString(dataCol) else null
                                        val contentUri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                                        val webPath = if (!dataPath.isNullOrEmpty() && File(dataPath).exists()) "file://$dataPath" else resolveWebPath(contentUri)
                                        allItems.add(JSObject().apply {
                                            put("id",        "dl_$id")
                                            put("name",      name)
                                            put("uri",       contentUri.toString())
                                            put("webPath",   webPath)
                                            put("size",      if (sizeCol != -1) c.getLong(sizeCol) else 0L)
                                            put("createdAt", if (dateCol != -1) c.getLong(dateCol) else 0L)
                                            put("mimeType",  mime)
                                        })
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.warn("AnanthSecureMedia", "Downloads query error: ${e.message}")
                            }
                        }

                        // Strategy C: scan known public folders
                        val scanDirs = mutableListOf<File>()
                        for (dirName in listOf(android.os.Environment.DIRECTORY_DOWNLOADS, android.os.Environment.DIRECTORY_DOCUMENTS)) {
                            android.os.Environment.getExternalStoragePublicDirectory(dirName)
                                ?.takeIf { it.exists() }?.let { scanDirs.add(it) }
                        }
                        for (folder in listOf("Download", "Downloads", "Documents", "Pdf", "PDF")) {
                            File(extStorage, folder).takeIf { it.exists() && scanDirs.none { d -> d.absolutePath == it.absolutePath } }
                                ?.let { scanDirs.add(it) }
                        }
                        context.getExternalFilesDirs(null).filterNotNull().filter { it.exists() }.forEach { scanDirs.add(it) }

                        for (dir in scanDirs) {
                            if (isDestroyed) break
                            try { scanDirectoryForDocs(dir, allItems, 0, 4) } catch (e: Exception) { /* skip */ }
                        }

                        // Strategy D: MediaStore.Files fallback
                        if (allItems.isEmpty() && !isDestroyed) {
                            try {
                                val mimeList = extToMime.values.distinct()
                                val proj = arrayOf(
                                    MediaStore.Files.FileColumns._ID,
                                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                                    MediaStore.Files.FileColumns.SIZE,
                                    MediaStore.Files.FileColumns.DATE_ADDED,
                                    MediaStore.Files.FileColumns.MIME_TYPE,
                                    MediaStore.Files.FileColumns.DATA
                                )
                                resolver.query(
                                    MediaStore.Files.getContentUri("external"),
                                    proj,
                                    "${MediaStore.Files.FileColumns.MIME_TYPE} IN (${mimeList.joinToString(",") { "?" }})",
                                    mimeList.toTypedArray(),
                                    "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"
                                )?.use { c ->
                                    Logger.warn("AnanthSecureMedia", "Files cursor: ${c.count}")
                                    val idCol   = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
                                    val nameCol = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                                    val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                                    val dateCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_ADDED)
                                    val mimeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                                    val dataCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
                                    while (c.moveToNext()) {
                                        if (isDestroyed) break
                                        val id   = if (idCol   != -1) c.getLong(idCol) else continue
                                        val name = if (nameCol != -1) c.getString(nameCol) ?: "" else ""
                                        val dataPath = if (dataCol != -1 && !c.isNull(dataCol)) c.getString(dataCol) else null
                                        val contentUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
                                        val webPath = if (!dataPath.isNullOrEmpty() && File(dataPath).exists()) "file://$dataPath" else resolveWebPath(contentUri)
                                        allItems.add(JSObject().apply {
                                            put("id",        id.toString())
                                            put("name",      name)
                                            put("uri",       contentUri.toString())
                                            put("webPath",   webPath)
                                            put("size",      if (sizeCol != -1) c.getLong(sizeCol) else 0L)
                                            put("createdAt", if (dateCol != -1) c.getLong(dateCol) else 0L)
                                            put("mimeType",  if (mimeCol != -1 && !c.isNull(mimeCol)) c.getString(mimeCol) ?: "" else "")
                                        })
                                    }
                                }
                            } catch (e: Exception) {
                                Logger.warn("AnanthSecureMedia", "Files query error: ${e.message}")
                            }
                        }
                    }

                    val seen   = mutableSetOf<String>()
                    val unique = allItems.filter { seen.add("${it.getString("name")}_${it.getLong("size")}") }
                    val page   = unique.sortedByDescending { it.getLong("createdAt") }.drop(offset).take(limit)
                    val out    = JSArray()
                    for (item in page) out.put(item)
                    Logger.warn("AnanthSecureMedia", "Returning ${page.size} docs")
                    safeResolve(call, JSObject().put("items", out))

                } catch (e: Exception) {
                    Logger.warn("AnanthSecureMedia", "getDocuments error: ${e.message}")
                    safeResolve(call, JSObject().put("items", JSArray()))
                }
            }
        }
    }

    /* ─── scanDirectoryForDocs ───────────────────────────── */
    private fun scanDirectoryForDocs(dir: File, results: MutableList<JSObject>, depth: Int, maxDepth: Int) {
        if (isDestroyed || depth > maxDepth || !dir.exists() || !dir.canRead()) return
        val files = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (file in files) {
            if (isDestroyed) return
            try {
                if (file.name.startsWith(".")) continue
                if (depth == 0 && file.name in setOf("Android", "data", "obb")) continue
                if (file.isDirectory) scanDirectoryForDocs(file, results, depth + 1, maxDepth)
                else if (file.isFile && file.canRead()) {
                    val ext = file.extension.lowercase()
                    if (ext !in docExtensions) continue
                    results.add(JSObject().apply {
                        put("id",        "fs_${file.absolutePath.hashCode()}")
                        put("name",      file.name)
                        put("uri",       "file://${file.absolutePath}")
                        put("webPath",   "file://${file.absolutePath}")
                        put("size",      file.length())
                        put("createdAt", file.lastModified() / 1000)
                        put("mimeType",  extToMime[ext] ?: "application/octet-stream")
                    })
                }
            } catch (e: Exception) { /* skip */ }
        }
    }

    /* ─── getFileAsBase64 — SAFE: max 5MB only ───────────── */
    @PluginMethod
    fun getFileAsBase64(call: PluginCall) {
        val path = call.getString("path") ?: run { call.reject("No path"); return }
        try {
            val file = File(path.removePrefix("file://"))
            if (!file.exists()) { call.reject("File not found"); return }
            // HARD LIMIT: never load files over 5MB into memory — this caused OOM crashes
            if (file.length() > 5 * 1024 * 1024) {
                call.reject("File too large for base64 (max 5MB)")
                return
            }
            val bytes  = file.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val mime = when {
                path.endsWith(".jpg", true) || path.endsWith(".jpeg", true) -> "image/jpeg"
                path.endsWith(".png",  true) -> "image/png"
                path.endsWith(".mp4",  true) -> "video/mp4"
                else                         -> "application/octet-stream"
            }
            call.resolve(JSObject().put("data", "data:$mime;base64,$base64"))
        } catch (e: Exception) {
            call.reject("Error: ${e.message}")
        }
    }

    /* ─── thumbnail ──────────────────────────────────────── */
    @PluginMethod
    fun generateThumbnail(call: PluginCall) {
        val path = call.getString("path") ?: run { safeReject(call, "Path required"); return }
        val bitmap = getBitmapFromPath(path)
        if (bitmap == null) { safeReject(call, "Failed to decode"); return }
        safeResolve(call, JSObject().put("thumbnail", saveBitmapToCache(bitmap)))
    }

    private fun saveBitmapToCache(bitmap: Bitmap?): String {
        return try {
            if (bitmap == null) return ""
            val f = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.png")
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) }
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", f).toString()
        } catch (e: Exception) { "" }
    }

    private fun getBitmapFromPath(path: String): Bitmap? {
        return try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, uri))
                else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            } else {
                val file = File(path)
                if (!file.exists()) return null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file))
                else @Suppress("DEPRECATION") MediaStore.Images.Media.getBitmap(context.contentResolver, Uri.fromFile(file))
            }
        } catch (e: Exception) { null }
    }

    /* ─── camera ─────────────────────────────────────────── */
    @PluginMethod
    fun capturePhoto(call: PluginCall) {
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallback"); return
        }
        launchCamera(call)
    }

    @PermissionCallback
    private fun cameraPermissionCallback(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED) launchCamera(call)
        else safeReject(call, "Camera permission denied")
    }

    private fun launchCamera(call: PluginCall) {
        val photoFile = try { createImageFile() } catch (_: IOException) { safeReject(call, "Failed to create file"); return }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photoFile)
        photoCall = call
        photoLauncher.launch(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }

    @PluginMethod
    fun captureVideo(call: PluginCall) {
        if (!::videoLauncher.isInitialized) { safeReject(call, "Not initialized"); return }
        if (getPermissionState("camera") != PermissionState.GRANTED) {
            requestPermissionForAlias("camera", call, "cameraPermissionCallbackVideo"); return
        }
        launchVideo(call)
    }

    @PermissionCallback
    private fun cameraPermissionCallbackVideo(call: PluginCall) {
        if (getPermissionState("camera") == PermissionState.GRANTED) launchVideo(call)
        else safeReject(call, "Camera denied")
    }

    private fun launchVideo(call: PluginCall) {
        videoCall = call
        activity?.runOnUiThread {
            try { videoLauncher.launch(Intent(MediaStore.ACTION_VIDEO_CAPTURE)) }
            catch (e: Exception) { safeReject(call, "Failed: ${e.message}") }
        }
    }

    private fun handlePhotoResult(result: ActivityResult) {
        val call = photoCall ?: return; photoCall = null
        if (result.resultCode == Activity.RESULT_OK) {
            val path = currentPhotoPath ?: run { safeReject(call, "No path"); return }
            val file = File(path)
            if (file.exists()) {
                val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                safeResolve(call, JSObject().put("uri", uri.toString()).put("webPath", uri.toString()))
            } else safeReject(call, "File missing")
        } else safeReject(call, "Cancelled")
    }

    private fun handleVideoResult(result: ActivityResult) {
        val call = videoCall ?: return; videoCall = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null)
            safeResolve(call, JSObject().put("uri", uri.toString()).put("webPath", uri.toString()))
        else safeReject(call, "Cancelled")
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("JPEG_${ts}_", ".jpg", context.cacheDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    /* ─── screen protection ──────────────────────────────── */
    private fun enableSecureScreenInternal() {
        activity?.runOnUiThread { activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun disableSecureScreenInternal() {
        activity?.runOnUiThread { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    @PluginMethod fun enableSecureScreen(call: PluginCall) { enableSecureScreenInternal(); safeResolve(call, JSObject().put("enabled", true)) }
    @PluginMethod fun setScreenshotProtection(call: PluginCall) { val e = call.getBoolean("enabled") ?: false; if (e) enableSecureScreenInternal() else disableSecureScreenInternal(); safeResolve(call, JSObject().put("enabled", e)) }
    @PluginMethod fun setScreenRecordingProtection(call: PluginCall) { val e = call.getBoolean("enabled") ?: false; if (e) enableSecureScreenInternal() else disableSecureScreenInternal(); safeResolve(call, JSObject().put("enabled", e)) }

    /* ─── biometric ──────────────────────────────────────── */
    @PluginMethod
    fun biometricAuth(call: PluginCall) {
        val act = activity ?: run { safeReject(call, "No activity"); return }
        if (!isVaultLocked()) { safeResolve(call, JSObject().put("success", true)); return }
        val bm = androidx.biometric.BiometricManager.from(act)
        when (bm.canAuthenticate(androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS -> {
                BiometricPrompt(act, ContextCompat.getMainExecutor(act),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                            lastUnlockTime = System.currentTimeMillis()
                            safeResolve(call, JSObject().put("success", true))
                        }
                        override fun onAuthenticationError(c: Int, e: CharSequence) { safeReject(call, e.toString()) }
                        override fun onAuthenticationFailed() { safeReject(call, "Failed") }
                    }
                ).authenticate(BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Secure Vault").setSubtitle("Authenticate to continue")
                    .setNegativeButtonText("Cancel").build())
            }
            else -> safeReject(call, "Biometric not supported")
        }
    }

    /* ─── misc ───────────────────────────────────────────── */
    @PluginMethod
    fun hideFolderFromGallery(call: PluginCall) {
        val path = call.getString("path") ?: run { safeReject(call, "Path required"); return }
        try {
            val folder = File(path)
            if (!folder.exists()) folder.mkdirs()
            File(folder, ".nomedia").also { if (!it.exists()) it.createNewFile() }
            safeResolve(call, JSObject().put("hidden", true))
        } catch (_: Exception) { safeReject(call, "Failed") }
    }

    @PluginMethod
    fun setAutoLock(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        val timeout = call.getInt("timeout")?.toLong() ?: AUTO_LOCK_TIME
        prefs.edit().putBoolean("autoLock", enabled).putLong("autoLockTimeout", timeout).apply()
        safeResolve(call, JSObject().put("enabled", enabled))
    }

    @PluginMethod
    fun hideVaultFromGallery(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        prefs.edit().putBoolean("hideMedia", enabled).apply()
        safeResolve(call, JSObject().put("enabled", enabled))
    }

    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            context.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                data = Uri.fromParts("package", context.packageName, null)
            })
            safeResolve(call, JSObject().put("opened", true))
        } catch (e: Exception) { safeReject(call, e.localizedMessage ?: "error") }
    }

    @PluginMethod
    fun requestMediaPermissions(call: PluginCall) {
        if (isMediaPermissionGranted()) { safeResolve(call, JSObject().put("granted", true)); return }
        requestPermissionForAlias("media", call, "requestMediaPermissionsCallback")
    }

    @PermissionCallback
    private fun requestMediaPermissionsCallback(call: PluginCall) {
        if (isMediaPermissionGranted()) safeResolve(call, JSObject().put("granted", true))
        else safeReject(call, "Denied")
    }

    /* ─── system bars ────────────────────────────────────── */
    @PluginMethod
    fun hideSystemBars(call: PluginCall) {
        activity?.runOnUiThread {
            try {
                val window = activity?.window ?: run { call.reject("No window"); return@runOnUiThread }
                val ctrl = WindowInsetsControllerCompat(window, window.decorView)
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
                ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN)
                }
                call.resolve()
            } catch (e: Exception) { call.reject("hideSystemBars: ${e.message}") }
        }
    }

    @PluginMethod
    fun showSystemBars(call: PluginCall) {
        activity?.runOnUiThread {
            try {
                val window = activity?.window ?: run { call.reject("No window"); return@runOnUiThread }
                val ctrl = WindowInsetsControllerCompat(window, window.decorView)
                ctrl.show(WindowInsetsCompat.Type.systemBars())
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    @Suppress("DEPRECATION")
                    window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
                call.resolve()
            } catch (e: Exception) { call.reject("showSystemBars: ${e.message}") }
        }
    }
}