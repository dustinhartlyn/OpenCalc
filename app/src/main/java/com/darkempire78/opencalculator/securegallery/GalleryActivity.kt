package com.darkempire78.opencalculator.securegallery

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import com.darkempire78.opencalculator.R
import androidx.appcompat.widget.PopupMenu
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Looper
import java.util.concurrent.Executors
import android.view.MenuItem
import android.view.Menu
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import android.net.Uri

class GalleryActivity : AppCompatActivity() {
    companion object {
        const val PHOTO_VIEWER_REQUEST = 1002
        private const val TAG = "GalleryActivity"
    }

    // Memory manager for bitmap lifecycle tracking
    private val memoryManager = MemoryManager

    // Data class for media thumbnails
    data class MediaThumbnail(
        val bitmap: android.graphics.Bitmap?,
        val duration: String?,
        val mediaType: MediaType
    )

    // Memory cache for optimized thumbnail loading
    private val thumbnailCache = mutableMapOf<String, MediaThumbnail?>()
    private val maxCacheSize = 50 // Limit cache to 50 items to prevent memory issues
    
    // Single thread executor for thumbnail loading to prevent too many concurrent threads
    private val thumbnailExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    
    // Track user interaction to pause thumbnail loading when user is scrolling
    private var isUserInteracting = false
    private var lastInteractionTime = 0L

    private var deleteDialog: android.app.AlertDialog? = null
    private var isDeleteMode = false
    private val selectedPhotosForDeletion = mutableSetOf<Int>()
    private var photosAdapter: RecyclerView.Adapter<MediaThumbnailViewHolder>? = null
    private var decryptedMedia = mutableListOf<MediaThumbnail?>()
    
    // Note management
    private var isNoteDeleteMode = false
    private val selectedNotesForDeletion = mutableSetOf<Int>()
    private var notesAdapter: RecyclerView.Adapter<NoteViewHolder>? = null
    private var decryptedNotes = mutableListOf<Pair<String, String>>()
    
    // Organize mode for drag-and-drop reordering
    private var isOrganizeMode = false
    private var organizeMedia = mutableListOf<MediaThumbnail?>()
    
    // Security features
    private var securityManager: SecurityManager? = null
    private var isPhotoPickerActive = false
    private var isMediaViewerActive = false
    private var isNoteEditorActive = false  // Track when note editor is open
    private var isRecreating = false
    private var isOpeningNewGallery = false  // Track when we're finishing to open a new gallery
    private var securityStartTime = 0L
    private var resumeTime = 0L
    
