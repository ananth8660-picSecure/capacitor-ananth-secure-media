package com.ananth.picsecure.media

import android.app.Activity
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.WindowManager
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.getcapacitor.*
import com.getcapacitor.annotation.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
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
            strings = [
                "android.permission.READ_MEDIA_AUDIO"
            ],
            alias = "audio"
        ),

        Permission(
            strings = [
                "android.permission.READ_EXTERNAL_STORAGE"
            ],
            alias = "storage"
        ),

        Permission(
            strings = ["android.permission.CAMERA"],
            alias = "camera"
        )
    ]
)
class AnanthSecureMediaPlugin : Plugin() {

    private lateinit var prefs: SharedPreferences
    private var lastUnlockTime: Long = 0
    private val AUTO_LOCK_TIME = 5 * 60 * 1000L
    private var currentPhotoPath: String? = null

    private val mediaExecutor = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtMost(4)
    )

    private lateinit var photoLauncher: ActivityResultLauncher<Intent>
    private lateinit var videoLauncher: ActivityResultLauncher<Intent>

    private var photoCall: PluginCall? = null
    private var videoCall: PluginCall? = null

    private var pendingAction: (() -> Unit)? = null
    private var pendingCall: PluginCall? = null

    override fun load() {

        prefs = context.getSharedPreferences("picsecure_settings", Activity.MODE_PRIVATE)

        photoLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result -> handlePhotoResult(result) }

        videoLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result -> handleVideoResult(result) }
    }

    override fun handleOnDestroy() {
        mediaExecutor.shutdown()
    }

    /* ----------------------------------------------------
       SETTINGS FROM APP
    ---------------------------------------------------- */

    @PluginMethod
    fun setSecuritySettings(call: PluginCall) {

        val antiScreenshot = call.getBoolean("antiScreenshot") ?: false
        val hideMedia = call.getBoolean("hideMedia") ?: false
        val autoLock = call.getBoolean("autoLock") ?: false

        prefs.edit()
            .putBoolean("antiScreenshot", antiScreenshot)
            .putBoolean("hideMedia", hideMedia)
            .putBoolean("autoLock", autoLock)
            .apply()

        if (antiScreenshot) enableSecureScreenInternal()
        else disableSecureScreenInternal()

        call.resolve(JSObject().put("saved", true))
    }

    /* ----------------------------------------------------
       AUTO LOCK
    ---------------------------------------------------- */

    private fun isVaultLocked(): Boolean {

        val enabled = prefs.getBoolean("autoLock", false)
        if (!enabled) return false

        val timeout = prefs.getLong("autoLockTimeout", AUTO_LOCK_TIME)

        return System.currentTimeMillis() - lastUnlockTime > timeout
    }

    /* ----------------------------------------------------
       PERMISSION HANDLER
    ---------------------------------------------------- */
        private fun ensureMediaPermission(call: PluginCall, action: () -> Unit) {

            if (Build.VERSION.SDK_INT >= 33) {

                if (getPermissionState("media") == PermissionState.GRANTED) {
                    action()
                } else {
                    pendingAction = action
                    pendingCall = call
                    requestPermissionForAlias("media", call, "mediaPermissionCallback")
                }

            } else {

                if (getPermissionState("storage") == PermissionState.GRANTED) {
                    action()
                } else {
                    pendingAction = action
                    pendingCall = call
                    requestPermissionForAlias("storage", call, "mediaPermissionCallback")
                }

            }
        }
            @PermissionCallback
            private fun mediaPermissionCallback(call: PluginCall) {

                val granted =
                    getPermissionState("media") == PermissionState.GRANTED ||
                    getPermissionState("storage") == PermissionState.GRANTED

                if (granted) {

                    pendingAction?.invoke()

                } else {

                    pendingCall?.reject("Media permission denied")

                }

                pendingAction = null
                pendingCall = null
            }

    /* ----------------------------------------------------
       FAST MEDIA QUERY WITH PAGING
    ---------------------------------------------------- */

    private fun queryMedia(uri: Uri, limit: Int, offset: Int): JSArray {

        val resolver: ContentResolver = context.contentResolver

        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME
        )

        val results = JSArray()

        try {

            val cursor: Cursor? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                    val bundle = Bundle().apply {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                        putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
                        putStringArray(
                            ContentResolver.QUERY_ARG_SORT_COLUMNS,
                            arrayOf(MediaStore.MediaColumns.DATE_ADDED)
                        )
                        putInt(
                            ContentResolver.QUERY_ARG_SORT_DIRECTION,
                            ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                        )
                    }

                    resolver.query(uri, projection, bundle, null)

                } else {

                    resolver.query(
                        uri,
                        projection,
                        null,
                        null,
                        "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit OFFSET $offset"
                    )

                }

            cursor?.use { c ->

                val idIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val dateIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val albumIndex = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)

                while (c.moveToNext()) {

                    val id = c.getLong(idIndex)
                    val name = c.getString(nameIndex)
                    val size = c.getLong(sizeIndex)
                    val date = c.getLong(dateIndex)
                    val album = c.getString(albumIndex)

                    val contentUri = ContentUris.withAppendedId(uri, id)

                    val item = JSObject()

                    item.put("id", id.toString())
                    item.put("name", name)
                    item.put("uri", contentUri.toString())
                    item.put("size", size)
                    item.put("createdAt", date)
                    item.put("album", album)

                    if (uri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) {

                        val thumbnailUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        item.put("thumbnail", thumbnailUri.toString())

                    } else {

                        val thumbnailUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )

                        item.put("thumbnail", thumbnailUri.toString())
                    }

                    results.put(item)
                }
            }

        } catch (_: Exception) { }

        return results
    }

    /* ----------------------------------------------------
       GET PHOTOS
    ---------------------------------------------------- */

    @PluginMethod
    fun getPhotos(call: PluginCall) {

        ensureMediaPermission(call) {

            val limit = call.getInt("limit") ?: 200
            val offset = call.getInt("offset") ?: 0

            mediaExecutor.execute {

                val items = queryMedia(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    limit,
                    offset
                )

                activity?.runOnUiThread {
                    call.resolve(JSObject().put("items", items))
                }
            }
        }
    }

    /* ----------------------------------------------------
       GET VIDEOS
    ---------------------------------------------------- */

    @PluginMethod
    fun getVideos(call: PluginCall) {

        ensureMediaPermission(call) {

            val limit = call.getInt("limit") ?: 200
            val offset = call.getInt("offset") ?: 0

            mediaExecutor.execute {

                val items = queryMedia(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    limit,
                    offset
                )

                activity?.runOnUiThread {
                    call.resolve(JSObject().put("items", items))
                }
            }
        }
    }

    /* ----------------------------------------------------
       GET AUDIO
    ---------------------------------------------------- */

    @PluginMethod
    fun getAudio(call: PluginCall) {

        ensureMediaPermission(call) {

            val limit = call.getInt("limit") ?: 200
            val offset = call.getInt("offset") ?: 0

            mediaExecutor.execute {

                val items = queryMedia(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    limit,
                    offset
                )

                activity?.runOnUiThread {
                    call.resolve(JSObject().put("items", items))
                }
            }
        }
    }


    /* ----------------------------------------------------
       GET DOCUMENTS (PDF, DOC, EXCEL, TXT)
    ---------------------------------------------------- */

    @PluginMethod
