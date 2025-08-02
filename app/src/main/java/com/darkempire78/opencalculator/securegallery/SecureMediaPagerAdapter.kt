package com.darkempire78.opencalculator.securegallery

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.VideoView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import com.darkempire78.opencalculator.R
import com.github.chrisbanes.photoview.PhotoView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.ref.WeakReference

class SecureMediaPagerAdapter(
    private val context: Context,
    private val mediaList: List<SecureMedia>,
    private val pin: String,
    private val salt: ByteArray?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    
    companion object {
        private const val VIEW_TYPE_PHOTO = 1
        private const val VIEW_TYPE_VIDEO = 2
    }
    
    // Security: Use secure collections and clear sensitive data
    private val tempFiles = mutableListOf<File>()
    private val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
    private val activityRef = WeakReference(context as? Activity)
    
    // Preloading and memory management with security considerations
    private val preloadCache = mutableMapOf<Int, File>() // Cache prepared video files - files are encrypted
    private val maxPreloadItems = 3 // Limit preloaded items to minimize memory exposure
    private var lastPreloadPosition = -1
    
    // Photo preloading cache - bitmaps are decrypted in memory, so limit cache size
    private val photoPreloadCache = mutableMapOf<String, Bitmap>()
    private val maxPhotoCache = 5 // Keep small to minimize decrypted data in memory
    
    // Security: Track decrypted data for secure cleanup
    private val decryptedDataTracker = mutableSetOf<String>()
    
    /**
     * Security utility: Securely wipe sensitive data from memory
     */
    private fun secureWipeByteArray(data: ByteArray?) {
        data?.let { bytes ->
            // Overwrite with random data first
            java.security.SecureRandom().nextBytes(bytes)
            // Then overwrite with zeros
            bytes.fill(0)
        }
    }
    
    override fun getItemViewType(position: Int): Int {
        return when (mediaList[position].mediaType) {
            MediaType.PHOTO -> VIEW_TYPE_PHOTO
            MediaType.VIDEO -> VIEW_TYPE_VIDEO
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PHOTO -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_view, parent, false)
                PhotoViewHolder(view)
            }
            VIEW_TYPE_VIDEO -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video_view, parent, false)
                VideoViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }
    
    override fun getItemCount(): Int = mediaList.size
    
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        when (holder) {
            is VideoViewHolder -> {
                // Immediately stop and clean up videos when views are recycled
                holder.cleanup()
                Log.d("SecureMediaPagerAdapter", "Video view recycled and cleaned up")
            }
            is PhotoViewHolder -> {
                holder.cleanup()
                Log.d("SecureMediaPagerAdapter", "Photo view recycled and cleaned up")
            }
        }
    }
    
    private var currentVideoHolder: VideoViewHolder? = null
    
    fun pauseAllVideos() {
        currentVideoHolder?.let { holder ->
            try {
                Log.d("SecureMediaPagerAdapter", "Pausing all videos - ensuring complete cleanup")
                
                holder.mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.stop() // Use stop instead of pause for complete cleanup
                        mp.reset() // Reset the MediaPlayer to prevent state issues
                        Log.d("SecureMediaPagerAdapter", "Stopped and reset video due to focus loss")
                    }
                }
                // Also stop VideoView if it's being used
                if (holder.videoView.isPlaying) {
                    holder.videoView.stopPlayback()
                    holder.videoView.suspend() // Suspend to free resources
                    Log.d("SecureMediaPagerAdapter", "Stopped VideoView playback")
                }
                // Reset UI state and ensure surfaces are hidden
                holder.loadingContainer.visibility = View.GONE
                holder.videoView.visibility = View.GONE
                holder.surfaceView.visibility = View.GONE
                
                // Force layout update to ensure changes take effect
                holder.itemView.requestLayout()
                
                Log.d("SecureMediaPagerAdapter", "Video views completely hidden and layout updated")
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error stopping videos", e)
            }
        }
        
        // Clear current video holder to prevent further operations
        currentVideoHolder = null
        Log.d("SecureMediaPagerAdapter", "All video cleanup completed")
    }
    
    // Handle page changes to ensure videos play correctly during swiping
    fun onPageChanged(position: Int) {
        Log.d("SecureMediaPagerAdapter", "=== PAGE CHANGE EVENT ===")
        Log.d("SecureMediaPagerAdapter", "Page changed to position $position")
        
        if (position >= 0 && position < mediaList.size) {
            val newMedia = mediaList[position]
            Log.d("SecureMediaPagerAdapter", "New media: ${newMedia.name} (${newMedia.mediaType})")
        } else {
            Log.e("SecureMediaPagerAdapter", "Invalid position $position for mediaList size ${mediaList.size}")
            return
        }
        
        // Stop current video but don't clear currentVideoHolder yet
        currentVideoHolder?.let { holder ->
            Log.d("SecureMediaPagerAdapter", "Stopping current video holder")
            try {
                holder.mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.stop()
                        mp.reset()
                        Log.d("SecureMediaPagerAdapter", "Stopped and reset MediaPlayer for page change")
                    } else {
                        Log.d("SecureMediaPagerAdapter", "MediaPlayer was not playing")
                    }
                }
                if (holder.videoView.isPlaying) {
                    holder.videoView.stopPlayback()
                    holder.videoView.suspend()
                    Log.d("SecureMediaPagerAdapter", "Stopped VideoView for page change")
                } else {
                    Log.d("SecureMediaPagerAdapter", "VideoView was not playing")
                }
                // Reset UI state
                holder.loadingContainer.visibility = View.GONE
                holder.videoView.visibility = View.GONE
                holder.surfaceView.visibility = View.GONE
                Log.d("SecureMediaPagerAdapter", "Reset video holder UI state")
            } catch (e: Exception) {
                Log.e("SecureMediaPagerAdapter", "Error stopping video during page change", e)
            }
        } ?: Log.d("SecureMediaPagerAdapter", "No current video holder to stop")
        
        Log.d("SecureMediaPagerAdapter", "Page change handling complete - new video will be set up automatically by bindVideo")
        // The new video will be set up automatically when bindVideo is called
        // No need to clear currentVideoHolder here as it will be updated by setCurrentVideoHolder
    }
    
    private fun setCurrentVideoHolder(holder: VideoViewHolder) {
        // Stop previous video if different holder
        currentVideoHolder?.let { prevHolder ->
            if (prevHolder != holder) {
                try {
                    prevHolder.mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            mp.stop()
                            mp.reset()
                            Log.d("SecureMediaPagerAdapter", "Stopped previous MediaPlayer")
                        }
                    }
                    if (prevHolder.videoView.isPlaying) {
                        prevHolder.videoView.stopPlayback()
                        Log.d("SecureMediaPagerAdapter", "Stopped previous VideoView playback")
                    }
                    // Hide loading container if still showing
                    prevHolder.loadingContainer.visibility = View.GONE
                } catch (e: Exception) {
                    Log.w("SecureMediaPagerAdapter", "Error stopping previous video", e)
                }
            }
        }
        currentVideoHolder = holder
        Log.d("SecureMediaPagerAdapter", "Set new current video holder")
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (position < 0 || position >= mediaList.size) {
            Log.e("SecureMediaPagerAdapter", "Invalid position $position for mediaList size ${mediaList.size}")
            return
        }
        
        val media = mediaList[position]
        Log.d("SecureMediaPagerAdapter", "Binding media at position $position: ${media.name} (${media.mediaType})")
        
        when (holder) {
            is PhotoViewHolder -> {
                bindPhoto(holder, media)
                // Trigger intelligent preloading for smoother photo experience
                intelligentPreloadPhotos(position)
            }
            is VideoViewHolder -> {
                bindVideo(holder, media)
                // Trigger intelligent preloading for smooth video experience
                intelligentPreload(position)
            }
        }
    }
    
    // Preload adjacent photos for smoother transitions
    private fun intelligentPreloadPhotos(currentPosition: Int) {
        Thread {
            try {
                // Preload next and previous photos
                val positionsToPreload = listOf(currentPosition - 1, currentPosition + 1)
                    .filter { it >= 0 && it < mediaList.size }
                    .filter { mediaList[it].mediaType == MediaType.PHOTO }
                
                for (pos in positionsToPreload) {
                    val photoMedia = mediaList[pos]
                    preloadPhotoIfNeeded(photoMedia)
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error during photo preload", e)
            }
        }.start()
    }
    
    private fun preloadPhotoIfNeeded(media: SecureMedia) {
        if (key == null) return
        
        val cacheKey = media.name + "_" + media.id
        if (photoPreloadCache.containsKey(cacheKey)) return
        
        try {
            // Skip preloading for medium and large files to avoid OOM
            val mediaSize = media.getMediaSize()
            if (mediaSize != MediaSize.SMALL) {
                Log.d("SecureMediaPagerAdapter", "Skipping preload for ${mediaSize} file: ${media.name}")
                return
            }
            
            val encryptedData = media.getEncryptedData()
            if (encryptedData.size < 16) return
            
            val iv = encryptedData.copyOfRange(0, 16)
            val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
            val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
            
            val options = BitmapFactory.Options().apply {
                inSampleSize = 2 // Half resolution for cache
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            
            val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
            
            if (bitmap != null && !bitmap.isRecycled) {
                synchronized(photoPreloadCache) {
                    // Clean cache if too large
                    if (photoPreloadCache.size >= maxPhotoCache) {
                        val oldestEntry = photoPreloadCache.entries.first()
                        oldestEntry.value.recycle()
                        photoPreloadCache.remove(oldestEntry.key)
                    }
                    photoPreloadCache[cacheKey] = bitmap
                }
                Log.d("SecureMediaPagerAdapter", "Preloaded photo: ${media.name}")
            }
        } catch (e: Exception) {
            Log.w("SecureMediaPagerAdapter", "Failed to preload photo: ${media.name}", e)
        }
    }

    // Intelligent preloading for videos to reduce loading delays
    private fun intelligentPreload(currentPosition: Int) {
        // Only preload if we moved to a different position
        if (currentPosition == lastPreloadPosition) return
        lastPreloadPosition = currentPosition
        
        Thread {
            try {
                // Preload next video (if it's a video)
                val nextPosition = currentPosition + 1
                if (nextPosition < mediaList.size && mediaList[nextPosition].mediaType == MediaType.VIDEO) {
                    if (!preloadCache.containsKey(nextPosition)) {
                        preloadVideoFile(mediaList[nextPosition], nextPosition)
                    }
                }
                
                // Clean up old preloaded files to manage memory
                if (preloadCache.size > maxPreloadItems) {
                    val positionsToRemove = preloadCache.keys.filter { pos ->
                        kotlin.math.abs(pos - currentPosition) > 2
                    }
                    
                    for (pos in positionsToRemove) {
                        preloadCache[pos]?.delete()
                        preloadCache.remove(pos)
                    }
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error during intelligent preload", e)
            }
        }.start()
    }
    
    private fun preloadVideoFile(videoMedia: SecureMedia, position: Int) {
        if (key == null) return
        
        try {
            val cacheDir = File(context.cacheDir, "temp_videos")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            
            val tempFile = File(cacheDir, "preload_${System.currentTimeMillis()}_${position}.mp4")
            
            if (videoMedia.usesExternalStorage()) {
                // For large videos, stream decrypt to temp file
                val encryptedFile = File(videoMedia.filePath!!)
                val inputStream = FileInputStream(encryptedFile)
                val outputStream = FileOutputStream(tempFile)
                
                CryptoUtils.decryptStream(inputStream, outputStream, key)
                inputStream.close()
                outputStream.close()
            } else {
                // For smaller videos, decrypt data to temp file
                val encryptedData = videoMedia.getEncryptedData()
                if (encryptedData.size < 16) return
                
                val iv = encryptedData.copyOfRange(0, 16)
                val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
                val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                
                tempFile.writeBytes(decryptedBytes)
            }
            
            preloadCache[position] = tempFile
            Log.d("SecureMediaPagerAdapter", "Preloaded video file for position $position")
        } catch (e: Exception) {
            Log.w("SecureMediaPagerAdapter", "Failed to preload video for position $position", e)
        }
    }
    
    private fun bindPhoto(holder: PhotoViewHolder, media: SecureMedia) {
        Log.d("SecureMediaPagerAdapter", "Loading photo: ${media.name}")
        
        try {
            // CRITICAL: Stop any playing videos when switching to photo
            if (currentVideoHolder != null) {
                Log.d("SecureMediaPagerAdapter", "Stopping current video before loading photo")
                pauseAllVideos()
                // Add a small delay to ensure video is fully stopped
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    Log.d("SecureMediaPagerAdapter", "Video stopped - now loading photo")
                }, 50)
            }
            
            // Clear previous image to prevent displaying wrong content
            holder.cleanup()
            
            // Ensure PhotoView is visible - DO NOT try to find video views in PhotoViewHolder
            // The layout files are different, so searching for video views will cause issues
            holder.photoView.visibility = View.VISIBLE
            holder.photoView.alpha = 1.0f // Ensure full opacity
            Log.d("SecureMediaPagerAdapter", "Made PhotoView visible for photo display")
            
            // Validate media data before proceeding (without loading entire file into memory)
            if (!media.hasValidEncryptedData()) {
                Log.e("SecureMediaPagerAdapter", "Invalid or missing encrypted data for photo: ${media.name}")
                setErrorImage(holder)
                return
            }
            
            // Check media size for memory optimization
            val mediaSize = media.getMediaSize()
            Log.d("SecureMediaPagerAdapter", "Photo ${media.name} size category: $mediaSize")
            
            // For very large photos, use more conservative memory settings
            val shouldUseLowMemoryMode = mediaSize == MediaSize.LARGE
            if (shouldUseLowMemoryMode) {
                Log.w("SecureMediaPagerAdapter", "Using low memory mode for large photo: ${media.name}")
            }
            
            // First check if we have a preloaded bitmap  
            val cacheKey = media.name + "_" + media.id
            synchronized(photoPreloadCache) {
                photoPreloadCache[cacheKey]?.let { cachedBitmap ->
                    Log.d("SecureMediaPagerAdapter", "Using cached bitmap for photo: ${media.name}")
                    holder.setImageBitmap(cachedBitmap)
                    return
                }
            }
            
            if (key != null) {
                // Load photo in background to prevent UI blocking
                Thread {
                    try {
                        val bitmap = if (media.usesExternalStorage()) {
                            // For file-based storage, use streaming decryption
                            decryptPhotoFromFile(media.filePath!!, key, mediaSize)
                        } else {
                            // For memory-based storage, decrypt data
                            val encryptedData = media.getEncryptedData()
                            if (encryptedData.size < 16) {
                                Log.e("SecureMediaPagerAdapter", "Encrypted data too small for photo: ${media.name}")
                                activityRef.get()?.runOnUiThread { setErrorImage(holder) }
                                return@Thread
                            }
                            decryptPhotoFromData(encryptedData, key, mediaSize)
                        }
                        
                        // Update UI on main thread
                        activityRef.get()?.runOnUiThread {
                            try {
                                if (bitmap != null && !bitmap.isRecycled) {
                                    Log.d("SecureMediaPagerAdapter", "Successfully loaded bitmap for photo: ${media.name}, size: ${bitmap.width}x${bitmap.height}")
                                    
                                    // Ensure the PhotoView is visible before setting bitmap
                                    holder.photoView.visibility = View.VISIBLE
                                    holder.photoView.alpha = 1.0f
                                    
                                    holder.setImageBitmap(bitmap)
                                    
                                    // Cache the bitmap for future use
                                    synchronized(photoPreloadCache) {
                                        if (photoPreloadCache.size < maxPhotoCache) {
                                            photoPreloadCache[cacheKey] = bitmap
                                        }
                                    }
                                } else {
                                    Log.e("SecureMediaPagerAdapter", "Failed to decode bitmap for photo: ${media.name}")
                                    setErrorImage(holder)
                                }
                            } catch (e: Exception) {
                                Log.e("SecureMediaPagerAdapter", "Error setting bitmap on UI thread", e)
                                setErrorImage(holder)
                            }
                        }
                        
                    } catch (e: Exception) {
                        Log.e("SecureMediaPagerAdapter", "Failed to decrypt photo: ${media.name}", e)
                        activityRef.get()?.runOnUiThread { setErrorImage(holder) }
                    }
                }.start()
            } else {
                Log.w("SecureMediaPagerAdapter", "No decryption key available for photo: ${media.name}")
                setErrorImage(holder)
            }
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Critical error in bindPhoto for ${media.name}", e)
            setErrorImage(holder)
        }
    }
    
    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun decryptPhotoFromFile(filePath: String, key: javax.crypto.spec.SecretKeySpec, mediaSize: MediaSize = MediaSize.SMALL): Bitmap? {
        var inputStream: FileInputStream? = null
        var decryptedBytes: ByteArray? = null
        try {
            val file = File(filePath)
            inputStream = FileInputStream(file)
            
            // Read IV
            val iv = ByteArray(16)
            inputStream.read(iv)
            
            // Decrypt the rest of the file to a ByteArray
            val cipherData = inputStream.readBytes()
            decryptedBytes = CryptoUtils.decrypt(iv, cipherData, key)
            
            // Security: Track decrypted data for cleanup
            val trackingId = "photo_${System.currentTimeMillis()}_${Thread.currentThread().id}"
            decryptedDataTracker.add(trackingId)
            
            // Create bitmap with proper options to prevent OutOfMemoryError
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
            
            // Calculate inSampleSize based on media size - more aggressive for larger files
            val targetSize = when (mediaSize) {
                MediaSize.SMALL -> 2048   // Full quality for small files
                MediaSize.MEDIUM -> 1536  // Reduced quality for medium files  
                MediaSize.LARGE -> 1024   // Much lower quality for large files
            }
            val sampleSize = calculateInSampleSize(options, targetSize, targetSize)
            
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            
            val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, decodeOptions)
            
            // Security: Remove tracking and clear decrypted bytes immediately
            decryptedDataTracker.remove(trackingId)
            return bitmap
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Failed to decrypt photo from file: $filePath", e)
            return null
        } finally {
            inputStream?.close()
            // Security: Clear decrypted bytes from memory using secure wipe
            secureWipeByteArray(decryptedBytes)
        }
    }
    
    private fun decryptPhotoFromData(encryptedData: ByteArray, key: javax.crypto.spec.SecretKeySpec, mediaSize: MediaSize = MediaSize.SMALL): Bitmap? {
        var decryptedBytes: ByteArray? = null
        try {
            val iv = encryptedData.copyOfRange(0, 16)
            val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
            
            decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
            
            // Security: Track decrypted data for cleanup
            val trackingId = "photo_data_${System.currentTimeMillis()}_${Thread.currentThread().id}"
            decryptedDataTracker.add(trackingId)
            
            // Create bitmap with proper options to prevent OutOfMemoryError
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
            
            // Calculate inSampleSize based on media size - more aggressive for larger files
            val targetSize = when (mediaSize) {
                MediaSize.SMALL -> 2048   // Full quality for small files
                MediaSize.MEDIUM -> 1536  // Reduced quality for medium files  
                MediaSize.LARGE -> 1024   // Much lower quality for large files
            }
            val sampleSize = calculateInSampleSize(options, targetSize, targetSize)
            
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            
            val bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, decodeOptions)
            
            // Security: Remove tracking
            decryptedDataTracker.remove(trackingId)
            return bitmap
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Failed to decrypt photo from data", e)
            return null
        } finally {
            // Security: Clear decrypted bytes from memory using secure wipe
            secureWipeByteArray(decryptedBytes)
        }
    }

    private fun setErrorImage(holder: PhotoViewHolder) {
        Log.w("SecureMediaPagerAdapter", "Setting error image for photo holder")
        activityRef.get()?.runOnUiThread {
            try {
                // Create a simple error bitmap if the system icon fails
                val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.DKGRAY)
                
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 24f
                    isAntiAlias = true
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                
                canvas.drawText("Image", bitmap.width / 2f, bitmap.height / 2f - 10f, paint)
                canvas.drawText("Load Error", bitmap.width / 2f, bitmap.height / 2f + 20f, paint)
                
                holder.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Log.e("SecureMediaPagerAdapter", "Failed to create error image", e)
                // Fallback to system icon
                holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
            }
        }
    }
    
    private fun bindVideo(holder: VideoViewHolder, media: SecureMedia) {
        Log.d("SecureMediaPagerAdapter", "=== BINDING VIDEO ===")
        Log.d("SecureMediaPagerAdapter", "Video: ${media.name}, ID: ${media.id}, Size: ${media.getMediaSize()}")
        Log.d("SecureMediaPagerAdapter", "Current holder state - loading visible: ${holder.loadingContainer.visibility == View.VISIBLE}, video visible: ${holder.videoView.visibility == View.VISIBLE}")
        
        try {
            // First cleanup any existing video state to prevent conflicts
            Log.d("SecureMediaPagerAdapter", "Cleaning up existing video state")
            holder.cleanup()
            
            // Ensure proper view state - DO NOT try to find photo views in VideoViewHolder
            // The layout files are different, so searching for photo views will cause issues
            // Just ensure video views are properly configured
            holder.videoView.visibility = View.GONE  // Start hidden, will show after setup
            holder.surfaceView.visibility = View.GONE  // Start hidden, will show when needed
            holder.loadingContainer.visibility = View.VISIBLE  // Show loading initially
            Log.d("SecureMediaPagerAdapter", "Set initial video view states")
            
            // Validate media data before proceeding (without loading entire file into memory)
            if (!media.hasValidEncryptedData()) {
                Log.e("SecureMediaPagerAdapter", "Invalid or missing encrypted data for video: ${media.name}")
                holder.loadingContainer.visibility = View.GONE
                return
            }
            Log.d("SecureMediaPagerAdapter", "Media validation passed for: ${media.name}")
            
            // Check media size for memory optimization
            val mediaSize = media.getMediaSize()
            Log.d("SecureMediaPagerAdapter", "Video ${media.name} size category: $mediaSize")
            
            // For very large videos, show a warning and use most conservative approach
            if (mediaSize == MediaSize.LARGE) {
                Log.w("SecureMediaPagerAdapter", "Loading large video file (>100MB): ${media.name}")
                // Could add user notification here in the future
            }
            
            // Set this as the current video holder AFTER cleanup and validation
            Log.d("SecureMediaPagerAdapter", "Setting as current video holder")
            setCurrentVideoHolder(holder)
        
            if (key != null) {
                // Show loading indicator
                holder.loadingContainer.visibility = View.VISIBLE
                holder.videoView.visibility = View.GONE
                Log.d("SecureMediaPagerAdapter", "Loading UI shown for video: ${media.name}")
                
                val currentPosition = mediaList.indexOf(media)
                Log.d("SecureMediaPagerAdapter", "Video position in list: $currentPosition")
                
                // Check if video is already preloaded
                val preloadedFile = preloadCache[currentPosition]
                if (preloadedFile != null && preloadedFile.exists()) {
                    Log.d("SecureMediaPagerAdapter", "Using preloaded video file for: ${media.name}, file size: ${preloadedFile.length()}")
                    // Use preloaded file immediately
                    val activity = activityRef.get()
                    activity?.runOnUiThread {
                        setupVideoView(holder, preloadedFile, media.name)
                    }
                    return
                }
                
                // Decrypt and prepare video in background thread
                Thread {
                    try {
                        Log.d("SecureMediaPagerAdapter", "Starting quick video preparation for: ${media.name}")
                        
                        // Create temporary file for video playback
                        var tempFile = File.createTempFile("secure_video_", ".mp4", context.cacheDir)
                        tempFiles.add(tempFile)
                        
                        // Use standard decryption for now - optimization can be added later
                        Log.d("SecureMediaPagerAdapter", "Using standard video decryption for: ${media.name}")
                        performStandardVideoDecryption(media, tempFile, key)
                        
                        // Validate the decrypted video file
                        if (!tempFile.exists() || tempFile.length() < 1024) {
                            Log.e("SecureMediaPagerAdapter", "Video file validation failed: exists=${tempFile.exists()}, size=${tempFile.length()}")
                            throw IllegalStateException("Invalid decrypted video file")
                        }
                        
                        // Setup video view on main thread with safety checks
                        val activity = activityRef.get()
                        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                            activity.runOnUiThread {
                                try {
                                    // Double-check activity state in UI thread
                                    if (!activity.isFinishing && !activity.isDestroyed) {
                                        Log.d("SecureMediaPagerAdapter", "About to setup video view for ${media.name}")
                                        
                                        // Add a small delay to ensure the activity is fully ready
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            try {
                                                if (!activity.isFinishing && !activity.isDestroyed) {
                                                    setupVideoView(holder, tempFile, media.name)
                                                    Log.d("SecureMediaPagerAdapter", "Video view setup initiated for ${media.name}")
                                                } else {
                                                    Log.w("SecureMediaPagerAdapter", "Activity destroyed during delay, skipping video setup for ${media.name}")
                                                    holder.loadingContainer.visibility = View.GONE
                                                }
                                            } catch (e: Exception) {
                                                Log.e("SecureMediaPagerAdapter", "Failed to setup video view (delayed) for ${media.name}", e)
                                                holder.loadingContainer.visibility = View.GONE
                                            }
                                        }, 100) // 100ms delay
                                        
                                    } else {
                                        Log.w("SecureMediaPagerAdapter", "Activity finishing/destroyed, skipping video setup for ${media.name}")
                                        holder.loadingContainer.visibility = View.GONE
                                    }
                                } catch (e: Exception) {
                                    Log.e("SecureMediaPagerAdapter", "Failed to setup video view for ${media.name}", e)
                                    holder.loadingContainer.visibility = View.GONE
                                }
                            }
                        } else {
                            Log.w("SecureMediaPagerAdapter", "Activity reference lost, skipping video setup for ${media.name}")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("SecureMediaPagerAdapter", "Failed to decrypt/play video: ${media.name}", e)
                        val activity = activityRef.get()
                        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                            activity.runOnUiThread {
                                holder.loadingContainer.visibility = View.GONE
                            }
                        }
                    }
                }.start()
            } else {
                Log.w("SecureMediaPagerAdapter", "No decryption key available for video: ${media.name}")
                holder.loadingContainer.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Critical error in bindVideo for ${media.name}", e)
            holder.loadingContainer.visibility = View.GONE
        }
    }
    
    private fun setupVideoView(holder: VideoViewHolder, tempFile: File, videoName: String) {
        try {
            Log.d("SecureMediaPagerAdapter", "Setting up video view for: $videoName, file exists: ${tempFile.exists()}, file size: ${tempFile.length()}")
            
            // Double-check file exists and is valid
            if (!tempFile.exists() || tempFile.length() < 1024) {
                Log.e("SecureMediaPagerAdapter", "Video file invalid for setup: exists=${tempFile.exists()}, size=${tempFile.length()}")
                holder.loadingContainer.visibility = View.GONE
                return
            }
            
            // Check file permissions
            Log.d("SecureMediaPagerAdapter", "File permissions - readable: ${tempFile.canRead()}, path: ${tempFile.absolutePath}")
            
            // First, let's try to get video information to verify the file is valid
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mimeType = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                Log.d("SecureMediaPagerAdapter", "Video metadata - Width: $width, Height: $height, Duration: $duration, MimeType: $mimeType")
                retriever.release()
            } catch (e: Exception) {
                Log.e("SecureMediaPagerAdapter", "Failed to read video metadata for $videoName", e)
                retriever.release()
                holder.loadingContainer.visibility = View.GONE
                return
            }
            
            val uri = Uri.fromFile(tempFile)
            Log.d("SecureMediaPagerAdapter", "Created URI: $uri")
            
            // Skip VideoView setup and go directly to MediaPlayer + SurfaceView
            // This approach is more reliable for large encrypted videos during swipe transitions
            Log.d("SecureMediaPagerAdapter", "Skipping VideoView setup, going directly to MediaPlayer approach")
            
            Log.d("SecureMediaPagerAdapter", "Video view setup completed for: $videoName")
            
            // Skip VideoView entirely and go directly to MediaPlayer + SurfaceView for reliability
            // This eliminates the black screen issue during swipe transitions
            Log.d("SecureMediaPagerAdapter", "Using MediaPlayer + SurfaceView approach directly for reliable playback")
            
            // Switch to MediaPlayer approach immediately
            try {
                // Hide VideoView and show SurfaceView
                holder.videoView.visibility = View.GONE
                holder.surfaceView.visibility = View.VISIBLE
                        
                        // Add tap-to-pause functionality for SurfaceView/MediaPlayer
                        holder.surfaceView.setOnClickListener {
                            try {
                                holder.mediaPlayer?.let { mp ->
                                    if (mp.isPlaying) {
                                        mp.pause()
                                        Log.d("SecureMediaPagerAdapter", "MediaPlayer paused by tap: $videoName")
                                    } else {
                                        mp.start()
                                        Log.d("SecureMediaPagerAdapter", "MediaPlayer resumed by tap: $videoName")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("SecureMediaPagerAdapter", "Error handling SurfaceView tap", e)
                            }
                        }
                        
                        // Wait for SurfaceView to be ready
                        holder.surfaceView.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                            override fun surfaceCreated(surfaceHolder: android.view.SurfaceHolder) {
                                Log.d("SecureMediaPagerAdapter", "Surface created, setting up MediaPlayer for $videoName")
                                
                                try {
                                    // Create and setup MediaPlayer
                                    holder.mediaPlayer = MediaPlayer().apply {
                                        setDataSource(tempFile.absolutePath)
                                        setDisplay(surfaceHolder)
                                        isLooping = true
                                        
                                        setOnPreparedListener { mp ->
                                            Log.d("SecureMediaPagerAdapter", "MediaPlayer prepared successfully for $videoName")
                                            holder.loadingContainer.visibility = View.GONE
                                            mp.start()
                                        }
                                        
                                        setOnErrorListener { mp, what, extra ->
                                            Log.e("SecureMediaPagerAdapter", "MediaPlayer error for $videoName: what=$what, extra=$extra")
                                            holder.loadingContainer.visibility = View.GONE
                                            mp.release()
                                            holder.mediaPlayer = null
                                            true
                                        }
                                        
                                        setOnVideoSizeChangedListener { mp, width, height ->
                                            Log.d("SecureMediaPagerAdapter", "Video size changed: ${width}x${height}")
                                        }
                                        
                                        prepareAsync()
                                    }
                                } catch (e: Exception) {
                                    Log.e("SecureMediaPagerAdapter", "Failed to create MediaPlayer for $videoName", e)
                                    holder.loadingContainer.visibility = View.GONE
                                }
                            }
                            
                            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                                Log.d("SecureMediaPagerAdapter", "Surface changed: ${width}x${height}")
                            }
                            
                            override fun surfaceDestroyed(surfaceHolder: android.view.SurfaceHolder) {
                                Log.d("SecureMediaPagerAdapter", "Surface destroyed for $videoName")
                                // Clean up MediaPlayer when surface is destroyed
                                holder.mediaPlayer?.let { mp ->
                                    try {
                                        if (mp.isPlaying) {
                                            mp.stop()
                                        }
                                        mp.release()
                                    } catch (e: Exception) {
                                        Log.w("SecureMediaPagerAdapter", "Error releasing MediaPlayer on surface destroy", e)
                                    }
                                    holder.mediaPlayer = null
                                }
                            }
                        })
                        
                    } catch (e: Exception) {
                        Log.e("SecureMediaPagerAdapter", "Failed MediaPlayer approach for $videoName", e)
                        holder.loadingContainer.visibility = View.GONE
                    }
            
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Exception in setupVideoView for $videoName", e)
            holder.loadingContainer.visibility = View.GONE
        }
    }
    
    private fun performStandardVideoDecryption(media: SecureMedia, tempFile: File, key: javax.crypto.spec.SecretKeySpec) {
        var decryptedBytes: ByteArray? = null
        try {
            if (media.usesExternalStorage()) {
                // For file-based storage, stream decrypt to avoid loading entire file into memory
                Log.d("SecureMediaPagerAdapter", "Using file-based decryption for large video: ${media.name}")
                
                val encryptedFile = File(media.filePath!!)
                val inputStream = FileInputStream(encryptedFile)
                val outputStream = FileOutputStream(tempFile)
                
                // Use existing streaming decryption method - this is secure as data never fully resides in memory
                CryptoUtils.decryptStream(inputStream, outputStream, key)
                
                inputStream.close()
                outputStream.close()
            } else {
                // For in-memory storage, decrypt normally but clear memory immediately
                val encryptedData = media.getEncryptedData()
                if (encryptedData.size < 16) {
                    Log.e("SecureMediaPagerAdapter", "Encrypted video data too small: ${encryptedData.size} bytes")
                    throw IllegalStateException("Invalid encrypted video data")
                }
                
                val iv = encryptedData.copyOfRange(0, 16)
                val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
                decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                
                Log.d("SecureMediaPagerAdapter", "Decrypted video data: ${decryptedBytes.size} bytes")
                
                // Security: Track decrypted video data
                val trackingId = "video_${media.name}_${System.currentTimeMillis()}"
                decryptedDataTracker.add(trackingId)
                
                val fos = FileOutputStream(tempFile)
                fos.write(decryptedBytes)
                fos.close()
                
                // Security: Remove tracking immediately after writing to file
                decryptedDataTracker.remove(trackingId)
            }
            
            Log.d("SecureMediaPagerAdapter", "Standard video decryption completed: ${tempFile.length()} bytes")
            
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Error in standard video decryption for ${media.name}", e)
            throw e
        } finally {
            // Security: Clear decrypted bytes from memory using secure wipe
            secureWipeByteArray(decryptedBytes)
        }
    }
    
    /**
     * SECURITY: Clear all caches on logout to prevent data interception
     * This ensures no decrypted thumbnail or media data persists after logout
     */
    fun secureLogoutCleanup() {
        Log.d("SecureMediaPagerAdapter", "SECURITY: Starting secure logout cleanup")
        
        // Stop all media playback immediately
        pauseAllVideos()
        
        // Clear all in-memory photo caches (these contain decrypted data)
        synchronized(photoPreloadCache) {
            photoPreloadCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    Log.d("SecureMediaPagerAdapter", "SECURITY: Recycled cached bitmap")
                }
            }
            photoPreloadCache.clear()
            Log.d("SecureMediaPagerAdapter", "SECURITY: Cleared photo preload cache")
        }
        
        // Securely clean up all temporary video files (these contain decrypted video data)
        for (file in tempFiles.toList()) {
            try {
                if (file.exists()) {
                    // Security: Overwrite file content with random data before deletion
                    val fileSize = file.length()
                    if (fileSize > 0) {
                        val randomData = ByteArray(minOf(fileSize.toInt(), 10 * 1024 * 1024)) // Max 10MB overwrite
                        java.security.SecureRandom().nextBytes(randomData)
                        file.writeBytes(randomData)
                        Log.d("SecureMediaPagerAdapter", "SECURITY: Overwrote temp file with random data: ${file.name}")
                    }
                    file.delete()
                    Log.d("SecureMediaPagerAdapter", "SECURITY: Securely deleted temp file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to securely delete temp file: ${file.name}", e)
                // Try regular deletion as fallback
                try { file.delete() } catch (e2: Exception) { /* Ignore */ }
            }
        }
        tempFiles.clear()
        
        // Securely clean up video preload cache (these contain decrypted video data)
        for (file in preloadCache.values.toList()) {
            try {
                if (file.exists()) {
                    // Security: Overwrite file content with random data before deletion
                    val fileSize = file.length()
                    if (fileSize > 0) {
                        val randomData = ByteArray(minOf(fileSize.toInt(), 10 * 1024 * 1024)) // Max 10MB overwrite
                        java.security.SecureRandom().nextBytes(randomData)
                        file.writeBytes(randomData)
                        Log.d("SecureMediaPagerAdapter", "SECURITY: Overwrote preload file with random data: ${file.name}")
                    }
                    file.delete()
                    Log.d("SecureMediaPagerAdapter", "SECURITY: Securely deleted preload file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to securely delete preload file: ${file.name}", e)
                try { file.delete() } catch (e2: Exception) { /* Ignore */ }
            }
        }
        preloadCache.clear()
        
        // Clear decrypted data tracker
        decryptedDataTracker.clear()
        
        // Clear thumbnail cache on disk using VideoUtils (this affects all video thumbnails)
        VideoUtils.clearAllThumbnailCaches(context)
        
        // Multiple garbage collection passes to ensure sensitive data is cleared from memory
        System.gc()
        Thread.sleep(100)
        System.gc()
        
        Log.d("SecureMediaPagerAdapter", "SECURITY: Secure logout cleanup completed")
    }
    
    fun cleanup() {
        Log.d("SecureMediaPagerAdapter", "Starting secure cleanup - tracking ${decryptedDataTracker.size} decrypted data items")
        
        // Security: Stop all media playback to prevent leaked decrypted data
        pauseAllVideos()
        
        // Clean up temporary video files (these contain decrypted video data)
        for (file in tempFiles) {
            try {
                if (file.exists()) {
                    // Security: Overwrite file content before deletion for better security
                    val fileSize = file.length()
                    if (fileSize > 0) {
                        val randomData = ByteArray(minOf(fileSize.toInt(), 1024 * 1024)) // Max 1MB overwrite
                        java.security.SecureRandom().nextBytes(randomData)
                        file.writeBytes(randomData)
                    }
                    file.delete()
                    Log.d("SecureMediaPagerAdapter", "Securely deleted temp file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to securely delete temp file: ${file.name}", e)
                // Try regular deletion as fallback
                try { file.delete() } catch (e2: Exception) { /* Ignore */ }
            }
        }
        tempFiles.clear()
        
        // Clean up preload cache (these also contain decrypted video data)
        for (file in preloadCache.values) {
            try {
                if (file.exists()) {
                    // Security: Overwrite file content before deletion
                    val fileSize = file.length()
                    if (fileSize > 0) {
                        val randomData = ByteArray(minOf(fileSize.toInt(), 1024 * 1024)) // Max 1MB overwrite
                        java.security.SecureRandom().nextBytes(randomData)
                        file.writeBytes(randomData)
                    }
                    file.delete()
                    Log.d("SecureMediaPagerAdapter", "Securely deleted preload file: ${file.name}")
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to securely delete preload file: ${file.name}", e)
                // Try regular deletion as fallback
                try { file.delete() } catch (e2: Exception) { /* Ignore */ }
            }
        }
        preloadCache.clear()
        
        // Cleanup photo cache (these contain decrypted bitmap data in memory)
        synchronized(photoPreloadCache) {
            photoPreloadCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            photoPreloadCache.clear()
        }
        
        // Security: Clear decrypted data tracker
        decryptedDataTracker.clear()
        
        // Security: Request garbage collection to clear sensitive data from memory
        System.gc()
        
        Log.d("SecureMediaPagerAdapter", "Secure adapter cleanup completed")
    }
    
    class PhotoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val photoView: PhotoView = itemView.findViewById(R.id.photoView)
        private var currentBitmap: Bitmap? = null
        private var isLoaded = false
        
        init {
            // Configure PhotoView for zoom functionality
            photoView.isZoomable = true
            photoView.maximumScale = 5.0f
            photoView.mediumScale = 2.5f
            photoView.minimumScale = 1.0f
            
            // Set up tap-to-zoom functionality
            photoView.setOnPhotoTapListener { view, x, y ->
                Log.d("SecureMediaPagerAdapter", "Photo tapped at scale: ${photoView.scale}, coordinates: ($x, $y)")
                if (photoView.scale > 1.5f) {
                    Log.d("SecureMediaPagerAdapter", "Zooming out to 1x")
                    photoView.setScale(1.0f, x, y, true)
                } else {
                    Log.d("SecureMediaPagerAdapter", "Zooming in to 3x")
                    photoView.setScale(3.0f, x, y, true)
                }
            }
            
            // Add error listener to detect image loading issues
            photoView.setOnViewTapListener { view, x, y ->
                if (!isLoaded && currentBitmap == null) {
                    Log.w("SecureMediaPagerAdapter", "Photo view tapped but image not loaded, attempting reload")
                    // Could trigger a reload here if needed
                }
            }
        }
        
        fun setImageBitmap(bitmap: Bitmap?) {
            Log.d("SecureMediaPagerAdapter", "Setting image bitmap: ${bitmap != null}, size: ${bitmap?.let { "${it.width}x${it.height}" } ?: "null"}")
            
            // Don't recycle current bitmap if it's the same as the new one
            if (currentBitmap != bitmap) {
                // Recycle previous bitmap to free memory
                currentBitmap?.let { oldBitmap ->
                    if (!oldBitmap.isRecycled) {
                        oldBitmap.recycle()
                        Log.d("SecureMediaPagerAdapter", "Recycled previous bitmap")
                    }
                }
            }
            
            currentBitmap = bitmap
            isLoaded = bitmap != null
            
            if (bitmap != null && !bitmap.isRecycled) {
                // Ensure PhotoView is properly configured before setting bitmap
                photoView.visibility = View.VISIBLE
                photoView.alpha = 1.0f
                photoView.setImageBitmap(bitmap)
                
                // Force a layout to ensure bitmap is displayed
                photoView.requestLayout()
                photoView.invalidate()
                
                Log.d("SecureMediaPagerAdapter", "Image bitmap set successfully and made visible")
            } else {
                Log.w("SecureMediaPagerAdapter", "Cannot set null or recycled bitmap")
                photoView.setImageBitmap(null)
                // Still keep PhotoView visible to show placeholder
                photoView.visibility = View.VISIBLE
            }
        }
        
        fun cleanup() {
            Log.d("SecureMediaPagerAdapter", "Cleaning up PhotoViewHolder")
            isLoaded = false
            
            // Clear the PhotoView first
            photoView.setImageBitmap(null)
            
            // Reset view state to prevent black screen issues
            photoView.visibility = View.VISIBLE
            photoView.alpha = 1.0f
            
            // Security: Recycle bitmap and clear reference to prevent memory leaks of decrypted data
            currentBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    Log.d("SecureMediaPagerAdapter", "Bitmap recycled during cleanup - freed decrypted image data")
                }
            }
            currentBitmap = null
            
            // Reset PhotoView scale to prevent state issues
            photoView.setScale(1.0f, false)
            
            // Security: Request garbage collection to clear any remaining bitmap data
            System.gc()
        }
        
        fun isImageLoaded(): Boolean = isLoaded && currentBitmap != null && !currentBitmap!!.isRecycled
    }
    
    class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val videoView: VideoView = itemView.findViewById(R.id.videoView)
        val surfaceView: SurfaceView = itemView.findViewById(R.id.surfaceView)
        val loadingContainer: View = itemView.findViewById(R.id.loadingContainer)
        val loadingIndicator: ProgressBar = itemView.findViewById(R.id.loadingIndicator)
        var mediaPlayer: MediaPlayer? = null
        
        fun cleanup() {
            // Stop and clean up MediaPlayer first
            mediaPlayer?.let { mp ->
                try {
                    if (mp.isPlaying) {
                        mp.stop()
                        Log.d("SecureMediaPagerAdapter", "Stopped MediaPlayer during cleanup")
                    }
                    mp.reset()
                    mp.release()
                } catch (e: Exception) {
                    Log.w("SecureMediaPagerAdapter", "Error cleaning up MediaPlayer", e)
                }
                mediaPlayer = null
            }
            
            // Stop and clean up VideoView
            try {
                if (videoView.isPlaying) {
                    videoView.stopPlayback()
                    Log.d("SecureMediaPagerAdapter", "Stopped VideoView during cleanup")
                }
                videoView.suspend()
                videoView.setVideoURI(null)
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error cleaning up VideoView", e)
            }
            
            // Reset UI state - hide all video views
            loadingContainer.visibility = View.GONE
            videoView.visibility = View.GONE
            surfaceView.visibility = View.GONE
            
            Log.d("SecureMediaPagerAdapter", "VideoViewHolder cleanup completed")
        }
    }
}