    // Screen off receiver for immediate security triggering
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                android.util.Log.d("SecureGallery", "Screen off detected - isPhotoPickerActive: $isPhotoPickerActive, isMediaViewerActive: $isMediaViewerActive, securityTriggered: ${TempPinHolder.securityTriggered}")
                // Only trigger security if not in photo picker, media viewer, or already triggered
                if (!isPhotoPickerActive && !isMediaViewerActive && !TempPinHolder.securityTriggered) {
                    android.util.Log.d("SecureGallery", "Triggering security due to screen off")
                    TempPinHolder.triggerSecurity("Screen turned off")
                } else {
                    android.util.Log.d("SecureGallery", "Skipping security trigger for screen off - flags: picker=$isPhotoPickerActive, viewer=$isMediaViewerActive, triggered=${TempPinHolder.securityTriggered}")
                }
            }
        }
    }
    
    // Gallery loading progress
    private var galleryLoadingIndicator: android.widget.ProgressBar? = null
    private var galleryLoadingText: android.widget.TextView? = null
    
    // Activity result launcher for photo viewer
    private val photoViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        android.util.Log.d("SecureGallery", "Media viewer returned - resultCode: ${result.resultCode}, isMediaViewerActive was: $isMediaViewerActive")
        android.util.Log.d("SecureGallery", "Setting isMediaViewerActive = false")
        isMediaViewerActive = false // Reset the flag when media viewer returns
        if (result.resultCode == RESULT_OK) {
            val returnPosition = result.data?.getIntExtra("return_position", -1) ?: -1
            android.util.Log.d("SecureGallery", "Media viewer return position: $returnPosition")
            if (returnPosition >= 0) {
                // Optionally scroll to the position in the gallery that was being viewed
                val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
                photosRecyclerView.scrollToPosition(returnPosition)
                android.util.Log.d("SecureGallery", "Scrolled gallery to position $returnPosition")
            }
        } else {
            android.util.Log.w("SecureGallery", "Media viewer returned with unexpected result code: ${result.resultCode}")
        }
    }

    // Activity result launcher for adding multiple pictures and videos
    private val addMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val currentPin = TempPinHolder.pin ?: ""
        isPhotoPickerActive = false // Reset the flag when picker returns
        if (uris.isNotEmpty()) {
            handleSelectedMedia(uris)
        }
    }
    
    // Activity result launcher for note editor
    private val noteEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isNoteEditorActive = false // Reset the flag when note editor returns
        
        if (result.resultCode == RESULT_OK) {
            val title = result.data?.getStringExtra("note_title") ?: ""
            val body = result.data?.getStringExtra("note_body") ?: ""
            val noteIndex = result.data?.getIntExtra("note_index", -1) ?: -1
            val isNewNote = result.data?.getBooleanExtra("is_new_note", true) ?: true
            
            if (title.isNotEmpty() || body.isNotEmpty()) {
                handleNoteSave(title, body, noteIndex, isNewNote)
            }
        }
    }
    
    // Activity result launcher for exporting photos directory selection
    private val exportPhotosLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            handleExportPhotos(uri)
        }
    }
    
    // Activity result launcher for exporting gallery file
    private val exportGalleryLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            handleExportGallery(uri)
        }
    }
    
    // Activity result launcher for importing gallery file
    private val importGalleryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            handleImportGallery(uri)
        }
    }

    // Handler for Add Media menu item (photos and videos)
    private fun addMediaToGallery() {
        val galleryName = intent.getStringExtra("gallery_name") ?: ""
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        
        if (gallery == null) {
            android.util.Log.e("SecureGallery", "Gallery not found: $galleryName")
            android.widget.Toast.makeText(this, "Error: Gallery not found", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        var currentPin = TempPinHolder.pin ?: ""
        
        // If PIN is empty, this means the activity was recreated and PIN was lost
        // We need to prompt the user to re-enter the PIN
        if (currentPin.isEmpty()) {
            // Show PIN dialog for re-authentication
            val builder = android.app.AlertDialog.Builder(this)
            builder.setTitle("Re-enter PIN")
            builder.setMessage("Please re-enter your PIN to add media to this gallery.")
            
            val input = android.widget.EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            builder.setView(input)
            
            builder.setPositiveButton("OK") { _, _ ->
                val enteredPin = input.text.toString()
                if (gallery.pinHash != null && CryptoUtils.verifyPin(enteredPin, gallery.salt, gallery.pinHash!!)) {
                    // Correct PIN - store it and proceed
                    TempPinHolder.pin = enteredPin
                    
                    // Now launch the photo picker
                    isPhotoPickerActive = true
                    addMediaLauncher.launch("*/*")
                } else {
                    android.widget.Toast.makeText(this, "Incorrect PIN", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            builder.setNegativeButton("Cancel") { dialog, _ ->
                dialog.cancel()
            }
            
            builder.show()
        } else {
            // PIN is available, proceed directly
            isPhotoPickerActive = true
            addMediaLauncher.launch("*/*") // Accept both images and videos
        }
    }

    private fun handleSelectedMedia(uris: List<android.net.Uri>) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        
        if (key == null) {
            android.widget.Toast.makeText(this, "Error: Unable to encrypt media. Please try again.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        
        // Show loading dialog for media import
        val progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = uris.size
            progress = 0
        }
        
        val progressText = android.widget.TextView(this).apply {
            text = "Encrypting and processing media files..."
            setPadding(0, 0, 0, 16)
        }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            addView(progressText)
            addView(progressBar)
        }
        
        val loadingDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Importing Media")
            .setView(layout)
            .setCancelable(false)
            .create()
        
        loadingDialog.show()
        
        val encryptedMedia = mutableListOf<SecureMedia>()
        val deletableUris = mutableListOf<android.net.Uri>()
        val originalUris = uris // Store original URIs for finishMediaImport
        
        // Process media in background to avoid blocking UI
        Thread {
            var processedCount = 0
            
            for (uri in uris) {
                try {
                    // Update loading dialog on UI thread
                    runOnUiThread {
                        progressBar.progress = processedCount
                        progressText.text = "Processing media ${processedCount + 1} of ${uris.size}..."
                    }
                    
                    // Determine media type based on MIME type
                    val mimeType = contentResolver.getType(uri)
                    val mediaType = when {
                        mimeType?.startsWith("image/") == true -> MediaType.PHOTO
                        mimeType?.startsWith("video/") == true -> MediaType.VIDEO
                        else -> MediaType.PHOTO // Default to photo if unknown
                    }
                    
                    val inputStream = contentResolver.openInputStream(uri)
                    if (inputStream != null) {
                            val extension = when (mediaType) {
                                MediaType.PHOTO -> ".jpg"
                                MediaType.VIDEO -> ".mp4"
                            }
                            val name = "${mediaType.name.lowercase()}_${System.currentTimeMillis()}$extension"
                            
                            // Use different storage strategies based on media type and size
                            if (mediaType == MediaType.VIDEO) {
                                // For videos, always store in external files to avoid memory issues
                                val galleryDir = java.io.File(filesDir, "secure_gallery")
                                if (!galleryDir.exists()) galleryDir.mkdirs()
                                
                                val encryptedFile = java.io.File(galleryDir, "${java.util.UUID.randomUUID()}.enc")
                                val fileOutputStream = java.io.FileOutputStream(encryptedFile)
                                
                                CryptoUtils.encryptStream(inputStream, fileOutputStream, key)
                                inputStream.close()
                                fileOutputStream.close()
                                
                                val secureMedia = SecureMedia.createWithFileStorage(
                                    name = name,
                                    date = System.currentTimeMillis(),
                                    mediaType = mediaType,
                                    encryptedFilePath = encryptedFile.absolutePath
                                )
                                
                                encryptedMedia.add(secureMedia)
                                
                                // Generate and save thumbnail in background to avoid blocking UI
                                try {
                                    VideoUtils.generateAndSaveThumbnail(this@GalleryActivity, secureMedia, key)
                                    Log.d("SecureGallery", "Background thumbnail generation completed for ${secureMedia.name}")
                                } catch (e: Exception) {
                                    Log.e("SecureGallery", "Failed to generate thumbnail in background for ${secureMedia.name}", e)
                                }
                            } else {
                                // For photos, check file size first to avoid OutOfMemoryError
                                val fileSize = try {
                                    val fileDescriptor = contentResolver.openFileDescriptor(uri, "r")
                                    fileDescriptor?.statSize ?: 0
                                } catch (e: Exception) {
                                    0
                                }
                                
                                // Use file storage for photos larger than 50MB
                                if (fileSize > 50 * 1024 * 1024) {
                                    val galleryDir = java.io.File(filesDir, "secure_gallery")
                                    if (!galleryDir.exists()) galleryDir.mkdirs()
                                    
                                    val encryptedFile = java.io.File(galleryDir, "${java.util.UUID.randomUUID()}.enc")
                                    val fileOutputStream = java.io.FileOutputStream(encryptedFile)
                                    
                                    CryptoUtils.encryptStream(inputStream, fileOutputStream, key)
                                    inputStream.close()
                                    fileOutputStream.close()
                                    
                                    encryptedMedia.add(SecureMedia.createWithFileStorage(
                                        name = name,
                                        date = System.currentTimeMillis(),
                                        mediaType = mediaType,
                                        encryptedFilePath = encryptedFile.absolutePath
                                    ))
                                } else {
                                    // For smaller photos, keep in-memory storage
                                    val bytes = inputStream.readBytes()
                                    inputStream.close()
                                    
                                    val (iv, encrypted) = CryptoUtils.encrypt(bytes, key)
                                    val combined = iv + encrypted
                                    
                                    encryptedMedia.add(SecureMedia(
                                        _encryptedData = combined,
                                        name = name,
                                        date = System.currentTimeMillis(),
                                        mediaType = mediaType
                                    ))
                                }
                            }
                            
                            // Test if URI can actually be deleted before adding to deletable list
                            val scheme = uri.scheme
                            val authority = uri.authority
                            if (scheme == "content" && authority != "com.android.providers.media.photopicker" && !uri.toString().contains("picker_get_content")) {
                                // Perform a more thorough check to see if deletion is actually possible
                                if (canDeleteUri(uri)) {
                                    deletableUris.add(uri)
                                }
                            }
                        }
                    processedCount++
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to encrypt media: $uri", e)
                    processedCount++
                }
            }
            
            // Continue processing on UI thread
            runOnUiThread {
                loadingDialog.dismiss()
                finishMediaImport(encryptedMedia, deletableUris, originalUris, gallery, key)
            }
        }.start()
    }
    
    // Optimized thumbnail loading with caching
    private fun loadThumbnailOptimized(mediaItem: SecureMedia, key: javax.crypto.spec.SecretKeySpec?, position: Int): MediaThumbnail? {
        // Debug check: warn if this is called on main thread
        if (Looper.myLooper() == Looper.getMainLooper()) {
            android.util.Log.w("SecureGallery", "WARNING: loadThumbnailOptimized called on main thread! This may cause ANR!")
        }
        
        val cacheKey = "${mediaItem.id}_${mediaItem.mediaType}"
        
        // Check cache first
        thumbnailCache[cacheKey]?.let { cachedThumbnail ->
            Log.d("SecureGallery", "Using cached thumbnail for position $position")
            return cachedThumbnail
        }
        
        // Load pre-generated thumbnail instead of generating on-demand
        val thumbnail = try {
            // Always try to load encrypted thumbnail first
            val galleryName = intent.getStringExtra("gallery_name") ?: ""
            val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
            
            android.util.Log.d("SecureGallery", "Loading thumbnail for ${mediaItem.name} (${mediaItem.mediaType}) at position $position, path: $thumbnailPath")
            
            if (File(thumbnailPath).exists()) {
                // Load the pre-generated encrypted thumbnail
                android.util.Log.d("SecureGallery", "Thumbnail file exists, loading encrypted thumbnail")
                val thumbnailBitmap = ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key!!)
                val duration = if (mediaItem.mediaType == MediaType.VIDEO) {
                    android.util.Log.d("SecureGallery", "Getting video duration for: ${mediaItem.name}")
                    VideoUtils.getVideoDuration(mediaItem, key)
                } else null
                
                if (thumbnailBitmap != null) {
                    android.util.Log.d("SecureGallery", "Successfully loaded thumbnail for: ${mediaItem.name}, size: ${thumbnailBitmap.width}x${thumbnailBitmap.height}")
                } else {
                    android.util.Log.w("SecureGallery", "Thumbnail bitmap is null for: ${mediaItem.name}")
                }
                MediaThumbnail(thumbnailBitmap, duration, mediaItem.mediaType)
            } else {
                // Fallback: generate thumbnail if pre-generated one doesn't exist (for backwards compatibility)
                android.util.Log.w("SecureGallery", "No pre-generated thumbnail found for ${mediaItem.name}, generating on-demand")
                generateThumbnailFallback(mediaItem, key)
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Failed to load thumbnail for: ${mediaItem.name}", e)
            // Fallback to on-demand generation if loading pre-generated thumbnail fails
            generateThumbnailFallback(mediaItem, key)
        }
        
        // Cache the result (limit cache size)
        if (thumbnail != null) {
            if (thumbnailCache.size >= maxCacheSize) {
                // Remove oldest entries
                val oldestKey = thumbnailCache.keys.first()
                val oldThumbnail = thumbnailCache[oldestKey]
                if (oldThumbnail?.bitmap != null && !oldThumbnail.bitmap.isRecycled) {
                    // Check if bitmap is still being used by the UI
                    try {
                        memoryManager.unmarkBitmapActive(oldestKey)
                        oldThumbnail.bitmap.recycle()
                    } catch (e: Exception) {
                        // Bitmap may still be in use, don't recycle
                        Log.w(TAG, "Could not recycle bitmap for $oldestKey: ${e.message}")
                    }
                }
                thumbnailCache.remove(oldestKey)
            }
            // Mark new bitmap as active
            if (thumbnail.bitmap != null) {
                memoryManager.markBitmapActive(cacheKey, thumbnail.bitmap)
            }
            thumbnailCache[cacheKey] = thumbnail
        }
        
        return thumbnail
    }
    
    // Fallback function for generating thumbnails on-demand (for backwards compatibility)
    private fun generateThumbnailFallback(mediaItem: SecureMedia, key: javax.crypto.spec.SecretKeySpec?): MediaThumbnail? {
        return when (mediaItem.mediaType) {
            MediaType.PHOTO -> {
                try {
                    // Try to load encrypted thumbnail first (newer format)
                    val galleryName = intent.getStringExtra("gallery_name") ?: ""
                    val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
                    val cachedThumbnail = if (File(thumbnailPath).exists()) {
                        ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key!!)
                    } else {
                        null
                    }
                    
                    val bitmap = if (cachedThumbnail != null) {
                        cachedThumbnail
                    } else {
                        // Fall back to generating thumbnail from original data
                        val encryptedData = mediaItem.getEncryptedData()
                        if (encryptedData.size < 16) {
                            Log.e("SecureGallery", "Encrypted data too small for photo: ${mediaItem.name}")
                            return null
                        }
                        val iv = encryptedData.copyOfRange(0, 16)
                        val ct = encryptedData.copyOfRange(16, encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ct, key!!)
                        
                        // Create a very small thumbnail to save memory and prevent ANR
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inSampleSize = 16 // Scale down by factor of 16 for extremely small thumbnails
                            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // Use less memory
                            inTempStorage = ByteArray(16 * 1024) // Use smaller temp storage
                        }
                        android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
                    }
                    
                    // Clear memory immediately
                    System.gc()
                    
                    MediaThumbnail(bitmap, null, mediaItem.mediaType)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to create fallback thumbnail for photo: ${mediaItem.name}", e)
                    null
                }
            }
            MediaType.VIDEO -> {
                try {
                    // Try to load encrypted thumbnail first (newer format)
                    val galleryName = intent.getStringExtra("gallery_name") ?: ""
                    val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
                    val cachedThumbnail = if (File(thumbnailPath).exists()) {
                        ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key!!)
                    } else {
                        // Fall back to old VideoUtils cache (unencrypted)
                        VideoUtils.loadCachedThumbnail(this, mediaItem)
                    }
                    val thumbnail = if (cachedThumbnail != null) {
                        cachedThumbnail
                    } else {
                        // For videos, use ThumbnailGenerator to handle the thumbnail creation and storage
                        try {
                            val thumbnailPath = if (mediaItem.usesExternalStorage()) {
                                ThumbnailGenerator.generateVideoThumbnailFromFile(
                                    this, 
                                    mediaItem.filePath!!, 
                                    mediaItem.id.toString(), 
                                    intent.getStringExtra("gallery_name") ?: "", 
                                    key!!
                                )
                            } else {
                                // For internal storage, decrypt and use bytes
                                val encryptedData = mediaItem.getEncryptedData()
                                val iv = encryptedData.copyOfRange(0, 16)
                                val ct = encryptedData.copyOfRange(16, encryptedData.size)
                                val decryptedBytes = CryptoUtils.decrypt(iv, ct, key!!)
                                ThumbnailGenerator.generateVideoThumbnail(
                                    this,
                                    decryptedBytes,
                                    mediaItem.id.toString(),
                                    intent.getStringExtra("gallery_name") ?: "",
                                    key!!
                                )
                            }
                            
                            // ThumbnailGenerator returns the path to cached thumbnail, 
                            // so we need to load the actual bitmap for display
                            if (thumbnailPath != null) {
                                // Try to load encrypted thumbnail first
                                val galleryName = intent.getStringExtra("gallery_name") ?: ""
                                val encryptedThumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
                                val cachedThumbnail = if (File(encryptedThumbnailPath).exists()) {
                                    ThumbnailGenerator.loadEncryptedThumbnail(encryptedThumbnailPath, key!!)
                                } else {
                                    // Fall back to old VideoUtils cache if no encrypted thumbnail
                                    VideoUtils.loadCachedThumbnail(this, mediaItem)
                                }
                                cachedThumbnail ?: VideoUtils.generateVideoThumbnailFromFile(
                                    if (mediaItem.usesExternalStorage()) mediaItem.filePath!! else thumbnailPath, 
                                    key!!
                                )
                            } else {
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("SecureGallery", "Failed to generate streaming video thumbnail", e)
                            null
                        }
                    }
                    val duration = VideoUtils.getVideoDuration(mediaItem, key!!)
                    MediaThumbnail(thumbnail, duration, mediaItem.mediaType)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to create fallback thumbnail for video: ${mediaItem.name}", e)
                    null
                }
            }
        }
    }
    
    // Check if we should pause thumbnail loading due to user interaction (optimized for pre-generated thumbnails)
    private fun shouldPauseThumbnailLoading(): Boolean {
        val currentTime = System.currentTimeMillis()
        return isUserInteracting || (currentTime - lastInteractionTime < 500) // Reduced from 2000ms to 500ms
    }
    
    // Load only initial thumbnails quickly, then load rest in background
    private fun loadInitialThumbnails(media: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec) {
        Log.d(TAG, "=== MEDIA LOADING START ===")
        Log.d(TAG, "Starting thumbnail loading for ${media.size} media items")
        Log.d(TAG, "Media items details:")
        media.forEachIndexed { index, item ->
            Log.d(TAG, "  [$index] ${item.name} (${item.mediaType}) - ID: ${item.id}, Size: ${item.getMediaSize()}")
        }
        
        // Show loading indicator in the UI instead of blocking dialog
        if (media.isNotEmpty()) {
            val loadingContainer = findViewById<android.widget.LinearLayout>(R.id.loadingContainer)
            galleryLoadingIndicator = findViewById<android.widget.ProgressBar>(R.id.loadingProgressBar)
            galleryLoadingText = findViewById<android.widget.TextView>(R.id.loadingText)
            
            Log.d(TAG, "Setting up loading UI - container: ${loadingContainer != null}, indicator: ${galleryLoadingIndicator != null}, text: ${galleryLoadingText != null}")
            
            loadingContainer?.visibility = android.view.View.VISIBLE
            galleryLoadingIndicator?.visibility = android.view.View.VISIBLE
            galleryLoadingText?.visibility = android.view.View.VISIBLE
            galleryLoadingIndicator?.max = media.size
            galleryLoadingIndicator?.progress = 0
            galleryLoadingText?.text = "Loading gallery..."
            
            Log.d(TAG, "Loading UI setup complete - max: ${media.size}, progress: 0")
        }
        
        // Immediately initialize the decryptedMedia list with the correct size
        runOnUiThread {
            Log.d(TAG, "Initializing decryptedMedia list on UI thread")
            decryptedMedia.clear()
            // Pre-fill with nulls for all media items
            repeat(media.size) { decryptedMedia.add(null) }
            Log.d(TAG, "DecryptedMedia list initialized with ${decryptedMedia.size} null entries")
            
            // Notify adapter immediately so it knows the full item count
            photosAdapter?.notifyDataSetChanged()
            Log.d(TAG, "Adapter notified of initial data change")
        }
        
        // Load thumbnails in background without any artificial limits
        thumbnailExecutor.execute {
            Log.d(TAG, "Starting background thumbnail loading thread")
            
            var loadedCount = 0
            var successCount = 0
            var failureCount = 0
            val updateBatchSize = 3 // Update UI every 3 thumbnails to reduce main thread pressure
            
            media.forEachIndexed { index, mediaItem ->
                try {
                    Log.d(TAG, "Loading thumbnail [$index/${media.size}]: ${mediaItem.name}")
                    val startTime = System.currentTimeMillis()
                    
                    val thumbnail = loadThumbnailOptimized(mediaItem, key, index)
                    val loadTime = System.currentTimeMillis() - startTime
                    
                    if (thumbnail != null) {
                        successCount++
                        Log.d(TAG, "Thumbnail loaded successfully [$index]: ${mediaItem.name} in ${loadTime}ms")
                        runOnUiThread {
                            if (index < decryptedMedia.size) {
                                decryptedMedia[index] = thumbnail
                                Log.v(TAG, "Updated decryptedMedia[$index] with thumbnail")
                                // Only notify for individual items occasionally to reduce UI pressure
                                if (index % updateBatchSize == 0 || index == media.size - 1) {
                                    photosAdapter?.notifyItemChanged(index)
                                    Log.v(TAG, "Notified adapter of item change at $index")
                                }
                                
                                loadedCount++
                                // Update loading indicator less frequently
                                if (index % updateBatchSize == 0 || index == media.size - 1) {
                                    galleryLoadingIndicator?.progress = loadedCount
                                    galleryLoadingText?.text = "Loading thumbnails ($loadedCount/${media.size})..."
                                    Log.v(TAG, "Updated loading progress: $loadedCount/${media.size}")
                                }
                            } else {
                                Log.e(TAG, "Index $index out of bounds for decryptedMedia (size: ${decryptedMedia.size})")
                            }
                        }
                    } else {
                        failureCount++
                        Log.w(TAG, "Thumbnail loading failed [$index]: ${mediaItem.name} in ${loadTime}ms")
                        runOnUiThread {
                            loadedCount++
                            // Update progress even for failed thumbnails, but less frequently
                            if (index % updateBatchSize == 0 || index == media.size - 1) {
                                galleryLoadingIndicator?.progress = loadedCount
                                galleryLoadingText?.text = "Loading thumbnails ($loadedCount/${media.size})..."
                                Log.v(TAG, "Updated loading progress (failed): $loadedCount/${media.size}")
                            }
                        }
                    }
                    
                    // Brief pause to prevent overwhelming the system - increased for stability
                    Thread.sleep(50) // Increased from 25ms to reduce main thread pressure
                    
                } catch (e: Exception) {
                    failureCount++
                    Log.e(TAG, "Exception loading thumbnail [$index]: ${mediaItem.name}", e)
                    runOnUiThread {
                        loadedCount++
                        // Update progress even for failed thumbnails, but less frequently
                        if (index % updateBatchSize == 0 || index == media.size - 1) {
                            galleryLoadingIndicator?.progress = loadedCount
                            galleryLoadingText?.text = "Loading thumbnails ($loadedCount/${media.size})..."
                            Log.v(TAG, "Updated loading progress (exception): $loadedCount/${media.size}")
                        }
                    }
                }
            }
            
            // Final comprehensive UI update
            Log.d(TAG, "Thumbnail loading complete - Success: $successCount, Failed: $failureCount, Total: ${media.size}")
            runOnUiThread {
                val actualLoadedCount = decryptedMedia.count { it != null }
                Log.d(TAG, "Final UI update - actualLoadedCount: $actualLoadedCount, decryptedMedia.size: ${decryptedMedia.size}")
                
                // Final batch update - notify all items at once for efficiency
                photosAdapter?.notifyDataSetChanged()
                Log.d(TAG, "Final adapter notification sent")
                
                // Force RecyclerView to recalculate everything
                val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
                photosRecyclerView.requestLayout()
                photosRecyclerView.invalidate()
                Log.d(TAG, "RecyclerView layout forced")
                
                // Hide loading indicator AFTER all UI operations are complete
                val loadingContainer = findViewById<android.widget.LinearLayout>(R.id.loadingContainer)
                loadingContainer?.visibility = android.view.View.GONE
                galleryLoadingIndicator?.visibility = android.view.View.GONE
                galleryLoadingText?.visibility = android.view.View.GONE
            }
        }
    }

    private fun loadThumbnailsAsync(media: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec) {
        Thread {
            // Find the starting index for new media
            val startIndex = decryptedMedia.size
            
            // First extend the decryptedMedia list to accommodate new items
            // BUT ensure we don't exceed the actual media count in the gallery
            val galleryName = intent.getStringExtra("gallery_name") ?: return@Thread
            val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return@Thread
            val maxSize = gallery.media.size
            
            runOnUiThread {
                // Only add the exact number of new items needed, don't exceed actual media count
                val itemsToAdd = minOf(media.size, maxSize - decryptedMedia.size)
                if (itemsToAdd > 0) {
                    repeat(itemsToAdd) { decryptedMedia.add(null) }
                    photosAdapter?.notifyDataSetChanged()
                }
            }
            
            // Load thumbnails for new media
            media.forEachIndexed { localIndex, mediaItem ->
                val globalIndex = startIndex + localIndex
                if (globalIndex >= maxSize) {
                    return@forEachIndexed
                }
                
                val thumbnail = loadThumbnailOptimized(mediaItem, key, globalIndex)
                if (thumbnail != null) {
                    runOnUiThread {
                        if (globalIndex < decryptedMedia.size) {
                            decryptedMedia[globalIndex] = thumbnail
                            photosAdapter?.notifyItemChanged(globalIndex)
                        }
                    }
                }
                
                Thread.sleep(50)
            }
        }.start()
    }
    
    // Load missing thumbnails within the existing range (for gaps)
    private fun loadMissingThumbnailsInRange(media: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec) {
        Thread {
            
            media.forEachIndexed { index, mediaItem ->
                if (index < decryptedMedia.size && decryptedMedia[index] == null) {
                    val thumbnail = loadThumbnailOptimized(mediaItem, key, index)
                    if (thumbnail != null) {
                        runOnUiThread {
                            decryptedMedia[index] = thumbnail
                            photosAdapter?.notifyItemChanged(index)
                        }
                    }
                }
            }
        }.start()
    }
    
    // Preload thumbnails for smooth scrolling
    private fun preloadThumbnails(centerPosition: Int, gallery: Gallery, key: javax.crypto.spec.SecretKeySpec?) {
        if (key == null) return
        
        thumbnailExecutor.execute {
            val preloadRange = 2 // Reduced preload range to prevent overwhelming
            val startIndex = maxOf(0, centerPosition - preloadRange)
            val endIndex = minOf(gallery.media.size - 1, centerPosition + preloadRange)
            
            for (i in startIndex..endIndex) {
                if (i != centerPosition && i < gallery.media.size) {
                    val mediaItem = gallery.media[i]
                    loadThumbnailOptimized(mediaItem, key, i)
                    
                    // Small delay between preload thumbnails
                    Thread.sleep(25)
                }
            }
        }
    }
    
    private fun finishMediaImport(encryptedMedia: List<SecureMedia>, deletableUris: List<android.net.Uri>, originalUris: List<android.net.Uri>, gallery: Gallery, key: javax.crypto.spec.SecretKeySpec?) {
        
        if (key == null) {
            android.util.Log.e("SecureGallery", "Cannot finish media import: key is null")
            android.widget.Toast.makeText(this, "Error: Unable to complete media import", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        
        // Add encrypted media to gallery
        gallery.media.addAll(encryptedMedia)
        // Save gallery data (this will persist the added media)
        GalleryManager.setContext(this)
        GalleryManager.saveGalleries()
        
        // Generate and save encrypted thumbnails for the new media during import
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        
        // Move thumbnail generation to background thread to prevent memory issues
        Thread {
            var processedCount = 0
            encryptedMedia.forEachIndexed { index, mediaItem ->
                try {
                    
                    when (mediaItem.mediaType) {
                        MediaType.PHOTO -> {
                            // Decrypt the photo data first to generate thumbnail
                            val encryptedData = mediaItem.getEncryptedData()
                            val iv = encryptedData.copyOfRange(0, 16)
                            val ct = encryptedData.copyOfRange(16, encryptedData.size)
                            val decryptedBytes = CryptoUtils.decrypt(iv, ct, key!!)
                            
                            // Generate encrypted thumbnail for photo
                            val thumbnailPath = ThumbnailGenerator.generatePhotoThumbnail(this@GalleryActivity, decryptedBytes, mediaItem.id.toString(), galleryName, key)
                            if (thumbnailPath != null) {
                                processedCount++
                            }
                        }
                        MediaType.VIDEO -> {
                            // Use ThumbnailGenerator for consistent thumbnail handling
                            android.util.Log.d("SecureGallery", "Processing video thumbnail for: ${mediaItem.name}, id: ${mediaItem.id}")
                            try {
                                val filePath = if (mediaItem.usesExternalStorage()) {
                                    android.util.Log.d("SecureGallery", "Video uses external storage: ${mediaItem.filePath}")
                                    mediaItem.filePath!!
                                } else {
                                    // For internal storage, create a temporary file
                                    android.util.Log.d("SecureGallery", "Video uses internal storage, creating temp file")
                                    val tempFile = File.createTempFile("video_thumb", ".tmp", cacheDir)
                                    tempFile.writeBytes(mediaItem.getEncryptedData())
                                    android.util.Log.d("SecureGallery", "Created temp file: ${tempFile.absolutePath}, size: ${tempFile.length()} bytes")
                                    tempFile.absolutePath
                                }
                                
                                android.util.Log.d("SecureGallery", "Generating video thumbnail from: $filePath")
                                val thumbnailPath = ThumbnailGenerator.generateVideoThumbnailFromFile(
                                    this@GalleryActivity, 
                                    filePath, 
                                    mediaItem.id.toString(), 
                                    galleryName, 
                                    key!!
                                )
                                
                                if (thumbnailPath != null) {
                                    android.util.Log.d("SecureGallery", "Video thumbnail generated successfully: $thumbnailPath")
                                    processedCount++
                                } else {
                                    android.util.Log.w("SecureGallery", "Video thumbnail generation failed for: ${mediaItem.name}")
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("SecureGallery", "Error processing video thumbnail for: ${mediaItem.name}", e)
                                // Force garbage collection
                                System.gc()
                                Thread.sleep(500)
                            }
                        }
                    }
                    
                    // Update UI with progress
                    runOnUiThread {
                        // Show progress to user
                        val progress = "${processedCount}/${encryptedMedia.size} thumbnails processed"
                    }
                    
                    // Force garbage collection and add delay between items to prevent memory buildup
                    System.gc()
                    Thread.sleep(100) // Small delay to prevent overwhelming the system
                    
                } catch (e: OutOfMemoryError) {
                    android.util.Log.e("SecureGallery", "Out of memory while processing: ${mediaItem.name}. Forcing garbage collection.", e)
                    System.gc()
                    Thread.sleep(500) // Longer delay on memory error
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to generate thumbnail during import for: ${mediaItem.name}", e)
                }
            }
            
            // After thumbnail generation is complete, load them on UI thread
            runOnUiThread {
                loadGeneratedThumbnails(encryptedMedia, galleryName, key)
                
                // Only prompt to delete originals if we have deletable URIs
                if (deletableUris.isNotEmpty()) {
                    deleteDialog = android.app.AlertDialog.Builder(this@GalleryActivity)
                        .setTitle("Delete Original Files?")
                        .setMessage("Do you want to delete the original files from your device? (${deletableUris.size} of ${originalUris.size} files can be deleted)")
                        .setPositiveButton("Delete") { _, _ ->
                            var deletedCount = 0
                            var failedCount = 0
                            for (uri in deletableUris) {
                                try {
                                    contentResolver.delete(uri, null, null)
                                    deletedCount++
                                } catch (e: SecurityException) {
                                    android.util.Log.e("SecureGallery", "Permission denied deleting file: $uri - ${e.message}")
                                    failedCount++
                                } catch (e: Exception) {
                                    android.util.Log.e("SecureGallery", "Failed to delete original file: $uri", e)
                                    failedCount++
                                }
                            }
                            val message = if (failedCount > 0) {
                                "Deleted $deletedCount files, failed to delete $failedCount"
                            } else {
                                "Original files deleted ($deletedCount)"
                            }
                            Toast.makeText(this@GalleryActivity, message, Toast.LENGTH_SHORT).show()
                            deleteDialog = null
                        }
                        .setNegativeButton("Keep") { _, _ ->
                            deleteDialog = null
                        }
                        .create()
                    deleteDialog?.show()
                } else {
                    // No deletable files - show info message if files came from photo picker
                    val hasPickerUris = originalUris.any { uri -> 
                        uri.authority == "com.android.providers.media.photopicker" || uri.toString().contains("picker_get_content")
                    }
                    if (hasPickerUris) {
                        Toast.makeText(this@GalleryActivity, "Media imported successfully. Original files remain in your gallery.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
        
        // Immediately show the UI without thumbnails - they'll be loaded once generated
        photosAdapter?.notifyDataSetChanged()
    }
    
    // Load thumbnails after they've been generated in background
    private fun loadGeneratedThumbnails(encryptedMedia: List<SecureMedia>, galleryName: String, key: javax.crypto.spec.SecretKeySpec) {
        
        // Find the starting position for new media in the decryptedMedia list
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val allMedia = gallery.media
        
        // Calculate where the new media starts in the full media list
        val startIndexInFullList = allMedia.size - encryptedMedia.size
        
        // Load the pre-generated thumbnails for immediate display
        encryptedMedia.forEachIndexed { localIndex, mediaItem ->
            val globalIndex = startIndexInFullList + localIndex
            
            try {
                val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
                if (File(thumbnailPath).exists()) {
                    val thumbnailBitmap = ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key)
                    val duration = if (mediaItem.mediaType == MediaType.VIDEO) {
                        VideoUtils.getVideoDuration(mediaItem, key)
                    } else null
                    val thumbnail = MediaThumbnail(thumbnailBitmap, duration, mediaItem.mediaType)
                    
                    // Ensure decryptedMedia list is large enough
                    while (decryptedMedia.size <= globalIndex) {
                        decryptedMedia.add(null)
                    }
                    
                    // Set the thumbnail at the correct position
                    decryptedMedia[globalIndex] = thumbnail
                    photosAdapter?.notifyItemChanged(globalIndex)
                    
                } else {
                    android.util.Log.w("SecureGallery", "Thumbnail file not found for: ${mediaItem.name}")
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to load generated thumbnail for: ${mediaItem.name}", e)
            }
        }
        
    }

    private fun enterDeleteMode() {
        isDeleteMode = true
        selectedPhotosForDeletion.clear()
        
        // Update the action bar
        supportActionBar?.title = "Select photos to delete"
        
        // Show delete mode buttons
        findViewById<android.widget.Button>(R.id.deleteButton).visibility = android.view.View.VISIBLE
        findViewById<android.widget.Button>(R.id.cancelButton).visibility = android.view.View.VISIBLE
        
        // Refresh the adapter to show checkboxes
        photosAdapter?.notifyDataSetChanged()
    }

    private fun exitDeleteMode() {
        isDeleteMode = false
        selectedPhotosForDeletion.clear()
        
        // Restore the original action bar title
        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        supportActionBar?.title = galleryName
        
        // Hide delete mode buttons
        findViewById<android.widget.Button>(R.id.deleteButton).visibility = android.view.View.GONE
        findViewById<android.widget.Button>(R.id.cancelButton).visibility = android.view.View.GONE
        
        // Refresh the adapter to hide checkboxes
        photosAdapter?.notifyDataSetChanged()
    }

    private fun deleteSelectedPhotos() {
        if (selectedPhotosForDeletion.isEmpty()) return
        
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        // Sort indices in descending order to avoid index shifting issues
        val sortedIndices = selectedPhotosForDeletion.sortedDescending()
        
        for (index in sortedIndices) {
            if (index < gallery.media.size) {
                gallery.media.removeAt(index)
            }
        }
        
        // Save gallery data
        GalleryManager.saveGalleries()
        
        // Exit delete mode and refresh UI
        exitDeleteMode()
        safeRecreate()
    }
    
    private fun enterNoteDeleteMode() {
        isNoteDeleteMode = true
        selectedNotesForDeletion.clear()
        
        // Show note delete mode buttons
        findViewById<android.widget.Button>(R.id.deleteNotesButton).visibility = android.view.View.VISIBLE
        findViewById<android.widget.Button>(R.id.cancelNotesButton).visibility = android.view.View.VISIBLE
        
        // Refresh the adapter to show checkboxes
        notesAdapter?.notifyDataSetChanged()
    }

    private fun exitNoteDeleteMode() {
        isNoteDeleteMode = false
        selectedNotesForDeletion.clear()
        
        // Hide note delete mode buttons
        findViewById<android.widget.Button>(R.id.deleteNotesButton).visibility = android.view.View.GONE
        findViewById<android.widget.Button>(R.id.cancelNotesButton).visibility = android.view.View.GONE
        
        // Refresh the adapter to hide checkboxes
        notesAdapter?.notifyDataSetChanged()
    }
    
    private fun openNoteEditor(noteIndex: Int, title: String, body: String) {
        isNoteEditorActive = true // Set flag when launching note editor
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra("gallery_name", this.intent.getStringExtra("gallery_name"))
        intent.putExtra("note_index", noteIndex)
        intent.putExtra("note_title", title)
        intent.putExtra("note_body", body)
        noteEditorLauncher.launch(intent)
    }
    
    private fun handleNoteSave(title: String, body: String, noteIndex: Int, isNewNote: Boolean) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        if (key != null) {
            try {
                val (ivTitle, ctTitle) = CryptoUtils.encrypt(title.toByteArray(Charsets.UTF_8), key)
                val (ivBody, ctBody) = CryptoUtils.encrypt(body.toByteArray(Charsets.UTF_8), key)
                val encryptedTitle = ivTitle + ctTitle
                val encryptedBody = ivBody + ctBody
                
                val note = SecureNote(encryptedTitle = encryptedTitle, encryptedBody = encryptedBody, date = System.currentTimeMillis())
                
                if (isNewNote) {
                    gallery.notes.add(note)
                } else {
                    gallery.notes[noteIndex] = note
                }
                
                GalleryManager.saveGalleries()
                
                // Refresh the notes display without recreating the activity
                runOnUiThread {
                    refreshNotesDisplay()
                }
                android.widget.Toast.makeText(this, "Note saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to encrypt note", e)
            }
        }
    }
    
    private fun refreshNotesDisplay() {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        if (key != null) {
            // Decrypt and display notes
            decryptedNotes.clear()
            for (note in gallery.notes) {
                try {
                    // Split encrypted data into IV and ciphertext for title
                    val titleData = note.encryptedTitle
                    val titleIv = titleData.sliceArray(0..15)
                    val titleCiphertext = titleData.sliceArray(16 until titleData.size)
                    val titleBytes = CryptoUtils.decrypt(titleIv, titleCiphertext, key)
                    
                    // Split encrypted data into IV and ciphertext for body
                    val bodyData = note.encryptedBody
                    val bodyIv = bodyData.sliceArray(0..15)
                    val bodyCiphertext = bodyData.sliceArray(16 until bodyData.size)
                    val bodyBytes = CryptoUtils.decrypt(bodyIv, bodyCiphertext, key)
                    
                    val title = String(titleBytes, Charsets.UTF_8)
                    val body = String(bodyBytes, Charsets.UTF_8)
                    decryptedNotes.add(Pair(title, body))
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to decrypt note", e)
                    decryptedNotes.add(Pair("Error", "Failed to decrypt note"))
                }
            }
            
            // Update the notes adapter
            notesAdapter?.notifyDataSetChanged() ?: android.util.Log.w("SecureGallery", "Notes adapter is null!")
        } else {
            android.util.Log.w("SecureGallery", "Cannot refresh notes display - key is null")
        }
    }

    private fun deleteSelectedNotes() {
        if (selectedNotesForDeletion.isEmpty()) return
        
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        val sortedIndices = selectedNotesForDeletion.sortedDescending()
        for (index in sortedIndices) {
            if (index < gallery.notes.size) {
                gallery.notes.removeAt(index)
            }
        }
        
        GalleryManager.saveGalleries()
        
        // Exit delete mode and refresh UI
        exitNoteDeleteMode()
        refreshNotesDisplay()
        android.widget.Toast.makeText(this, "Selected notes deleted", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        val currentPin = TempPinHolder.pin ?: ""
        deleteDialog?.dismiss()
        deleteDialog = null
        
        // Shutdown thumbnail executor to prevent memory leaks
        thumbnailExecutor.shutdown()
        try {
            if (!thumbnailExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                thumbnailExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            thumbnailExecutor.shutdownNow()
        }
        
        // Clean up memory cache
        thumbnailCache.values.forEach { thumbnail ->
            thumbnail?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        thumbnailCache.clear()
        
        // Clean up decrypted media bitmaps
        decryptedMedia.forEach { thumbnail ->
            thumbnail?.bitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
        }
        decryptedMedia.clear()
        
        cleanupSecurity()
        
        // Only clear PIN when activity is actually finishing and not opening a new gallery
        if (isFinishing && !isOpeningNewGallery) {
            TempPinHolder.clear()
        } else if (isFinishing && isOpeningNewGallery) {
        } else {
        }
        
        // Clean up loading indicator references
        galleryLoadingIndicator = null
        galleryLoadingText = null
        
        super.onDestroy()
    }
    
    override fun onPause() {
        val currentPin = TempPinHolder.pin ?: ""
        android.util.Log.d("SecureGallery", "onPause() called - isPhotoPickerActive: $isPhotoPickerActive, isMediaViewerActive: $isMediaViewerActive, isNoteEditorActive: $isNoteEditorActive")
        super.onPause()
        
        // Trigger security immediately when app loses focus
        if (!isPhotoPickerActive && !isMediaViewerActive && !TempPinHolder.securityTriggered) {
            android.util.Log.d("SecureGallery", "Triggering security due to onPause")
            TempPinHolder.triggerSecurity("App lost focus (onPause)")
        } else {
            android.util.Log.d("SecureGallery", "Skipping security trigger in onPause - flags: picker=$isPhotoPickerActive, viewer=$isMediaViewerActive, triggered=${TempPinHolder.securityTriggered}")
        }
        
        // Disable security monitoring during pause
        securityManager?.disable()
    }
    
    override fun onStop() {
        val currentPin = TempPinHolder.pin ?: ""
        android.util.Log.d("SecureGallery", "onStop() called - isPhotoPickerActive: $isPhotoPickerActive, isMediaViewerActive: $isMediaViewerActive, isRecreating: $isRecreating")
        super.onStop()
        
        if (!isPhotoPickerActive && !isMediaViewerActive && !isRecreating) {
            // Trigger security immediately when app goes to background
            if (!TempPinHolder.securityTriggered) {
                android.util.Log.d("SecureGallery", "Triggering security due to onStop")
                TempPinHolder.triggerSecurity("App backgrounded (onStop)")
            } else {
                android.util.Log.d("SecureGallery", "Security already triggered, skipping onStop trigger")
            }
        } else {
            android.util.Log.d("SecureGallery", "Skipping security trigger in onStop - flags: picker=$isPhotoPickerActive, viewer=$isMediaViewerActive, recreating=$isRecreating")
        }
    }
    
    override fun finish() {
        super.finish()
    }

    override fun onRestart() {
        super.onRestart()
        
        // Don't trigger security when returning from legitimate app activities
        if (TempPinHolder.securityTriggered && !TempPinHolder.wasRecentlyCleared() && 
            !isPhotoPickerActive && !isMediaViewerActive && !isNoteEditorActive) {
            finish()
            return
        }
    }
    
    override fun onResume() {
        val currentPin = TempPinHolder.pin ?: ""
        resumeTime = System.currentTimeMillis()
        super.onResume()
        
        // Enable security monitoring when activity becomes active
        securityManager?.enable()
        
        // Only refresh if we're returning from photo picker or if media viewer was NOT active
        // This prevents duplicate thumbnails when returning from photo viewer
        if (isPhotoPickerActive || !isMediaViewerActive) {
            refreshGalleryData()
        } else {
        }
        
        // Reset flags
        isPhotoPickerActive = false
        isMediaViewerActive = false
    }
    
    private fun refreshGalleryData() {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        // Check if media count has changed and we need a refresh
        val actualMediaCount = gallery.media.size
        val currentThumbnailCount = decryptedMedia.count { it != null }
        val hasNewMedia = actualMediaCount > currentThumbnailCount
        val hasMissingThumbnails = actualMediaCount > 0 && currentThumbnailCount < actualMediaCount
        
        android.util.Log.d("SecureGallery", "Gallery refresh check - Total media: $actualMediaCount, Current thumbnails: $currentThumbnailCount, decryptedMedia.size: ${decryptedMedia.size}")
        
        if (hasNewMedia || hasMissingThumbnails) {
            android.util.Log.d("SecureGallery", "Gallery changed - Need refresh. New: $hasNewMedia, Missing: $hasMissingThumbnails")
            
            // Only clear if we actually have new media beyond what we've processed
            if (hasNewMedia && actualMediaCount > decryptedMedia.size) {
                android.util.Log.d("SecureGallery", "Expanding thumbnail list from ${decryptedMedia.size} to $actualMediaCount")
                // Expand the list to accommodate new media (don't clear existing)
                // Ensure we don't exceed the actual media count
                while (decryptedMedia.size < actualMediaCount) {
                    decryptedMedia.add(null)
                }
                photosAdapter?.notifyDataSetChanged()
                
                // Load only the new thumbnails
                val newMedia = gallery.media.drop(currentThumbnailCount)
                if (key != null && newMedia.isNotEmpty()) {
                    loadThumbnailsAsync(newMedia, key)
                }
            } else if (hasMissingThumbnails) {
                // Ensure decryptedMedia list matches actual media count before loading missing thumbnails
                if (decryptedMedia.size != actualMediaCount) {
                    android.util.Log.d("SecureGallery", "Resizing decryptedMedia from ${decryptedMedia.size} to $actualMediaCount")
                    // Resize the list to match actual media count
                    while (decryptedMedia.size < actualMediaCount) {
                        decryptedMedia.add(null)
                    }
                    while (decryptedMedia.size > actualMediaCount) {
                        decryptedMedia.removeAt(decryptedMedia.size - 1)
                    }
                    photosAdapter?.notifyDataSetChanged()
                }
                
                // Load missing thumbnails within existing range
                if (key != null && gallery.media.isNotEmpty()) {
                    loadMissingThumbnailsInRange(gallery.media, key)
                }
            }
            
            android.util.Log.d("SecureGallery", "Gallery refresh completed")
        } else {
            android.util.Log.d("SecureGallery", "No gallery refresh needed - Media: $actualMediaCount, Thumbnails: $currentThumbnailCount")
        }
    }
    
    // Security feature implementations
    private fun initializeSecurity() {
        // Initialize proper security manager
        securityManager = SecurityManager(this, object : SecurityManager.SecurityEventListener {
            override fun onSecurityTrigger(reason: String) {
                android.util.Log.d("SecureGallery", "SecurityManager triggered: $reason")
                closeGalleryForSecurity()
            }
        })
        
        // Register screen off receiver for immediate security triggering
        val screenOffFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, screenOffFilter)
        android.util.Log.d("SecureGallery", "Security initialized - SecurityManager and screen off receiver registered")
    }
    
    private fun cleanupSecurity() {
        // Cleanup security manager
        securityManager?.disable()
        securityManager = null
        
        // Unregister screen off receiver
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, ignore
        }
    }
    
    private fun closeGalleryForSecurity() {
        val currentPin = TempPinHolder.pin ?: ""
        android.util.Log.d("SecureGallery", "closeGalleryForSecurity() called - PIN='$currentPin', securityTriggered=${TempPinHolder.securityTriggered}")
        android.util.Log.d("SecureGallery", "Gallery closure context - isPhotoPickerActive: $isPhotoPickerActive, isMediaViewerActive: $isMediaViewerActive, isNoteEditorActive: $isNoteEditorActive")
        
        // Log current stack trace to see what triggered the closure
        val stackTrace = Thread.currentThread().stackTrace
        android.util.Log.d("SecureGallery", "Gallery closure stack trace: ${stackTrace.take(10).joinToString { "${it.className}.${it.methodName}:${it.lineNumber}" }}")
        
        if (!TempPinHolder.securityTriggered) {
            // triggerSecurity() now automatically clears the PIN
            TempPinHolder.triggerSecurity("Gallery security closure")
            android.util.Log.d("SecureGallery", "Security triggered - clearing PIN and finishing activity")
            finish() // Close gallery and return to calculator
        }
    }
    
    // Safe recreation that doesn't trigger security closure
    private fun safeRecreate() {
        isRecreating = true
        recreate()
    }
    
    // SensorEventListener implementation for accelerometer
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // Debug: Log PIN status at activity start
        val currentPin = TempPinHolder.pin ?: ""
        android.util.Log.d("SecureGallery", "onCreate: PIN status - pin='$currentPin', isEmpty=${currentPin.isEmpty()}")

        // Keep screen on while gallery is active to prevent authentication timeout issues
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // For API 27+, use the new methods instead of deprecated flags
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        // Initialize GalleryManager context
        GalleryManager.setContext(this)

        // Record when security system becomes active to prevent startup false positives
        securityStartTime = System.currentTimeMillis()
        
        // Initialize memory manager
        MemoryManager.initialize(this)
        
        // Initialize security features
        initializeSecurity()
        
        // Clear security trigger since we successfully entered the gallery
        TempPinHolder.clearSecurityTrigger()
        android.util.Log.d("SecureGallery", "onCreate: Gallery started successfully, security trigger cleared")
        
        // Reset recreation flag
        isRecreating = false

        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        
        // Get gallery data from GalleryManager instead of Intent
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        val notes = gallery?.notes ?: mutableListOf()
        val media = gallery?.media ?: mutableListOf() // Changed from photos to media

        // Apply sort order to media - defer to background to reduce main thread work
        if (gallery != null) {
            Thread {
                GallerySortUtils.applySortOrder(gallery)
                runOnUiThread {
                    android.util.Log.d("SecureGallery", "Sort order applied for ${gallery.media.size} media items")
                }
            }.start()
        }

        // Decrypt notes using pin and salt
        val pin = TempPinHolder.pin ?: ""
        val salt = GalleryManager.getGalleries().find { it.name == galleryName }?.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null

        // Clear and populate the class-level decryptedNotes variable
        decryptedNotes.clear()
        for (note in notes) {
            var title = "(Encrypted)"
            var body = "(Encrypted)"
            if (key != null) {
                try {
                    val ivTitle = note.encryptedTitle.copyOfRange(0, 16)
                    val ctTitle = note.encryptedTitle.copyOfRange(16, note.encryptedTitle.size)
                    val ivBody = note.encryptedBody.copyOfRange(0, 16)
                    val ctBody = note.encryptedBody.copyOfRange(16, note.encryptedBody.size)
                    title = String(CryptoUtils.decrypt(ivTitle, ctTitle, key), Charsets.UTF_8)
                    body = String(CryptoUtils.decrypt(ivBody, ctBody, key), Charsets.UTF_8)
                } catch (e: Exception) {
                    title = "(Decryption failed)"
                    body = "(Decryption failed)"
                }
            }
            decryptedNotes.add(Pair(title, body))
        }

        val notesRecyclerView = findViewById<RecyclerView>(R.id.notesRecyclerView)
        notesRecyclerView.layoutManager = LinearLayoutManager(this)
        notesAdapter = object : RecyclerView.Adapter<NoteViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): NoteViewHolder {
                val v = android.view.LayoutInflater.from(parent.context).inflate(android.R.layout.simple_list_item_2, parent, false)
                return NoteViewHolder(v)
            }
            override fun getItemCount() = decryptedNotes.size
            override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
                val note = decryptedNotes[position]
                val isSelected = selectedNotesForDeletion.contains(position)
                holder.bind(note.first, note.second, isNoteDeleteMode, isSelected)
                
                if (isNoteDeleteMode) {
                    // In delete mode, clicking toggles selection
                    holder.itemView.setOnClickListener {
                        if (selectedNotesForDeletion.contains(position)) {
                            selectedNotesForDeletion.remove(position)
                        } else {
                            selectedNotesForDeletion.add(position)
                        }
                        notifyItemChanged(position)
                        
                        // Auto-cancel delete mode if no notes are selected
                        if (selectedNotesForDeletion.isEmpty()) {
                            exitNoteDeleteMode()
                        }
                    }
                    
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode - click to edit note
                    holder.itemView.setOnClickListener {
                        openNoteEditor(position, note.first, note.second)
                    }
                    
                    // Long press to enter delete mode
                    holder.itemView.setOnLongClickListener {
                        enterNoteDeleteMode()
                        selectedNotesForDeletion.add(position)
                        notifyDataSetChanged()
                        true
                    }
                }
            }
        }
        notesRecyclerView.adapter = notesAdapter

        // Hide notes section for level 2 galleries
        val notesSection = findViewById<LinearLayout>(R.id.notesSection)
        if (isCurrentGalleryLevel2()) {
            notesSection.visibility = android.view.View.GONE
        } else {
            notesSection.visibility = android.view.View.VISIBLE
        }

        // Setup media RecyclerView with two-column grid
        val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
        val gridLayoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        photosRecyclerView.layoutManager = gridLayoutManager
        
        // Ensure RecyclerView can scroll and display all items
        photosRecyclerView.setHasFixedSize(false) // Allow dynamic sizing
        photosRecyclerView.isNestedScrollingEnabled = false // Disable nested scrolling since we're inside a ScrollView
        
        // Add momentum scrolling - similar to iOS smooth deceleration
        // Standard friction for smooth deceleration (0.84f provides good momentum without being too bouncy)
        photosRecyclerView.setItemViewCacheSize(20) // Improved performance during fast scrolling
        
        // Enable momentum scrolling with custom friction for smooth deceleration
        // This creates a natural feel similar to popular gallery apps
        val scrollListener = object : RecyclerView.OnScrollListener() {
            private var isScrolling = false
            
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> {
                        isScrolling = true
                        isUserInteracting = true
                        lastInteractionTime = System.currentTimeMillis()
                    }
                    RecyclerView.SCROLL_STATE_SETTLING -> {
                        // Maintain momentum during deceleration
                        isUserInteracting = true
                        lastInteractionTime = System.currentTimeMillis()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        isScrolling = false
                        isUserInteracting = false
                    }
                }
            }
        }
        photosRecyclerView.addOnScrollListener(scrollListener)
        
        // Initialize empty media list - thumbnails will be loaded asynchronously
        this.decryptedMedia = mutableListOf()
        
        // Create and initialize the adapter FIRST, then load thumbnails
        photosAdapter = object : RecyclerView.Adapter<MediaThumbnailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaThumbnailViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_thumbnail, parent, false)
                return MediaThumbnailViewHolder(v)
            }
            override fun getItemCount(): Int {
                val baseCount = if (isOrganizeMode) organizeMedia.size else decryptedMedia.size
                
                // Safety check: ensure count doesn't exceed actual media in gallery
                val galleryName = intent.getStringExtra("gallery_name")
                val gallery = if (galleryName != null) GalleryManager.getGalleries().find { it.name == galleryName } else null
                val actualMediaCount = gallery?.media?.size ?: 0
                
                val count = if (actualMediaCount > 0) minOf(baseCount, actualMediaCount) else baseCount
                
                android.util.Log.d("SecureGallery", "getItemCount() called: returning $count (organize: $isOrganizeMode, decryptedMedia: ${decryptedMedia.size}, organizeMedia: ${organizeMedia.size})")
                
                // Additional debugging for the first 20 calls
                if (count <= 10) {
                    val stackTrace = Thread.currentThread().stackTrace
                    android.util.Log.d("SecureGallery", "getItemCount() stack trace preview: ${stackTrace.take(5).joinToString { it.methodName }}")
                }
                
                return count
            }
            override fun onBindViewHolder(holder: MediaThumbnailViewHolder, position: Int) {
                // Use organize media if in organize mode, otherwise use decrypted media
                val mediaThumbnail = if (isOrganizeMode && position < organizeMedia.size) {
                    organizeMedia[position]
                } else if (position < decryptedMedia.size) {
                    decryptedMedia[position]
                } else {
                    null
                }
                
                android.util.Log.d("SecureGallery", "onBindViewHolder position $position: mediaThumbnail = ${if (mediaThumbnail != null) "loaded" else "null"}")
                
                holder.bind(mediaThumbnail, isDeleteMode, selectedPhotosForDeletion.contains(position), isOrganizeMode, position)
                
                // Trigger preloading for smooth scrolling
                if (!isOrganizeMode && !isDeleteMode && gallery != null) {
                    preloadThumbnails(position, gallery, key)
                }
                
                if (isOrganizeMode) {
                    // In organize mode, disable clicks (only allow drag)
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.setOnLongClickListener(null)
                } else if (isDeleteMode) {
                    // In delete mode, clicking toggles selection
                    holder.itemView.setOnClickListener {
                        if (selectedPhotosForDeletion.contains(position)) {
                            selectedPhotosForDeletion.remove(position)
                        } else {
                            selectedPhotosForDeletion.add(position)
                        }
                        notifyItemChanged(position)
                        
                        // Auto-cancel delete mode if no media are selected
                        if (selectedPhotosForDeletion.isEmpty()) {
                            exitDeleteMode()
                        }
                    }
                    
                    // No long press needed in delete mode
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode - click to view media
                    holder.itemView.setOnClickListener {
                        // Only allow viewing if the thumbnail is loaded (not null)
                        val mediaThumbnail = if (isOrganizeMode && position < organizeMedia.size) {
                            organizeMedia[position]
                        } else if (position < decryptedMedia.size) {
                            decryptedMedia[position]
                        } else {
                            null
                        }
                        
                        if (mediaThumbnail != null) {
                            android.util.Log.d("SecureGallery", "Setting isMediaViewerActive = true for position $position")
                            isMediaViewerActive = true
                            val intent = android.content.Intent(this@GalleryActivity, SecureMediaViewerActivity::class.java)
                            intent.putExtra(SecureMediaViewerActivity.EXTRA_GALLERY_NAME, galleryName)
                            intent.putExtra(SecureMediaViewerActivity.EXTRA_POSITION, position) // This position matches the media list
                            intent.putExtra(SecureMediaViewerActivity.EXTRA_PIN, pin)
                            intent.putExtra(SecureMediaViewerActivity.EXTRA_SALT, salt)
                            android.util.Log.d("SecureGallery", "Opening media viewer at position $position")
                            photoViewerLauncher.launch(intent)
                        } else {
                            android.util.Log.w("SecureGallery", "Cannot open media at position $position - thumbnail not loaded")
                        }
                    }
                    
                    // Long press to enter delete mode
                    holder.itemView.setOnLongClickListener {
                        enterDeleteMode()
                        selectedPhotosForDeletion.add(position)
                        notifyDataSetChanged()
                        true
                    }
                }
            }
            
            override fun onViewRecycled(holder: MediaThumbnailViewHolder) {
                super.onViewRecycled(holder)
                // Clean up ViewHolder properly
                holder.cleanup()
            }
        }
        
        photosRecyclerView.adapter = photosAdapter

        // Defer thumbnail loading to prevent main thread blocking during startup
        // This prevents the immediate onPause that was causing screen management issues
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                // Load thumbnails after UI has settled
                if (key != null && media.isNotEmpty()) {
                    android.util.Log.d("SecureGallery", "Starting deferred thumbnail loading for ${media.size} media items")
                    loadInitialThumbnails(media, key)
                } else {
                    android.util.Log.w("SecureGallery", "No media to load: key=${key != null}, media.size=${media.size}")
                }
            }
        }, 200) // 200ms delay to let activity fully initialize

        // Add drag-and-drop support for organize mode
        setupDragAndDrop(photosRecyclerView)

        // Removed toast: Opened $galleryName with ... notes and ... photos

        // Enable swipe-to-go-back gesture
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    val deltaX = e2.x - e1.x
                    if (deltaX > 300 && Math.abs(deltaX) > Math.abs(e2.y - e1.y)) {
                        finish()
                        return true
                    }
                }
                return false
            }
        })

        // Setup hamburger menu
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.galleryToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_menu_black_background)
        toolbar.setNavigationOnClickListener {
            showCustomGalleryMenu()
        }
        
        // Set initial title to gallery name
        supportActionBar?.title = galleryName
        
        // Setup delete mode buttons
        val deleteButton = findViewById<android.widget.Button>(R.id.deleteButton)
        val cancelButton = findViewById<android.widget.Button>(R.id.cancelButton)
        
        deleteButton.setOnClickListener {
            deleteSelectedPhotos()
        }
        
        cancelButton.setOnClickListener {
            exitDeleteMode()
        }
        
        // Setup add note button
        val addNoteButton = findViewById<android.widget.Button>(R.id.addNoteButton)
        addNoteButton.setOnClickListener {
            openNoteEditor(-1, "", "")
        }
        
        // Setup note delete mode buttons
        val deleteNotesButton = findViewById<android.widget.Button>(R.id.deleteNotesButton)
        val cancelNotesButton = findViewById<android.widget.Button>(R.id.cancelNotesButton)
        
        deleteNotesButton.setOnClickListener {
            deleteSelectedNotes()
        }
        
        cancelNotesButton.setOnClickListener {
            exitNoteDeleteMode()
        }
        
        // Clean up orphaned video thumbnails
        if (gallery != null && media.isNotEmpty()) {
            VideoUtils.cleanupOrphanedThumbnails(this, media)
        }
        
        android.util.Log.d("SecureGallery", "GalleryActivity onCreate() completed successfully")
    }



    // Dialog for creating a gallery
    private fun showCreateGalleryDialog() {
        // Check security level restriction
        if (isCurrentGalleryLevel2()) {
            Toast.makeText(this, "Gallery creation not allowed for Level 2 galleries", Toast.LENGTH_SHORT).show()
            return
        }
        
        val pinInput = android.widget.EditText(this)
        pinInput.hint = "Enter PIN"
        val nameInput = android.widget.EditText(this)
        nameInput.hint = "Enter Gallery Name"
        
        // Security level selection
        val securityLevelSpinner = android.widget.Spinner(this)
        val securityLevelOptions = arrayOf("Level 1 - Standard", "Level 2 - Restricted")
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, securityLevelOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        securityLevelSpinner.adapter = adapter
        
        // Explanatory text for security levels
        val explanationText = android.widget.TextView(this)
        explanationText.text = "Security Level 2 galleries cannot create new galleries or export data"
        explanationText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        explanationText.setTextColor(android.graphics.Color.GRAY)
        explanationText.setPadding(0, 8, 0, 8)
        
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.addView(pinInput)
        layout.addView(nameInput)
        layout.addView(android.widget.TextView(this).apply { text = "Security Level:" })
        layout.addView(securityLevelSpinner)
        layout.addView(explanationText)
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Create Gallery")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val pin = pinInput.text.toString()
                val name = nameInput.text.toString()
                val securityLevel = securityLevelSpinner.selectedItemPosition + 1 // 1 or 2
                val result = com.darkempire78.opencalculator.securegallery.GalleryManager.createGallery(pin, name, securityLevel)
                if (result) {
                    Toast.makeText(this, "Gallery created", Toast.LENGTH_SHORT).show()
                    // Store the PIN in TempPinHolder so the new gallery can access it
                    TempPinHolder.pin = pin
                    // Close current and open new gallery
                    isOpeningNewGallery = true  // Flag to prevent PIN clearing
                    val intent = intent
                    intent.putExtra("gallery_name", name)
                    finish()
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Failed to create gallery", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Dialog for renaming a gallery
    private fun showRenameGalleryDialog(oldName: String) {
        val nameInput = android.widget.EditText(this)
        nameInput.hint = "Enter New Gallery Name"
        android.app.AlertDialog.Builder(this)
            .setTitle("Rename Gallery")
            .setView(nameInput)
            .setPositiveButton("Rename") { _, _ ->
                val newName = nameInput.text.toString()
                val gallery = com.darkempire78.opencalculator.securegallery.GalleryManager.getGalleries().find { it.name == oldName }
                val result = if (gallery != null) com.darkempire78.opencalculator.securegallery.GalleryManager.renameGallery(gallery.id, newName) else false
                android.util.Log.d("SecureGallery", "RenameGalleryDialog: oldName=$oldName newName=$newName result=$result")
                Toast.makeText(this, if (result) "Gallery renamed" else "Failed to rename gallery", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Dialog for changing gallery PIN
    private fun showChangePinDialog(galleryName: String) {
        val currentPinInput = android.widget.EditText(this)
        currentPinInput.hint = "Enter Current PIN"
        currentPinInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        
        val newPinInput = android.widget.EditText(this)
        newPinInput.hint = "Enter New PIN"
        newPinInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        
        val confirmPinInput = android.widget.EditText(this)
        confirmPinInput.hint = "Confirm New PIN"
        confirmPinInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.addView(currentPinInput)
        layout.addView(newPinInput)
        layout.addView(confirmPinInput)
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Change PIN")
            .setView(layout)
            .setPositiveButton("Change") { _, _ ->
                val currentPin = currentPinInput.text.toString()
                val newPin = newPinInput.text.toString()
                val confirmPin = confirmPinInput.text.toString()
                
                if (newPin != confirmPin) {
                    Toast.makeText(this, "New PIN and confirmation don't match", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (newPin.length < 4) {
                    Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                changePinForGallery(galleryName, currentPin, newPin)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun changePinForGallery(galleryName: String, currentPin: String, newPin: String) {
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        if (gallery == null) {
            Toast.makeText(this, "Gallery not found", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Verify current PIN
        val currentPinHash = CryptoUtils.generatePinHash(currentPin, gallery.salt)
        if (!currentPinHash.contentEquals(gallery.pinHash)) {
            Toast.makeText(this, "Current PIN is incorrect", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show progress dialog for re-encryption
        val progressText = android.widget.TextView(this).apply {
            text = "Re-encrypting gallery data with new PIN..."
            setPadding(0, 0, 0, 16)
        }
        
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
            addView(progressText)
        }
        
        val progressDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Changing PIN")
            .setView(layout)
            .setCancelable(false)
            .create()
        
        progressDialog.show()
        
        Thread {
            try {
                // Derive keys
                val currentKey = CryptoUtils.deriveKey(currentPin, gallery.salt)
                val newKey = CryptoUtils.deriveKey(newPin, gallery.salt)
                
                // Re-encrypt all media
                // Count existing thumbnails for progress calculation
                val thumbnailCount = gallery.media.count { mediaItem ->
                    val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this@GalleryActivity, galleryName, mediaItem.id.toString())
                    java.io.File(thumbnailPath).exists()
                }
                var totalItems = gallery.media.size + thumbnailCount + gallery.notes.size
                var processedItems = 0
                
                // Re-encrypt media
                for (mediaItem in gallery.media) {
                    try {
                        // Decrypt with old key
                        val encryptedData = mediaItem.getEncryptedData()
                        val iv = encryptedData.sliceArray(0..15)
                        val ciphertext = encryptedData.sliceArray(16 until encryptedData.size)
                        val decryptedData = CryptoUtils.decrypt(iv, ciphertext, currentKey)
                        
                        // Re-encrypt with new key
                        val (newIv, newCiphertext) = CryptoUtils.encrypt(decryptedData, newKey)
                        mediaItem.setEncryptedData(newIv + newCiphertext)
                        
                        processedItems++
                        val progress = (processedItems * 100 / totalItems)
                        runOnUiThread {
                            progressText.text = "Re-encrypting gallery data... ($progress%)"
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SecureGallery", "Failed to re-encrypt media item: ${mediaItem.name}", e)
                    }
                }
                
                // Re-encrypt thumbnails using ThumbnailGenerator paths
                for (mediaItem in gallery.media) {
                    try {
                        // Get the expected thumbnail path using ThumbnailGenerator
                        val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this@GalleryActivity, galleryName, mediaItem.id.toString())
                        val thumbnailFile = java.io.File(thumbnailPath)
                        
                        if (thumbnailFile.exists()) {
                            // Read the encrypted thumbnail data
                            val thumbnailData = thumbnailFile.readBytes()
                            
                            if (thumbnailData.size >= 16) {
                                // Decrypt thumbnail with old key
                                val thumbnailIv = thumbnailData.sliceArray(0..15)
                                val thumbnailCiphertext = thumbnailData.sliceArray(16 until thumbnailData.size)
                                val decryptedThumbnail = CryptoUtils.decrypt(thumbnailIv, thumbnailCiphertext, currentKey)
                                
                                // Re-encrypt thumbnail with new key
                                val (newThumbnailIv, newThumbnailCiphertext) = CryptoUtils.encrypt(decryptedThumbnail, newKey)
                                
                                // Save re-encrypted thumbnail back to file
                                thumbnailFile.writeBytes(newThumbnailIv + newThumbnailCiphertext)
                                
                                android.util.Log.d("SecureGallery", "Successfully re-encrypted thumbnail for: ${mediaItem.name}")
                            } else {
                                android.util.Log.w("SecureGallery", "Thumbnail data too small for: ${mediaItem.name}")
                            }
                            
                            processedItems++
                            val progress = (processedItems * 100 / totalItems)
                            runOnUiThread {
                                progressText.text = "Re-encrypting thumbnails... ($progress%)"
                            }
                        } else {
                            android.util.Log.d("SecureGallery", "No thumbnail found for: ${mediaItem.name} at $thumbnailPath")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SecureGallery", "Failed to re-encrypt thumbnail for: ${mediaItem.name}", e)
                    }
                }
                
                // Re-encrypt notes
                for (note in gallery.notes) {
                    try {
                        // Decrypt title and body with old key
                        val titleData = note.encryptedTitle
                        val titleIv = titleData.sliceArray(0..15)
                        val titleCiphertext = titleData.sliceArray(16 until titleData.size)
                        val decryptedTitle = CryptoUtils.decrypt(titleIv, titleCiphertext, currentKey)
                        
                        val bodyData = note.encryptedBody
                        val bodyIv = bodyData.sliceArray(0..15)
                        val bodyCiphertext = bodyData.sliceArray(16 until bodyData.size)
                        val decryptedBody = CryptoUtils.decrypt(bodyIv, bodyCiphertext, currentKey)
                        
                        // Re-encrypt with new key
                        val (newTitleIv, newTitleCiphertext) = CryptoUtils.encrypt(decryptedTitle, newKey)
                        val (newBodyIv, newBodyCiphertext) = CryptoUtils.encrypt(decryptedBody, newKey)
                        
                        note.encryptedTitle = newTitleIv + newTitleCiphertext
                        note.encryptedBody = newBodyIv + newBodyCiphertext
                        
                        processedItems++
                        val progress = (processedItems * 100 / totalItems)
                        runOnUiThread {
                            progressText.text = "Re-encrypting notes... ($progress%)"
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("SecureGallery", "Failed to re-encrypt note", e)
                    }
                }
                
                // Update PIN hash
                gallery.pinHash = CryptoUtils.generatePinHash(newPin, gallery.salt)
                
                // Save the gallery
                GalleryManager.saveGalleries()
                
                // Update the temporary PIN holder
                TempPinHolder.pin = newPin
                
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@GalleryActivity, "PIN changed successfully", Toast.LENGTH_SHORT).show()
                    
                    // Clear thumbnail cache to force reload with new key
                    thumbnailCache.clear()
                    
                    // Refresh the display with new PIN
                    refreshGalleryData()
                    refreshNotesDisplay()
                }
                
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to change PIN", e)
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@GalleryActivity, "Failed to change PIN: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // Dialog for deleting a gallery
    private fun showDeleteGalleryDialog(name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete Gallery")
            .setMessage("Are you sure you want to delete $name?")
            .setPositiveButton("Delete") { _, _ ->
                val gallery = com.darkempire78.opencalculator.securegallery.GalleryManager.getGalleries().find { it.name == name }
                val result = if (gallery != null) com.darkempire78.opencalculator.securegallery.GalleryManager.deleteGallery(gallery.id) else false
                android.util.Log.d("SecureGallery", "DeleteGalleryDialog: name=$name result=$result")
                if (result) {
                    Toast.makeText(this, "Gallery deleted", Toast.LENGTH_SHORT).show()
                    finish() // Close gallery and return to calculator
                } else {
                    Toast.makeText(this, "Failed to delete gallery", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // Helper method to get the current gallery
    private fun getCurrentGallery(): Gallery? {
        val galleryName = intent.getStringExtra("gallery_name") ?: return null
        return GalleryManager.getGalleries().find { it.name == galleryName }
    }

    // Helper method to check if current gallery is level 2
    private fun isCurrentGalleryLevel2(): Boolean {
        return getCurrentGallery()?.securityLevel == 2
    }

    private fun showCustomGalleryMenu() {
        val dialog = Dialog(this)
        
        // Only show normal menu items, delete mode is handled by dedicated buttons
        val allMenuItems = listOf(
            Pair("Add Media", R.id.action_add_pictures), // Changed text but keeping same ID for compatibility
            Pair("Export Photos", R.id.action_export_photos),
            Pair("Export Gallery", R.id.action_export_gallery),
            Pair("Import Gallery", R.id.action_import_gallery),
            Pair("Sort Options", -1), // Special submenu item
            Pair("Create Gallery", R.id.action_create_gallery),
            Pair("Rename Gallery", R.id.action_rename_gallery),
            Pair("Change Pin", R.id.action_change_pin),
            Pair("Delete Gallery", R.id.action_delete_gallery)
        )
        
        // Filter menu items based on security level
        val menuItems = if (isCurrentGalleryLevel2()) {
            // For level 2 galleries, exclude create gallery, export gallery, delete gallery, rename gallery, and import gallery
            // But keep export photos option
            allMenuItems.filter { (_, id) ->
                id != R.id.action_create_gallery && 
                id != R.id.action_export_gallery &&
                id != R.id.action_delete_gallery &&
                id != R.id.action_rename_gallery &&
                id != R.id.action_import_gallery
            }
        } else {
            allMenuItems
        }
        
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setBackgroundColor(android.graphics.Color.WHITE)
        
        for ((title, id) in menuItems) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.custom_popup_menu_item, container, false)
            val textView = itemView.findViewById<TextView>(R.id.menuItemText)
            textView.text = title
            textView.setOnClickListener {
                val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
                when (id) {
                    R.id.action_add_pictures -> addMediaToGallery() // Updated to use media instead of pictures
                    R.id.action_export_photos -> exportPhotosToMedia()
                    R.id.action_export_gallery -> exportGalleryToFile()
                    R.id.action_import_gallery -> importGalleryFromFile()
                    R.id.action_create_gallery -> showCreateGalleryDialog()
                    R.id.action_rename_gallery -> showRenameGalleryDialog(galleryName)
                    R.id.action_change_pin -> showChangePinDialog(galleryName)
                    R.id.action_delete_gallery -> showDeleteGalleryDialog(galleryName)
                    -1 -> {
                        dialog.dismiss()
                        showSortOptionsMenu()
                        return@setOnClickListener
                    }
                }
                dialog.dismiss()
            }
            container.addView(itemView)
        }
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }

    class NoteViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind(title: String, body: String, isDeleteMode: Boolean = false, isSelected: Boolean = false) {
            val titleView = itemView.findViewById<android.widget.TextView>(android.R.id.text1)
            val bodyView = itemView.findViewById<android.widget.TextView>(android.R.id.text2)
            titleView.text = title
            bodyView.text = body
            
            // Set text color for dark background
            titleView.setTextColor(android.graphics.Color.WHITE)
            bodyView.setTextColor(android.graphics.Color.LTGRAY)
            
            // Handle selection overlay
            if (isDeleteMode) {
                // Add a semi-transparent overlay for delete mode
                if (isSelected) {
                    itemView.alpha = 0.6f
                    itemView.setBackgroundColor(0x880000FF.toInt()) // Semi-transparent blue
                } else {
                    itemView.alpha = 1.0f
                    itemView.setBackgroundColor(0x00000000.toInt()) // Transparent
                }
            } else {
                // Normal mode - remove any overlays
                itemView.alpha = 1.0f
                itemView.setBackgroundColor(0x00000000.toInt()) // Transparent
            }
        }
    }
    
    class MediaThumbnailViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        private var currentBitmap: android.graphics.Bitmap? = null
        var mediaThumbnail: MediaThumbnail? = null
            private set
        var cacheKey: String? = null
            private set
        
        fun bind(mediaThumbnail: MediaThumbnail?, isDeleteMode: Boolean = false, isSelected: Boolean = false, isOrganizeMode: Boolean = false, position: Int = -1) {
            this.mediaThumbnail = mediaThumbnail
            this.cacheKey = if (position >= 0) "${position}_${mediaThumbnail?.mediaType}" else null
            val imageView = itemView.findViewById<android.widget.ImageView>(R.id.mediaThumbnail)
            val playIcon = itemView.findViewById<android.widget.ImageView>(R.id.playIcon)
            val durationText = itemView.findViewById<android.widget.TextView>(R.id.videoDuration)
            
            // Unmark previous bitmap as no longer active in this ViewHolder
            cacheKey?.let { oldKey ->
                MemoryManager.unmarkBitmapActive(oldKey)
            }
            currentBitmap = null
            
            if (mediaThumbnail?.bitmap != null && !mediaThumbnail.bitmap.isRecycled) {
                currentBitmap = mediaThumbnail.bitmap
                // Mark this bitmap as active to prevent recycling while displayed
                cacheKey?.let { key ->
                    MemoryManager.markBitmapActive(key, mediaThumbnail.bitmap)
                }
                imageView.setImageBitmap(mediaThumbnail.bitmap)
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
            
            // Show video-specific UI elements
            if (mediaThumbnail?.mediaType == MediaType.VIDEO) {
                playIcon.visibility = android.view.View.VISIBLE
                if (mediaThumbnail.duration != null) {
                    durationText.text = mediaThumbnail.duration
                    durationText.visibility = android.view.View.VISIBLE
                } else {
                    durationText.visibility = android.view.View.GONE
                }
            } else {
                playIcon.visibility = android.view.View.GONE
                durationText.visibility = android.view.View.GONE
            }
            
            // Handle selection overlay
            if (isDeleteMode) {
                // Add a semi-transparent overlay for delete mode
                if (isSelected) {
                    itemView.alpha = 0.6f
                    itemView.setBackgroundColor(0x880000FF.toInt()) // Semi-transparent blue
                } else {
                    itemView.alpha = 1.0f
                    itemView.setBackgroundColor(0x00000000.toInt()) // Transparent
                }
            } else if (isOrganizeMode) {
                // Add a slight green overlay for organize mode
                itemView.alpha = 0.9f
                itemView.setBackgroundColor(0x4400FF00.toInt()) // Semi-transparent green
            } else {
                // Normal mode - remove any overlays
                itemView.alpha = 1.0f
                itemView.setBackgroundColor(0x00000000.toInt()) // Transparent
            }
        }
        
        fun cleanup() {
            // Unmark bitmap as no longer active in this ViewHolder
            cacheKey?.let { key ->
                MemoryManager.unmarkBitmapActive(key)
            }
            currentBitmap = null
            cacheKey = null
        }
    }
    
    // Sort Options Menu Functions
    private fun showSortOptionsMenu() {
        val dialog = Dialog(this)
        
        val sortOptions = listOf(
            Pair("By Name", GallerySortOrder.NAME),
            Pair("By Date", GallerySortOrder.DATE),
            Pair("Custom Order", GallerySortOrder.CUSTOM),
            Pair("Organize Photos", null) // Special organize mode option
        )
        
        val container = android.widget.LinearLayout(this)
        container.orientation = android.widget.LinearLayout.VERTICAL
        container.setBackgroundColor(android.graphics.Color.WHITE)
        
        for ((title, sortOrder) in sortOptions) {
            val itemView = LayoutInflater.from(this).inflate(R.layout.custom_popup_menu_item, container, false)
            val textView = itemView.findViewById<TextView>(R.id.menuItemText)
            textView.text = title
            textView.setOnClickListener {
                if (sortOrder != null) {
                    applySortOrder(sortOrder)
                } else {
                    enterOrganizeMode()
                }
                dialog.dismiss()
            }
            container.addView(itemView)
        }
        
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
    }
    
    private fun applySortOrder(sortOrder: GallerySortOrder) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        gallery.sortOrder = sortOrder
        
        when (sortOrder) {
            GallerySortOrder.NAME -> {
                GallerySortUtils.sortMediaByName(gallery.media)
                Toast.makeText(this, "Media sorted by name", Toast.LENGTH_SHORT).show()
            }
            GallerySortOrder.DATE -> {
                GallerySortUtils.sortMediaByDate(gallery.media)
                Toast.makeText(this, "Media sorted by date", Toast.LENGTH_SHORT).show()
            }
            GallerySortOrder.CUSTOM -> {
                if (gallery.customOrder.isNotEmpty()) {
                    GallerySortUtils.sortMediaByCustomOrder(gallery.media, gallery.customOrder)
                    Toast.makeText(this, "Media sorted by custom order", Toast.LENGTH_SHORT).show()
                } else {
                    // Initialize custom order with current order
                    gallery.customOrder = (0 until gallery.media.size).toMutableList()
                    Toast.makeText(this, "Custom order initialized", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        GalleryManager.saveGalleries()
        safeRecreate() // Refresh to show new order
    }
    
    // Organize Mode Functions
    private fun enterOrganizeMode() {
        isOrganizeMode = true
        
        // Store current decrypted photos for organize mode
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        android.util.Log.d("SecureGallery", "Organize mode setup: pin='$pin', salt=${salt.contentToString()}, galleryName='$galleryName'")
        android.util.Log.d("SecureGallery", "Gallery details: id=${gallery.id}, mediaCount=${gallery.media.size}, hasHash=${gallery.pinHash != null}")
        
        if (key == null) {
            android.util.Log.e("SecureGallery", "Cannot derive key: pin='$pin'")
            Toast.makeText(this, "Cannot organize media: decryption key unavailable", Toast.LENGTH_LONG).show()
            return
        }
        
        organizeMedia.clear()
        organizeMedia.addAll(gallery.media.mapIndexed { index, mediaItem ->
            when (mediaItem.mediaType) {
                    MediaType.PHOTO -> {
                        try {
                            val encryptedData = mediaItem.getEncryptedData()
                            android.util.Log.d("SecureGallery", "Decrypting photo $index (${mediaItem.name}): data size=${encryptedData.size}")
                            val iv = encryptedData.copyOfRange(0, 16)
                            val ct = encryptedData.copyOfRange(16, encryptedData.size)
                            val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
                            val bitmap = android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                            MediaThumbnail(bitmap, null, mediaItem.mediaType)
                        } catch (e: Exception) {
                            android.util.Log.e("SecureGallery", "Primary decryption failed for photo ${mediaItem.name} (index $index)", e)
                            
                            // Try legacy decryption with default salt
                            try {
                                android.util.Log.d("SecureGallery", "Attempting legacy decryption for photo $index")
                                val legacySalt = ByteArray(16) // Empty salt for legacy photos
                                val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                                val encryptedData = mediaItem.getEncryptedData()
                                val iv = encryptedData.copyOfRange(0, 16)
                                val ct = encryptedData.copyOfRange(16, encryptedData.size)
                                val decryptedBytes = CryptoUtils.decrypt(iv, ct, legacyKey)
                                val bitmap = android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                                android.util.Log.d("SecureGallery", "Legacy decryption succeeded for photo $index")
                                MediaThumbnail(bitmap, null, mediaItem.mediaType)
                            } catch (legacyE: Exception) {
                                android.util.Log.e("SecureGallery", "Both primary and legacy decryption failed for photo ${mediaItem.name} (index $index)", legacyE)
                                null
                            }
                        }
                    }
                    MediaType.VIDEO -> {
                        try {
                            val thumbnail = if (mediaItem.usesExternalStorage()) {
                                VideoUtils.generateVideoThumbnailFromFile(mediaItem.filePath!!, key)
                            } else {
                                // For internal storage, decrypt and generate thumbnail
                                val encryptedData = mediaItem.getEncryptedData()
                                val iv = encryptedData.copyOfRange(0, 16)
                                val ct = encryptedData.copyOfRange(16, encryptedData.size)
                                val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
                                VideoUtils.generateVideoThumbnailFromDecryptedBytes(decryptedBytes)
                            }
                            val duration = VideoUtils.getVideoDuration(mediaItem, key)
                            MediaThumbnail(thumbnail, duration, mediaItem.mediaType)
                        } catch (e: Exception) {
                            android.util.Log.e("SecureGallery", "Failed to generate video thumbnail for organize mode: ${mediaItem.name}", e)
                            null
                        }
                    }
                }
        })
        
        android.util.Log.d("SecureGallery", "Organize mode: ${organizeMedia.size} media items prepared (${organizeMedia.count { it != null }} non-null)")
        
        // Change action bar title
        supportActionBar?.title = "Organize Media - Hold & Drag to Reorder"
        
        // Show organize mode instructions
        Toast.makeText(this, "Hold and drag media to reorder. Tap 'Done' when finished.", Toast.LENGTH_LONG).show()
        
        // Add organize mode toolbar with Done button
        showOrganizeModeToolbar()
        
        // Refresh adapter to enable drag functionality
        recreateMediaAdapter()
    }
    
    private fun exitOrganizeMode() {
        isOrganizeMode = false
        
        // Restore original title
        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        supportActionBar?.title = galleryName
        
        // Hide organize mode toolbar
        hideOrganizeModeToolbar()
        
        // Save the new custom order
        saveCustomOrder()
        
        // Refresh adapter to disable drag functionality
        recreateMediaAdapter()
        
        Toast.makeText(this, "Media order saved", Toast.LENGTH_SHORT).show()
    }
    
    private fun showOrganizeModeToolbar() {
        // Add the Done button to the existing toolbar
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.galleryToolbar)
        
        // Create done button
        val doneButton = android.widget.Button(this)
        doneButton.id = android.view.View.generateViewId()
        doneButton.text = "Done"
        doneButton.setTextColor(android.graphics.Color.WHITE)
        doneButton.setBackgroundColor(0xFF4CAF50.toInt()) // Green background
        doneButton.setOnClickListener { exitOrganizeMode() }
        
        // Add done button to toolbar
        val layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT,
            androidx.appcompat.widget.Toolbar.LayoutParams.WRAP_CONTENT
        )
        layoutParams.gravity = android.view.Gravity.END
        doneButton.layoutParams = layoutParams
        
        toolbar.addView(doneButton)
    }
    
    private fun hideOrganizeModeToolbar() {
        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.galleryToolbar)
        // Remove any added views (the Done button)
        val childCount = toolbar.childCount
        if (childCount > 1) { // Keep the first child (the default content)
            toolbar.removeViewAt(childCount - 1)
        }
    }
    
    private fun saveCustomOrder() {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        // Update gallery sort order to custom and save current media order
        gallery.sortOrder = GallerySortOrder.CUSTOM
        gallery.customOrder = (0 until gallery.media.size).toMutableList()
        
        GalleryManager.saveGalleries()
    }
    
    private fun recreateMediaAdapter() {
        // Recreate the media adapter to handle organize mode properly
        val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
        
        // Get current thumbnails if not in organize mode
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        photosAdapter = object : RecyclerView.Adapter<MediaThumbnailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaThumbnailViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_thumbnail, parent, false)
                return MediaThumbnailViewHolder(v)
            }
            override fun getItemCount() = if (isOrganizeMode) organizeMedia.size else decryptedMedia.size
            override fun onBindViewHolder(holder: MediaThumbnailViewHolder, position: Int) {
                // Use organize media if in organize mode, otherwise use media thumbnails
                val mediaThumbnail = if (isOrganizeMode && position < organizeMedia.size) {
                    organizeMedia[position]
                } else if (position < decryptedMedia.size) {
                    decryptedMedia[position]
                } else {
                    null
                }
                
                holder.bind(mediaThumbnail, isDeleteMode, selectedPhotosForDeletion.contains(position), isOrganizeMode, position)
                
                if (isOrganizeMode) {
                    // In organize mode, disable clicks (only allow drag)
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.setOnLongClickListener(null)
                } else if (isDeleteMode) {
                    // In delete mode, clicking toggles selection
                    holder.itemView.setOnClickListener {
                        if (selectedPhotosForDeletion.contains(position)) {
                            selectedPhotosForDeletion.remove(position)
                        } else {
                            selectedPhotosForDeletion.add(position)
                        }
                        notifyItemChanged(position)
                        
                        // Auto-cancel delete mode if no media are selected
                        if (selectedPhotosForDeletion.isEmpty()) {
                            exitDeleteMode()
                        }
                    }
                    
                    // No long press needed in delete mode
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode - click to view media
                    holder.itemView.setOnClickListener {
                        val intent = android.content.Intent(this@GalleryActivity, SecureMediaViewerActivity::class.java)
                        intent.putExtra("gallery_name", galleryName)
                        intent.putExtra("position", position)
                        intent.putExtra("pin", pin)
                        intent.putExtra("salt", salt)
                        photoViewerLauncher.launch(intent)
                    }
                    
                    // Long press to enter delete mode
                    holder.itemView.setOnLongClickListener {
                        enterDeleteMode()
                        selectedPhotosForDeletion.add(position)
                        notifyDataSetChanged()
                        true
                    }
                }
            }
            
            override fun onViewRecycled(holder: MediaThumbnailViewHolder) {
                super.onViewRecycled(holder)
                // Clean up ViewHolder properly
                holder.cleanup()
            }
        }
        
        photosRecyclerView.adapter = photosAdapter
    }
    
    private fun setupDragAndDrop(recyclerView: RecyclerView) {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0 // No swipe to delete in organize mode
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                // Only allow drag in organize mode
                if (!isOrganizeMode) return false
                
                val fromPosition = viewHolder.bindingAdapterPosition
                val toPosition = target.bindingAdapterPosition
                
                // Move item in the gallery photos list
                val galleryName = intent.getStringExtra("gallery_name") ?: return false
                val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return false
                
                if (fromPosition < gallery.media.size && toPosition < gallery.media.size) {
                    val item = gallery.media.removeAt(fromPosition)
                    gallery.media.add(toPosition, item)
                    
                    // Also move the decrypted media thumbnail
                    val mediaThumbnail = organizeMedia.removeAt(fromPosition)
                    organizeMedia.add(toPosition, mediaThumbnail)
                    
                    // Notify adapter of the move
                    photosAdapter?.notifyItemMoved(fromPosition, toPosition)
                    
                    return true
                }
                
                return false
            }
            
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // No swipe functionality in organize mode
            }
            
            override fun isLongPressDragEnabled(): Boolean {
                // Only enable drag in organize mode
                return isOrganizeMode
            }
            
            override fun isItemViewSwipeEnabled(): Boolean {
                // Disable swipe when in organize mode
                return false
            }
        })
        
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }
    
    // Export and Import Functions
    
    private fun exportPhotosToMedia() {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        if (gallery.media.isEmpty()) {
            Toast.makeText(this, "No media to export", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Show directory picker
        exportPhotosLauncher.launch(null)
    }
    
    private fun handleExportPhotos(directoryUri: android.net.Uri) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty()) CryptoUtils.deriveKey(pin, salt) else null
        
        if (key == null) {
            Toast.makeText(this, "Unable to decrypt photos for export", Toast.LENGTH_LONG).show()
            return
        }
        
        var exportedCount = 0
        var failedCount = 0
        
        try {
            val documentTree = DocumentsContract.buildDocumentUriUsingTree(directoryUri, DocumentsContract.getTreeDocumentId(directoryUri))
            
            for ((index, media) in gallery.media.withIndex()) {
                try {
                    // Decrypt media
                    val encryptedData = media.getEncryptedData()
                    val iv = encryptedData.copyOfRange(0, 16)
                    val ct = encryptedData.copyOfRange(16, encryptedData.size)
                    val decryptedBytes = try {
                        CryptoUtils.decrypt(iv, ct, key)
                    } catch (e: Exception) {
                        // Try legacy decryption
                        val legacySalt = ByteArray(16)
                        val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                        CryptoUtils.decrypt(iv, ct, legacyKey)
                    }
                    
                    // Create file name with incremented number and gallery name
                    val paddedNumber = String.format("%03d", index + 1) // 001, 002, 003, etc.
                    val sanitizedGalleryName = galleryName.replace(Regex("[^a-zA-Z0-9_-]"), "_") // Remove special characters
                    val fileName = "${paddedNumber}_${sanitizedGalleryName}.jpg"
                    
                    // Create document
                    val newFileUri = DocumentsContract.createDocument(
                        contentResolver,
                        documentTree,
                        "image/jpeg",
                        fileName
                    )
                    
                    if (newFileUri != null) {
                        contentResolver.openOutputStream(newFileUri)?.use { outputStream ->
                            outputStream.write(decryptedBytes)
                            exportedCount++
                        }
                    } else {
                        failedCount++
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to export media: ${media.name}", e)
                    failedCount++
                }
            }
            
            Toast.makeText(this, "Export complete: $exportedCount photos exported, $failedCount failed", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Export failed", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun exportGalleryToFile() {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        
        // Check security level restriction
        if (gallery.securityLevel == 2) {
            Toast.makeText(this, "Export not allowed for Level 2 galleries", Toast.LENGTH_SHORT).show()
            return
        }
        
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "gallery_${galleryName.replace(" ", "_")}_$timestamp.secgal"
        
        exportGalleryLauncher.launch(fileName)
    }
    
    private fun handleExportGallery(fileUri: android.net.Uri) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val media = gallery.media ?: mutableListOf()
        
        try {
            contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                // Collect cached thumbnails for export
                val cachedThumbnails = VideoUtils.collectCachedThumbnails(this, media)
                
                // Convert media to photos for legacy compatibility
                val photosForExport = gallery.media.filter { it.isPhoto() }.map { media ->
                    SecurePhoto(
                        id = media.id,
                        encryptedData = media.getEncryptedData(),
                        name = media.name,
                        date = media.date,
                        customOrder = media.customOrder
                    )
                }
                
                // Create a serializable export format
                val exportData = GalleryExportData(
                    name = gallery.name,
                    salt = gallery.salt,
                    pinHash = gallery.pinHash,
                    photos = photosForExport, // Legacy compatibility
                    media = media, // Include all media items
                    videoThumbnails = cachedThumbnails, // Include cached thumbnails
                    notes = gallery.notes,
                    sortOrder = gallery.sortOrder,
                    customOrder = gallery.customOrder,
                    exportVersion = 2, // Increment version for new format
                    exportDate = System.currentTimeMillis()
                )
                
                // Serialize to bytes
                val serializedData = java.io.ObjectOutputStream(outputStream).use { objOut ->
                    objOut.writeObject(exportData)
                }
                
                Toast.makeText(this, "Gallery exported successfully", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Gallery export failed", e)
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun importGalleryFromFile() {
        importGalleryLauncher.launch(arrayOf("application/octet-stream", "*/*"))
    }
    
    private fun handleImportGallery(fileUri: android.net.Uri) {
        try {
            contentResolver.openInputStream(fileUri)?.use { inputStream ->
                val importData = java.io.ObjectInputStream(inputStream).use { objIn ->
                    objIn.readObject() as GalleryExportData
                }
                
                // Check if gallery name already exists
                val existingGallery = GalleryManager.getGalleries().find { it.name == importData.name }
                if (existingGallery != null) {
                    // Show dialog to choose new name or replace
                    showImportConflictDialog(importData)
                } else {
                    // Import directly
                    performGalleryImport(importData, importData.name)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Gallery import failed", e)
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun showImportConflictDialog(importData: GalleryExportData) {
        val options = arrayOf("Replace existing gallery", "Import with new name", "Cancel")
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Gallery Already Exists")
            .setMessage("A gallery named '${importData.name}' already exists.")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Replace existing
                        performGalleryImport(importData, importData.name, replace = true)
                    }
                    1 -> {
                        // Import with new name
                        showRenameForImportDialog(importData)
                    }
                    2 -> {
                        // Cancel - do nothing
                    }
                }
            }
            .show()
    }
    
    private fun showRenameForImportDialog(importData: GalleryExportData) {
        val nameInput = android.widget.EditText(this)
        nameInput.hint = "Enter new gallery name"
        nameInput.setText("${importData.name} (imported)")
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Import Gallery")
            .setMessage("Enter a new name for the imported gallery:")
            .setView(nameInput)
            .setPositiveButton("Import") { _, _ ->
                val newName = nameInput.text.toString().trim()
                if (newName.isNotEmpty()) {
                    performGalleryImport(importData, newName)
                } else {
                    Toast.makeText(this, "Gallery name cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performGalleryImport(importData: GalleryExportData, galleryName: String, replace: Boolean = false) {
        try {
            if (replace) {
                // Remove existing gallery
                val existingGallery = GalleryManager.getGalleries().find { it.name == galleryName }
                if (existingGallery != null) {
                    GalleryManager.deleteGallery(existingGallery.id)
                }
            }
            
            // Create new gallery with imported data
            val newGallery = Gallery(
                name = galleryName,
                salt = importData.salt,
                pinHash = importData.pinHash,
                sortOrder = importData.sortOrder,
                customOrder = importData.customOrder.toMutableList()
            )
            
            // Add photos and notes (legacy support)  
            // Convert legacy photos to media format
            for (photo in importData.photos) {
                val mediaItem = SecureMedia(
                    id = photo.id,
                    _encryptedData = photo.encryptedData,
                    name = photo.name,
                    date = photo.date,
                    mediaType = MediaType.PHOTO,
                    customOrder = photo.customOrder
                )
                newGallery.media.add(mediaItem)
            }
            newGallery.notes.addAll(importData.notes)
            
            // Add media items if available (version 2+)
            if (importData.exportVersion >= 2) {
                // Check if Gallery has a media property, if not we'll need to add it to legacy photos
                try {
                    // For now, add media items to the photos list (assuming media items are converted to SecurePhoto format)
                    // This will need to be updated when Gallery class supports SecureMedia directly
                    
                    // Restore cached thumbnails for videos
                    VideoUtils.restoreCachedThumbnails(this, importData.media, importData.videoThumbnails)
                } catch (e: Exception) {
                    Log.w("SecureGallery", "Failed to restore video thumbnails, thumbnails will be regenerated", e)
                }
            }
            
            // Add to gallery manager
            GalleryManager.addGallery(newGallery)
            GalleryManager.saveGalleries()
            
            val mediaCount = if (importData.exportVersion >= 2) importData.media.size else importData.photos.size
            val thumbnailCount = if (importData.exportVersion >= 2) importData.videoThumbnails.size else 0
            
            Toast.makeText(this, "Gallery '${galleryName}' imported successfully with $mediaCount items, ${importData.notes.size} notes, and $thumbnailCount video thumbnails", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            android.util.Log.e("SecureGallery", "Failed to import gallery", e)
            Toast.makeText(this, "Import failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * Convert bitmap to byte array for encryption
     */
    private fun bitmapToByteArray(bitmap: android.graphics.Bitmap): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)
        return stream.toByteArray()
    }
    
    /**
     * Check if a URI can actually be deleted before showing deletion dialog
     * This prevents showing deletion options for content URIs that require special permissions
     */
    private fun canDeleteUri(uri: android.net.Uri): Boolean {
        return try {
            // First check basic URI properties
            val scheme = uri.scheme
            val authority = uri.authority
            
            // Skip certain authorities that are known to require special permissions
            when (authority) {
                "com.android.providers.media.documents" -> {
                    android.util.Log.d("SecureGallery", "URI requires MANAGE_DOCUMENTS permission: $uri")
                    return false
                }
                "com.android.providers.downloads.documents" -> {
                    android.util.Log.d("SecureGallery", "Downloads provider URIs often cannot be deleted: $uri")
                    return false
                }
            }
            
            // For content URIs, try to check if we can write to them
            if (scheme == "content") {
                // Check if we can get basic information about the URI
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    // If we can query it, try to check write permissions
                    val writePermission = contentResolver.persistedUriPermissions.any { permission ->
                        permission.uri == uri && permission.isWritePermission
                    }
                    
                    if (!writePermission) {
                        android.util.Log.d("SecureGallery", "No write permission for URI: $uri")
                        return false
                    }
                }
            }
            
            // If all checks pass, assume it can be deleted
            android.util.Log.d("SecureGallery", "URI appears deletable: $uri")
            true
            
        } catch (e: SecurityException) {
            android.util.Log.d("SecureGallery", "SecurityException checking URI deletion capability: $uri - ${e.message}")
            false
        } catch (e: Exception) {
            android.util.Log.d("SecureGallery", "Exception checking URI deletion capability: $uri - ${e.message}")
            false
        }
    }
}

// Data class for gallery export
data class GalleryExportData(
    val name: String,
    val salt: ByteArray,
    val pinHash: ByteArray?,
    val photos: List<SecurePhoto>,
    val media: List<SecureMedia> = emptyList(), // Default for backward compatibility
    val videoThumbnails: Map<String, ByteArray> = emptyMap(), // Default for backward compatibility
    val notes: List<SecureNote>,
    val sortOrder: GallerySortOrder,
    val customOrder: List<Int>,
    val exportVersion: Int,
    val exportDate: Long
) : java.io.Serializable
