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
import android.view.ViewGroup
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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.os.Looper

class GalleryActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        const val PHOTO_VIEWER_REQUEST = 1002
    }

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
    
    // Organize mode for drag-and-drop reordering
    private var isOrganizeMode = false
    private var organizeMedia = mutableListOf<MediaThumbnail?>()
    
    // Security features
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isActivityVisible = true
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isPhotoPickerActive = false
    private var isMediaViewerActive = false
    private var isRecreating = false
    private var isScreenOff = false
    private var activityStartTime = 0L
    
    // Activity result launcher for photo viewer
    private val photoViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isMediaViewerActive = false // Reset the flag when media viewer returns
        if (result.resultCode == RESULT_OK) {
            val returnPosition = result.data?.getIntExtra("return_position", -1) ?: -1
            if (returnPosition >= 0) {
                // Optionally scroll to the position in the gallery that was being viewed
                val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
                photosRecyclerView.scrollToPosition(returnPosition)
            }
        }
    }

    // Activity result launcher for adding multiple pictures and videos
    private val addMediaLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        isPhotoPickerActive = false // Reset the flag when picker returns
        if (uris.isNotEmpty()) {
            handleSelectedMedia(uris)
        }
    }
    
    // Activity result launcher for note editor
    private val noteEditorLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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
        isPhotoPickerActive = true
        addMediaLauncher.launch("*/*") // Accept both images and videos
    }

    private fun handleSelectedMedia(uris: List<android.net.Uri>) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        // Show loading dialog for media import
        val loadingDialog = android.app.ProgressDialog(this).apply {
            setTitle("Importing Media")
            setMessage("Encrypting and processing media files...")
            setCancelable(false)
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = uris.size
            show()
        }
        
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
                        loadingDialog.progress = processedCount
                        loadingDialog.setMessage("Processing media ${processedCount + 1} of ${uris.size}...")
                    }
                    
                    // Determine media type based on MIME type
                    val mimeType = contentResolver.getType(uri)
                    val mediaType = when {
                        mimeType?.startsWith("image/") == true -> MediaType.PHOTO
                        mimeType?.startsWith("video/") == true -> MediaType.VIDEO
                        else -> MediaType.PHOTO // Default to photo if unknown
                    }
                    
                    if (key != null) {
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
                            
                            // Only add URIs that can actually be deleted (not photo picker temporary URIs)
                            val scheme = uri.scheme
                            val authority = uri.authority
                            if (scheme == "content" && authority != "com.android.providers.media.photopicker" && !uri.toString().contains("picker_get_content")) {
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
            if (mediaItem.hasThumbnail()) {
                // Load the pre-generated encrypted thumbnail
                val galleryName = intent.getStringExtra("gallery_name") ?: ""
                val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
                val thumbnailBitmap = ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key!!)
                val duration = if (mediaItem.mediaType == MediaType.VIDEO) {
                    VideoUtils.getVideoDuration(mediaItem, key)
                } else null
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
                thumbnailCache[oldestKey]?.bitmap?.recycle()
                thumbnailCache.remove(oldestKey)
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
                        inPurgeable = true // Allow system to purge bitmap if needed
                        inInputShareable = true
                        inTempStorage = ByteArray(16 * 1024) // Use smaller temp storage
                    }
                    val bitmap = android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
                    
                    // Clear decrypted bytes immediately to free memory
                    System.gc()
                    
                    MediaThumbnail(bitmap, null, mediaItem.mediaType)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Failed to create fallback thumbnail for photo: ${mediaItem.name}", e)
                    null
                }
            }
            MediaType.VIDEO -> {
                try {
                    // Use cached thumbnail if available, otherwise generate using streaming
                    val cachedThumbnail = VideoUtils.loadCachedThumbnail(this, mediaItem)
                    val thumbnail = if (cachedThumbnail != null) {
                        cachedThumbnail
                    } else {
                        // For videos, use streaming approach to avoid memory issues
                        if (mediaItem.usesExternalStorage()) {
                            VideoUtils.generateVideoThumbnailFromFile(mediaItem.filePath!!, key!!)
                        } else {
                            VideoUtils.generateVideoThumbnailFromData(mediaItem.getEncryptedData(), key!!)
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
    
    // Check if we should pause thumbnail loading due to user interaction
    private fun shouldPauseThumbnailLoading(): Boolean {
        val currentTime = System.currentTimeMillis()
        return isUserInteracting || (currentTime - lastInteractionTime < 2000) // Pause for 2 seconds after interaction
    }
    
    // Load only initial thumbnails quickly, then load rest in background
    private fun loadInitialThumbnails(media: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec) {
        val initialCount = minOf(2, media.size) // Load only first 2 thumbnails initially (reduced from 3)
        
        thumbnailExecutor.execute {
            // Set thread priority to lowest to avoid blocking main thread
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_LOWEST)
            
            val initialThumbnails = mutableListOf<MediaThumbnail>()
            
            // Load first few thumbnails quickly with delays between each
            for (i in 0 until initialCount) {
                val thumbnail = loadThumbnailOptimized(media[i], key, i)
                if (thumbnail != null) {
                    initialThumbnails.add(thumbnail)
                    
                    // Update UI immediately for each thumbnail to show progress
                    runOnUiThread {
                        decryptedMedia.add(thumbnail)
                        photosAdapter?.notifyItemInserted(decryptedMedia.size - 1)
                        android.util.Log.d("SecureGallery", "Initial thumbnail loaded: ${decryptedMedia.size}")
                    }
                    
                    // Longer delay between initial thumbnails
                    Thread.sleep(200)
                }
            }
            
            android.util.Log.d("SecureGallery", "Initial thumbnails completed: ${initialThumbnails.size}")
            
            // Load remaining thumbnails in background with much longer delay
            if (media.size > initialCount) {
                Thread.sleep(1000) // Wait much longer before starting remaining thumbnails
                loadRemainingThumbnails(media.subList(initialCount, media.size), key, initialCount)
            }
        }
    }
    
    // Load remaining thumbnails asynchronously to prevent ANR
    private fun loadRemainingThumbnails(remainingMedia: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec, startIndex: Int) {
        // This runs on the same single thread executor, so no need to create new thread
        val loadedThumbnails = mutableListOf<MediaThumbnail>()
        
        remainingMedia.forEachIndexed { index, mediaItem ->
            // Check if we should pause due to user interaction
            while (shouldPauseThumbnailLoading()) {
                Thread.sleep(200)
                android.util.Log.d("SecureGallery", "Pausing thumbnail loading due to user interaction")
            }
            
            val thumbnail = loadThumbnailOptimized(mediaItem, key, startIndex + index)
            if (thumbnail != null) {
                loadedThumbnails.add(thumbnail)
                
                // Update UI one at a time to be extremely gentle on the system
                val currentBatch = loadedThumbnails.toList()
                
                runOnUiThread {
                    val startPosition = decryptedMedia.size
                    decryptedMedia.addAll(currentBatch)
                    photosAdapter?.notifyItemRangeInserted(startPosition, currentBatch.size)
                    
                    android.util.Log.d("SecureGallery", "Remaining thumbnails progress: ${decryptedMedia.size}/${startIndex + remainingMedia.size}")
                }
                
                loadedThumbnails.clear()
                
                // Much longer delay between individual thumbnails (500ms each)
                Thread.sleep(500)
            }
        }
    }

    private fun loadThumbnailsAsync(media: List<SecureMedia>, key: javax.crypto.spec.SecretKeySpec) {
        Thread {
            val loadedThumbnails = mutableListOf<MediaThumbnail>()
            val batchSize = 5 // Process thumbnails in batches to reduce UI thread pressure
            
            media.forEachIndexed { index, mediaItem ->
                val thumbnail = loadThumbnailOptimized(mediaItem, key, index)
                if (thumbnail != null) {
                    loadedThumbnails.add(thumbnail)
                    
                    // Update UI in batches or when we reach the end
                    if (loadedThumbnails.size % batchSize == 0 || index == media.size - 1) {
                        val currentBatch = loadedThumbnails.toList()
                        
                        runOnUiThread {
                            val startPosition = decryptedMedia.size
                            decryptedMedia.addAll(currentBatch)
                            photosAdapter?.notifyItemRangeInserted(startPosition, currentBatch.size)
                            
                            // Log progress
                            android.util.Log.d("SecureGallery", "Loaded batch: ${decryptedMedia.size}/${media.size} thumbnails")
                        }
                        
                        loadedThumbnails.clear()
                        
                        // Add a small delay between batches to prevent overwhelming the system
                        Thread.sleep(50)
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
        
        // Add encrypted media to gallery
        gallery.media.addAll(encryptedMedia)
        // Save gallery data (this will persist the added media)
        GalleryManager.setContext(this)
        GalleryManager.saveGalleries()
        
        // Generate and save encrypted thumbnails for the new media during import
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        encryptedMedia.forEach { mediaItem ->
            try {
                when (mediaItem.mediaType) {
                    MediaType.PHOTO -> {
                        // Decrypt the photo data first to generate thumbnail
                        val encryptedData = mediaItem.getEncryptedData()
                        val iv = encryptedData.copyOfRange(0, 16)
                        val ct = encryptedData.copyOfRange(16, encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ct, key!!)
                        
                        // Generate encrypted thumbnail for photo
                        ThumbnailGenerator.generatePhotoThumbnail(this, decryptedBytes, mediaItem.id.toString(), galleryName, key)
                        android.util.Log.d("SecureGallery", "Generated thumbnail for photo: ${mediaItem.name}")
                    }
                    MediaType.VIDEO -> {
                        // For videos, we need the raw video bytes to generate thumbnail
                        val encryptedData = mediaItem.getEncryptedData()
                        val iv = encryptedData.copyOfRange(0, 16)
                        val ct = encryptedData.copyOfRange(16, encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ct, key!!)
                        
                        // Generate encrypted thumbnail for video
                        ThumbnailGenerator.generateVideoThumbnail(this, decryptedBytes, mediaItem.id.toString(), galleryName, key)
                        android.util.Log.d("SecureGallery", "Generated thumbnail for video: ${mediaItem.name}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to generate thumbnail during import for: ${mediaItem.name}", e)
            }
        }
        
        // Load the pre-generated thumbnails for immediate display
        val newMediaThumbnails = encryptedMedia.mapNotNull { mediaItem ->
            try {
                if (mediaItem.hasThumbnail()) {
                    val thumbnailPath = ThumbnailGenerator.getThumbnailPath(this, galleryName, mediaItem.id.toString())
                    val thumbnailBitmap = ThumbnailGenerator.loadEncryptedThumbnail(thumbnailPath, key!!)
                    val duration = if (mediaItem.mediaType == MediaType.VIDEO) {
                        VideoUtils.getVideoDuration(mediaItem, key!!)
                    } else null
                    MediaThumbnail(thumbnailBitmap, duration, mediaItem.mediaType)
                } else {
                    android.util.Log.w("SecureGallery", "No thumbnail available for: ${mediaItem.name}")
                    null
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to load thumbnail for: ${mediaItem.name}", e)
                null
            }
        }
        
        // Add new thumbnails to the decryptedMedia list
        decryptedMedia.addAll(newMediaThumbnails)
        
        // Refresh the UI to show new media immediately
        photosAdapter?.notifyDataSetChanged()
        
        // Only prompt to delete originals if we have deletable URIs
        if (deletableUris.isNotEmpty()) {
            deleteDialog = android.app.AlertDialog.Builder(this)
                .setTitle("Delete Original Files?")
                .setMessage("Do you want to delete the original files from your device? (${deletableUris.size} of ${originalUris.size} files can be deleted)")
                .setPositiveButton("Delete") { _, _ ->
                    var deletedCount = 0
                    var failedCount = 0
                    for (uri in deletableUris) {
                        try {
                            contentResolver.delete(uri, null, null)
                            deletedCount++
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
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
                Toast.makeText(this, "Media imported successfully. Original files remain in your gallery.", Toast.LENGTH_LONG).show()
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
            if (index < gallery.photos.size) {
                gallery.photos.removeAt(index)
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
        val intent = Intent(this, NoteEditorActivity::class.java)
        intent.putExtra("gallery_name", intent.getStringExtra("gallery_name"))
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
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
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
                safeRecreate() // Refresh the activity to show updated notes
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to encrypt note", e)
            }
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
        safeRecreate()
    }

    override fun onDestroy() {
        android.util.Log.d("SecureGallery", "GalleryActivity onDestroy() called")
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
        // Clear PIN from memory when gallery is destroyed
        TempPinHolder.clear()
        super.onDestroy()
    }
    
    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        // Security feature: close gallery when app loses focus
        // BUT don't close if photo picker is active or we're recreating the activity
        // ALSO don't close if screen is off (let screen off receiver handle it to avoid duplicate triggers)
        // ALSO add a small delay to prevent immediate closure during activity startup
        // ALSO ignore pauses within the first 2 seconds of activity creation (startup grace period)
        val timeSinceStart = System.currentTimeMillis() - activityStartTime
        if (!isPhotoPickerActive && !isMediaViewerActive && !isRecreating && !isScreenOff && timeSinceStart > 2000) {
            android.util.Log.d("SecureGallery", "onPause called - scheduling delayed security check (time since start: ${timeSinceStart}ms)")
            // Use a small delay to prevent immediate closure during activity startup
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // Double-check that we're still paused and conditions haven't changed
                if (!isActivityVisible && !isPhotoPickerActive && !isMediaViewerActive && !isRecreating && !isScreenOff) {
                    android.util.Log.d("SecureGallery", "Delayed security check - triggering security for app focus loss")
                    closeGalleryForSecurity()
                } else {
                    android.util.Log.d("SecureGallery", "Delayed security check - conditions changed, NOT triggering security")
                }
            }, 250) // 250ms delay to allow for brief lifecycle transitions
        } else {
            android.util.Log.d("SecureGallery", "onPause called - NOT triggering security (photoPickerActive=$isPhotoPickerActive, mediaViewerActive=$isMediaViewerActive, recreating=$isRecreating, screenOff=$isScreenOff, timeSinceStart=${timeSinceStart}ms)")
        }
    }
    
    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        isScreenOff = false // Reset screen off flag when activity resumes
        // Reset security trigger when activity becomes visible again after being paused
        // This ensures security works again after each legitimate return to gallery
        android.util.Log.d("SecureGallery", "onResume called, clearing security trigger")
        
        // Refresh gallery data to show any new media that was added
        refreshGalleryData()
    }
    
    private fun refreshGalleryData() {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        // Check if media count has changed
        if (gallery.media.size != decryptedMedia.size || gallery.media.size > decryptedMedia.count { it != null }) {
            android.util.Log.d("SecureGallery", "Media count changed, refreshing gallery: ${gallery.media.size} media, ${decryptedMedia.size} thumbnails")
            
            // Clear existing data for refresh
            decryptedMedia.clear()
            photosAdapter?.notifyDataSetChanged()
            
            // Load thumbnails asynchronously to prevent ANR - DO NOT load synchronously here!
            if (key != null && gallery.media.isNotEmpty()) {
                loadInitialThumbnails(gallery.media, key)
            }
            
            android.util.Log.d("SecureGallery", "Gallery refresh started asynchronously")
        }
    }
    
    // Security feature implementations
    private fun initializeSecurity() {
        // Initialize accelerometer
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        // Register accelerometer listener
        accelerometer?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        
        // Register screen off receiver
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    val timeSinceStart = System.currentTimeMillis() - activityStartTime
                    if (timeSinceStart > 2000) {
                        android.util.Log.d("SecureGallery", "Screen off detected after grace period (${timeSinceStart}ms), triggering security")
                        isScreenOff = true
                        closeGalleryForSecurity()
                    } else {
                        android.util.Log.d("SecureGallery", "Screen off detected during grace period (${timeSinceStart}ms), ignoring")
                    }
                }
            }
        }
        
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)
    }
    
    private fun cleanupSecurity() {
        // Unregister accelerometer listener
        sensorManager.unregisterListener(this)
        
        // Unregister screen off receiver
        screenOffReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Receiver might not be registered
            }
        }
        screenOffReceiver = null
    }
    
    private fun closeGalleryForSecurity() {
        android.util.Log.d("SecureGallery", "closeGalleryForSecurity called, securityTriggered=${TempPinHolder.securityTriggered}")
        if (!TempPinHolder.securityTriggered) {
            android.util.Log.d("SecureGallery", "Security not already triggered, setting flag and finishing activity")
            TempPinHolder.securityTriggered = true
            // Clear PIN from memory for security - user must re-enter PIN to access gallery again
            TempPinHolder.clear()
            finish() // Close gallery and return to calculator
        } else {
            android.util.Log.d("SecureGallery", "Security already triggered, ignoring call")
        }
    }
    
    // Safe recreation that doesn't trigger security closure
    private fun safeRecreate() {
        isRecreating = true
        recreate()
    }
    
    // SensorEventListener implementation for accelerometer
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER && isActivityVisible) {
            val currentTime = System.currentTimeMillis()
            // Grace period after activity start to prevent false triggers during app launch
            if (currentTime - activityStartTime > 3000) { // 3 second grace period
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                
                // Check for face down detection (Z-axis negative with significant magnitude)
                if (z < -8.0 && Math.abs(x) < 3.0 && Math.abs(y) < 3.0) {
                    android.util.Log.d("SecureGallery", "Security trigger: Face down detected")
                    closeGalleryForSecurity()
                }
                
                // Check for shake detection (significant acceleration)
                val acceleration = kotlin.math.sqrt((x * x + y * y + z * z).toDouble())
                if (acceleration > 15.0 && !isScreenOff) { // Only trigger if screen is not off
                    android.util.Log.d("SecureGallery", "Security trigger: Device shake detected")
                    closeGalleryForSecurity()
                }
            } else if (currentTime - activityStartTime <= 3000) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                if (z < -8.0 && Math.abs(x) < 3.0 && Math.abs(y) < 3.0) {
                    android.util.Log.d("SecureGallery", "Face down detected during grace period (${currentTime - activityStartTime}ms), ignoring")
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.d("SecureGallery", "GalleryActivity onCreate() started")
        setContentView(R.layout.activity_gallery)

        // Record activity start time for startup grace period
        activityStartTime = System.currentTimeMillis()
        android.util.Log.d("SecureGallery", "Activity start time recorded: $activityStartTime")

        // Initialize GalleryManager context
        GalleryManager.setContext(this)
        
        // Initialize security features
        initializeSecurity()
        
        // Clear security trigger since we successfully entered the gallery
        TempPinHolder.clearSecurityTrigger()
        
        // Reset recreation flag
        isRecreating = false

        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        
        // Get gallery data from GalleryManager instead of Intent
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        val notes = gallery?.notes ?: mutableListOf()
        val media = gallery?.media ?: mutableListOf() // Changed from photos to media

        // Apply sort order to media
        if (gallery != null) {
            GallerySortUtils.applySortOrder(gallery)
        }

        // Decrypt notes using pin and salt
        val pin = TempPinHolder.pin ?: ""
        val salt = GalleryManager.getGalleries().find { it.name == galleryName }?.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null

        val decryptedNotes = notes.map { note ->
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
            Pair(title, body)
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

        // Setup media RecyclerView with two-column grid
        val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
        photosRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        
        // Add scroll listener to pause thumbnail loading during user interaction
        photosRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING, RecyclerView.SCROLL_STATE_SETTLING -> {
                        isUserInteracting = true
                        lastInteractionTime = System.currentTimeMillis()
                    }
                    RecyclerView.SCROLL_STATE_IDLE -> {
                        isUserInteracting = false
                        lastInteractionTime = System.currentTimeMillis()
                    }
                }
            }
        })
        
        // Initialize empty media list - thumbnails will be loaded asynchronously
        this.decryptedMedia = mutableListOf()
        
        // Load only first few thumbnails initially to prevent ANR, load rest later
        if (key != null && media.isNotEmpty()) {
            loadInitialThumbnails(media, key)
        }

        photosAdapter = object : RecyclerView.Adapter<MediaThumbnailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaThumbnailViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media_thumbnail, parent, false)
                return MediaThumbnailViewHolder(v)
            }
            override fun getItemCount() = if (isOrganizeMode) organizeMedia.size else decryptedMedia.size
            override fun onBindViewHolder(holder: MediaThumbnailViewHolder, position: Int) {
                // Use organize media if in organize mode, otherwise use decrypted media
                val mediaThumbnail = if (isOrganizeMode && position < organizeMedia.size) {
                    organizeMedia[position]
                } else if (position < decryptedMedia.size) {
                    decryptedMedia[position]
                } else {
                    null
                }
                
                holder.bind(mediaThumbnail, isDeleteMode, selectedPhotosForDeletion.contains(position), isOrganizeMode)
                
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
                        isMediaViewerActive = true
                        val intent = android.content.Intent(this@GalleryActivity, SecureMediaViewerActivity::class.java)
                        intent.putExtra(SecureMediaViewerActivity.EXTRA_GALLERY_NAME, galleryName)
                        intent.putExtra(SecureMediaViewerActivity.EXTRA_POSITION, position)
                        intent.putExtra(SecureMediaViewerActivity.EXTRA_PIN, pin)
                        intent.putExtra(SecureMediaViewerActivity.EXTRA_SALT, salt)
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
        }
        
        photosRecyclerView.adapter = photosAdapter

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
        val pinInput = android.widget.EditText(this)
        pinInput.hint = "Enter PIN"
        val nameInput = android.widget.EditText(this)
        nameInput.hint = "Enter Gallery Name"
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.addView(pinInput)
        layout.addView(nameInput)
        android.app.AlertDialog.Builder(this)
            .setTitle("Create Gallery")
            .setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val pin = pinInput.text.toString()
                val name = nameInput.text.toString()
                val result = com.darkempire78.opencalculator.securegallery.GalleryManager.createGallery(pin, name)
                android.util.Log.d("SecureGallery", "CreateGalleryDialog: pin=$pin name=$name result=$result")
                if (result) {
                    Toast.makeText(this, "Gallery created", Toast.LENGTH_SHORT).show()
                    // Close current and open new gallery
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

    private fun showCustomGalleryMenu() {
        val dialog = Dialog(this)
        
        // Only show normal menu items, delete mode is handled by dedicated buttons
        val menuItems = listOf(
            Pair("Add Media", R.id.action_add_pictures), // Changed text but keeping same ID for compatibility
            Pair("Export Photos", R.id.action_export_photos),
            Pair("Export Gallery", R.id.action_export_gallery),
            Pair("Import Gallery", R.id.action_import_gallery),
            Pair("Sort Options", -1), // Special submenu item
            Pair("Create Gallery", R.id.action_create_gallery),
            Pair("Rename Gallery", R.id.action_rename_gallery),
            Pair("Delete Gallery", R.id.action_delete_gallery)
        )
        
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
        
        fun bind(mediaThumbnail: MediaThumbnail?, isDeleteMode: Boolean = false, isSelected: Boolean = false, isOrganizeMode: Boolean = false) {
            val imageView = itemView.findViewById<android.widget.ImageView>(R.id.mediaThumbnail)
            val playIcon = itemView.findViewById<android.widget.ImageView>(R.id.playIcon)
            val durationText = itemView.findViewById<android.widget.TextView>(R.id.videoDuration)
            
            // Recycle previous bitmap to free memory
            currentBitmap?.let { oldBitmap ->
                if (!oldBitmap.isRecycled) {
                    oldBitmap.recycle()
                }
            }
            currentBitmap = null
            
            if (mediaThumbnail?.bitmap != null) {
                currentBitmap = mediaThumbnail.bitmap
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
            currentBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            currentBitmap = null
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
                GallerySortUtils.sortPhotosByName(gallery.photos)
                Toast.makeText(this, "Photos sorted by name", Toast.LENGTH_SHORT).show()
            }
            GallerySortOrder.DATE -> {
                GallerySortUtils.sortPhotosByDate(gallery.photos)
                Toast.makeText(this, "Photos sorted by date", Toast.LENGTH_SHORT).show()
            }
            GallerySortOrder.CUSTOM -> {
                if (gallery.customOrder.isNotEmpty()) {
                    GallerySortUtils.sortPhotosByCustomOrder(gallery.photos, gallery.customOrder)
                    Toast.makeText(this, "Photos sorted by custom order", Toast.LENGTH_SHORT).show()
                } else {
                    // Initialize custom order with current order
                    gallery.customOrder = (0 until gallery.photos.size).toMutableList()
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
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        android.util.Log.d("SecureGallery", "Organize mode setup: pin='$pin', salt=${salt?.contentToString()}, galleryName='$galleryName'")
        android.util.Log.d("SecureGallery", "Gallery details: id=${gallery.id}, mediaCount=${gallery.media.size}, hasHash=${gallery.pinHash != null}")
        
        if (key == null) {
            android.util.Log.e("SecureGallery", "Cannot derive key: pin='$pin', salt is null=${salt == null}")
            Toast.makeText(this, "Cannot organize media: decryption key unavailable", Toast.LENGTH_LONG).show()
            return
        }
        
        organizeMedia.clear()
        organizeMedia.addAll(gallery.media.mapIndexed { index, mediaItem ->
            if (key != null) {
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
                            val thumbnail = VideoUtils.generateVideoThumbnail(this@GalleryActivity, mediaItem, key)
                            val duration = VideoUtils.getVideoDuration(mediaItem, key)
                            MediaThumbnail(thumbnail, duration, mediaItem.mediaType)
                        } catch (e: Exception) {
                            android.util.Log.e("SecureGallery", "Failed to generate video thumbnail for organize mode: ${mediaItem.name}", e)
                            null
                        }
                    }
                }
            } else {
                android.util.Log.w("SecureGallery", "No key available for media decryption in organize mode")
                null
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
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
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
                
                holder.bind(mediaThumbnail, isDeleteMode, selectedPhotosForDeletion.contains(position), isOrganizeMode)
                
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
                
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                
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
        
        if (gallery.photos.isEmpty()) {
            Toast.makeText(this, "No photos to export", Toast.LENGTH_SHORT).show()
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
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        if (key == null) {
            Toast.makeText(this, "Unable to decrypt photos for export", Toast.LENGTH_LONG).show()
            return
        }
        
        var exportedCount = 0
        var failedCount = 0
        
        try {
            val documentTree = DocumentsContract.buildDocumentUriUsingTree(directoryUri, DocumentsContract.getTreeDocumentId(directoryUri))
            
            for ((index, photo) in gallery.photos.withIndex()) {
                try {
                    // Decrypt photo
                    val encryptedData = photo.encryptedData
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
                    android.util.Log.e("SecureGallery", "Failed to export photo: ${photo.name}", e)
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
                
                // Create a serializable export format
                val exportData = GalleryExportData(
                    name = gallery.name,
                    salt = gallery.salt,
                    pinHash = gallery.pinHash,
                    photos = gallery.photos,
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
            newGallery.photos.addAll(importData.photos)
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