fun getDocuments(call: PluginCall) {

    ensureMediaPermission(call) {

        val limit = call.getInt("limit") ?: 200
        val offset = call.getInt("offset") ?: 0

        mediaExecutor.execute {

            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_ADDED,
                MediaStore.Files.FileColumns.MIME_TYPE
            )

            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} IN (?, ?, ?, ?, ?, ?)"
            val selectionArgs = arrayOf(
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/plain"
            )

            val uri = MediaStore.Files.getContentUri("external")
            val resolver = context.contentResolver
            val results = JSArray()

            val cursor = resolver.query(
                uri,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Files.FileColumns.DATE_ADDED} DESC LIMIT $limit OFFSET $offset"
            )

            cursor?.use { c ->

                val idIndex = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameIndex = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateIndex = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                val mimeIndex = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                while (c.moveToNext()) {

                    val id = c.getLong(idIndex)
                    val name = c.getString(nameIndex)
                    val size = c.getLong(sizeIndex)
                    val date = c.getLong(dateIndex)
                    val mime = c.getString(mimeIndex)

                    val contentUri = ContentUris.withAppendedId(uri, id)

                    val thumbnail = generateDocumentThumbnail(contentUri, mime)

                    val item = JSObject()
                    item.put("id", id.toString())
                    item.put("name", name)
                    item.put("uri", contentUri.toString())
                    item.put("size", size)
                    item.put("createdAt", date)
                    item.put("album", "Documents")
                    item.put("thumbnail", thumbnail)

                    results.put(item)
                }
            }

            activity?.runOnUiThread {
                call.resolve(JSObject().put("items", results))
            }
        }
    }
}

    private fun generateDocumentThumbnail(uri: Uri, mime: String): String {
        return try {
            when {
                mime == "application/pdf" -> {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return uri.toString()
                    val renderer = PdfRenderer(pfd)
                    val page = renderer.openPage(0)
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    renderer.close()
                    pfd.close()
                    saveBitmapToCache(bitmap)
                }
                mime.contains("word") || mime.contains("excel") -> {
                    val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    saveBitmapToCache(bitmap)
                }
                else -> uri.toString()
            }
        } catch (_: Exception) {
            uri.toString()
        }
    }

    private fun saveBitmapToCache(bitmap: Bitmap?): String {
        return try {
            if (bitmap == null) return ""
            val file = File(context.cacheDir, "thumb_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 80, it) }
            file.toURI().toString()
        } catch (_: Exception) {
            ""
        }
    }

    /* ----------------------------------------------------
       CAMERA
    ---------------------------------------------------- */

    @PluginMethod
    fun capturePhoto(call: PluginCall) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val photoFile = try { createImageFile() } catch (_: IOException) {
            call.reject("Failed to create photo file"); return
        }
        val photoURI = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", photoFile)
        intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        photoCall = call
        photoLauncher.launch(intent)
    }

    @PluginMethod
    fun captureVideo(call: PluginCall) {
        val intent = Intent(MediaStore.ACTION_VIDEO_CAPTURE)
        videoCall = call
        videoLauncher.launch(intent)
    }

    private fun handlePhotoResult(result: ActivityResult) {
        val call = photoCall ?: return
        photoCall = null
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = currentPhotoPath?.let { File(it) }?.let {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it)
            }
            uri?.let { call.resolve(JSObject().put("uri", it.toString())) } ?: call.reject("No photo returned")
        } else call.reject("Camera cancelled")
    }

    private fun handleVideoResult(result: ActivityResult) {
        val call = videoCall ?: return
        videoCall = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) call.resolve(JSObject().put("uri", uri.toString()))
        else call.reject("No video returned or cancelled")
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File = context.cacheDir
        return File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir).apply {
            currentPhotoPath = absolutePath
        }
    }

    /* ----------------------------------------------------
       SCREEN PROTECTION
    ---------------------------------------------------- */

    private fun enableSecureScreenInternal() {
        activity?.runOnUiThread { activity?.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE) }
    }

    private fun disableSecureScreenInternal() {
        activity?.runOnUiThread { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    @PluginMethod
    fun enableSecureScreen(call: PluginCall) {
        enableSecureScreenInternal()
        call.resolve(JSObject().put("enabled", true))
    }

    /* ----------------------------------------------------
       BIOMETRIC AUTH
    ---------------------------------------------------- */

    @PluginMethod
    fun biometricAuth(call: PluginCall) {
        val act = activity ?: run { call.reject("Activity not available"); return }
        if (isVaultLocked()) {
            val executor: Executor = ContextCompat.getMainExecutor(act)
            val biometricPrompt = BiometricPrompt(act, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    lastUnlockTime = System.currentTimeMillis()
                    call.resolve(JSObject().put("success", true))
                }
                override fun onAuthenticationError(code: Int, err: CharSequence) { call.reject(err.toString()) }
                override fun onAuthenticationFailed() { call.reject("Authentication failed") }
            })
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Secure Vault")
                .setSubtitle("Authenticate to continue")
                .setNegativeButtonText("Cancel")
                .build()
            biometricPrompt.authenticate(promptInfo)
        } else call.resolve(JSObject().put("success", true))
    }

    /* ----------------------------------------------------
       HIDE MEDIA FROM GALLERY
    ---------------------------------------------------- */

    @PluginMethod
    fun hideFolderFromGallery(call: PluginCall) {
        val path = call.getString("path") ?: run { call.reject("Path required"); return }
        try {
            val folder = File(path); if (!folder.exists()) folder.mkdirs()
            val noMedia = File(folder, ".nomedia"); if (!noMedia.exists()) noMedia.createNewFile()
            call.resolve(JSObject().put("hidden", true))
        } catch (_: Exception) { call.reject("Failed to hide folder") }
    }

    /* ----------------------------------------------------
       ADVANCED SECURITY CONTROLS
    ---------------------------------------------------- */

    @PluginMethod
    fun setScreenshotProtection(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        if (enabled) enableSecureScreenInternal() else disableSecureScreenInternal()
        call.resolve(JSObject().put("enabled", enabled))
    }

    @PluginMethod
    fun setScreenRecordingProtection(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        if (enabled) enableSecureScreenInternal() else disableSecureScreenInternal()
        call.resolve(JSObject().put("enabled", enabled))
    }

    @PluginMethod
    fun setAutoLock(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        val timeout = call.getInt("timeout")?.toLong() ?: AUTO_LOCK_TIME
        prefs.edit().putBoolean("autoLock", enabled).putLong("autoLockTimeout", timeout).apply()
        call.resolve(JSObject().put("enabled", enabled))
    }

    @PluginMethod
    fun hideVaultFromGallery(call: PluginCall) {
        val enabled = call.getBoolean("enabled") ?: false
        prefs.edit().putBoolean("hideMedia", enabled).apply()
        call.resolve(JSObject().put("enabled", enabled))
    }

    /* ----------------------------------------------------
        OPEN APP SETTINGS (FOR PERMISSIONS)
    ---------------------------------------------------- */
    @PluginMethod
    fun openAppSettings(call: PluginCall) {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val uri = Uri.fromParts("package", context.packageName, null)
            intent.data = uri
            context.startActivity(intent)
            call.resolve(JSObject().put("opened", true))
        } catch (e: Exception) {
            call.reject("Unable to open app settings: ${e.localizedMessage}")
        }
    }


        @PluginMethod
        fun requestMediaPermissions(call: PluginCall) {

            if (getPermissionState("media") == PermissionState.GRANTED) {

                val ret = JSObject()
                ret.put("granted", true)

                call.resolve(ret)

            } else {

                requestPermissionForAlias(
                    "media",
                    call,
                    "requestMediaPermissionsCallback"
                )
            }
        }

        @PermissionCallback
        private fun requestMediaPermissionsCallback(call: PluginCall) {

            val granted = getPermissionState("media") == PermissionState.GRANTED

            val ret = JSObject()
            ret.put("granted", granted)

            if (granted) {
                call.resolve(ret)
            } else {
                call.reject("Media permission denied")
            }
        }
}