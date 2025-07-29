package com.darkempire78.opencalculator.securegallery

import android.app.Activity
import android.content.Context
impor    private fun setCurrentVideoHolder(holder: VideoViewHolder) {
        // Stop previous video completely before switching
        currentVideoHolder?.let { prevHolder ->
            try {
                prevHolder.mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.stop()
                        Log.d("SecureMediaPagerAdapter", "Stopped previous video completely on switch")
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
        currentVideoHolder = holder
        Log.d("SecureMediaPagerAdapter", "Set new current video holder")
    }Bitmap
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
    
    private val tempFiles = mutableListOf<File>()
    private val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
    private val activityRef = WeakReference(context as? Activity)
    
    // Preloading and memory management
    private val preloadCache = mutableMapOf<Int, File>() // Cache prepared video files
    private val maxPreloadItems = 3 // Limit preloaded items
    private var lastPreloadPosition = -1
    
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
                holder.mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.pause()
                        Log.d("SecureMediaPagerAdapter", "Paused video due to focus loss")
                    }
                }
                // Also pause VideoView if it's being used
                if (holder.videoView.isPlaying) {
                    holder.videoView.pause()
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error pausing video", e)
            }
        }
    }
    
    fun resumeCurrentVideo() {
        currentVideoHolder?.let { holder ->
            try {
                holder.mediaPlayer?.let { mp ->
                    if (!mp.isPlaying) {
                        mp.start()
                        Log.d("SecureMediaPagerAdapter", "Resumed video after focus gain")
                    }
                }
                // Also resume VideoView if it's being used
                if (!holder.videoView.isPlaying && holder.videoView.canPause()) {
                    holder.videoView.start()
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error resuming video", e)
            }
        }
    }
    
    fun setCurrentVideoHolder(holder: VideoViewHolder?) {
        // Pause previous video when switching
        currentVideoHolder?.let { prevHolder ->
            try {
                prevHolder.mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        mp.pause()
                        Log.d("SecureMediaPagerAdapter", "Paused previous video on scroll")
                    }
                }
                if (prevHolder.videoView.isPlaying) {
                    prevHolder.videoView.pause()
                }
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Error pausing previous video", e)
            }
        }
        currentVideoHolder = holder
    }
    
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val media = mediaList[position]
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
    
    private val photoPreloadCache = mutableMapOf<String, Bitmap>()
    private val maxPhotoCache = 5
    
    private fun preloadPhotoIfNeeded(media: SecureMedia) {
        if (key == null) return
        
        val cacheKey = media.name + "_" + media.id
        if (photoPreloadCache.containsKey(cacheKey)) return
        
        try {
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
        if (currentPosition == lastPreloadPosition) return
        lastPreloadPosition = currentPosition
        
        Thread {
            try {
                // Preload next and previous videos
                val positionsToPreload = listOf(currentPosition - 1, currentPosition + 1)
                    .filter { it >= 0 && it < mediaList.size }
                    .filter { mediaList[it].mediaType == MediaType.VIDEO }
                
                for (pos in positionsToPreload) {
                    if (!preloadCache.containsKey(pos) && preloadCache.size < maxPreloadItems) {
                        val videoMedia = mediaList[pos]
                        preloadVideoFile(videoMedia, pos)
                    }
                }
                
                // Clean up distant preloaded items
                val distantKeys = preloadCache.keys.filter { 
                    kotlin.math.abs(it - currentPosition) > 2 
                }.toList()
                for (key in distantKeys) {
                    preloadCache[key]?.delete()
                    preloadCache.remove(key)
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
        
        // Clear previous image to prevent displaying wrong content
        holder.cleanup()
        
        // First check if we have a preloaded bitmap
        val cacheKey = media.name + "_" + media.id
        synchronized(photoPreloadCache) {
            photoPreloadCache[cacheKey]?.let { cachedBitmap ->
                Log.d("SecureMediaPagerAdapter", "Using preloaded photo: ${media.name}")
                holder.setImageBitmap(cachedBitmap)
                return
            }
        }
        
        if (key != null) {
            // Load photo in background to prevent UI blocking
            Thread {
                try {
                    val encryptedData = media.getEncryptedData()
                    if (encryptedData.size < 16) {
                        Log.e("SecureMediaPagerAdapter", "Encrypted data too small for photo: ${media.name}")
                        setErrorImage(holder)
                        return@Thread
                    }
                    
                    val iv = encryptedData.copyOfRange(0, 16)
                    val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
                    
                    var decryptedBytes: ByteArray? = null
                    
                    // Try normal decryption first
                    try {
                        decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                    } catch (e: Exception) {
                        Log.e("SecureMediaPagerAdapter", "Decryption failed for ${media.name}", e)
                        setErrorImage(holder)
                        return@Thread
                    }
                    
                    if (decryptedBytes == null) {
                        setErrorImage(holder)
                        return@Thread
                    }
                    
                    var bitmap: Bitmap? = null
                    
                    try {
                        // Create bitmap with proper options to prevent OutOfMemoryError
                        val options = BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, options)
                        
                        // Calculate inSampleSize to reduce memory usage for large images
                        val sampleSize = calculateInSampleSize(options, 2048, 2048)
                        
                        val decodeOptions = BitmapFactory.Options().apply {
                            inSampleSize = sampleSize
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        
                        bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, decodeOptions)
                        
                    } catch (oom: OutOfMemoryError) {
                        Log.e("SecureMediaPagerAdapter", "Out of memory loading photo: ${media.name}", oom)
                        System.gc() // Force garbage collection
                        
                        // Try with more aggressive compression
                        try {
                            val aggressiveOptions = BitmapFactory.Options().apply {
                                inSampleSize = 4
                                inPreferredConfig = Bitmap.Config.RGB_565
                            }
                            bitmap = BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size, aggressiveOptions)
                        } catch (e: Exception) {
                            Log.e("SecureMediaPagerAdapter", "Failed to decode with aggressive options", e)
                        }
                    } catch (e: Exception) {
                        Log.e("SecureMediaPagerAdapter", "Error decoding photo: ${media.name}", e)
                    }
                    
                    // Update UI on main thread
                    activityRef.get()?.runOnUiThread {
                        if (bitmap != null && !bitmap.isRecycled) {
                            Log.d("SecureMediaPagerAdapter", "Successfully loaded bitmap for photo: ${media.name}, size: ${bitmap.width}x${bitmap.height}")
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
                    }
                    
                } catch (e: Exception) {
                    Log.e("SecureMediaPagerAdapter", "Failed to decrypt photo: ${media.name}", e)
                    setErrorImage(holder)
                }
            }.start()
        } else {
            Log.w("SecureMediaPagerAdapter", "No decryption key available for photo: ${media.name}")
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
            
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    private fun setErrorImage(holder: PhotoViewHolder) {
        holder.photoView.setImageResource(android.R.drawable.ic_menu_gallery)
    }
    
    private fun bindVideo(holder: VideoViewHolder, media: SecureMedia) {
        Log.d("SecureMediaPagerAdapter", "Loading video: ${media.name}")
        
        // Set this as the current video holder
        setCurrentVideoHolder(holder)
        
        if (key != null) {
            // Show loading indicator
            holder.loadingContainer.visibility = View.VISIBLE
            holder.videoView.visibility = View.GONE
            
            val currentPosition = mediaList.indexOf(media)
            
            // Check if video is already preloaded
            val preloadedFile = preloadCache[currentPosition]
            if (preloadedFile != null && preloadedFile.exists()) {
                Log.d("SecureMediaPagerAdapter", "Using preloaded video file for: ${media.name}")
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
                    Log.d("SecureMediaPagerAdapter", "Starting video decryption in background for: ${media.name}")
                    
                    // Create temporary file for video playback
                    var tempFile = File.createTempFile("secure_video_", ".mp4", context.cacheDir)
                    tempFiles.add(tempFile)
                    
                    if (media.usesExternalStorage()) {
                        // For file-based storage, decrypt file using streaming
                        val encryptedFile = File(media.filePath!!)
                        val inputStream = FileInputStream(encryptedFile)
                        val outputStream = FileOutputStream(tempFile)
                        
                        Log.d("SecureMediaPagerAdapter", "Decrypting video file: ${encryptedFile.length()} bytes")
                        
                        // Read IV from file
                        val iv = ByteArray(16)
                        inputStream.read(iv)
                        
                        // Decrypt the rest using streaming
                        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.IvParameterSpec(iv))
                        
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            val decrypted = cipher.update(buffer, 0, bytesRead)
                            if (decrypted != null) {
                                outputStream.write(decrypted)
                            }
                        }
                        
                        val finalData = cipher.doFinal()
                        if (finalData.isNotEmpty()) {
                            outputStream.write(finalData)
                        }
                        
                        inputStream.close()
                        outputStream.close()
                    } else {
                        // For in-memory storage, decrypt normally
                        val encryptedData = media.getEncryptedData()
                        val iv = encryptedData.copyOfRange(0, 16)
                        val ciphertext = encryptedData.copyOfRange(16, encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ciphertext, key)
                        
                        val fos = FileOutputStream(tempFile)
                        fos.write(decryptedBytes)
                        fos.close()
                    }
                    
                    Log.d("SecureMediaPagerAdapter", "Video decryption completed: ${tempFile.length()} bytes")
                    
                    // Force garbage collection to free up memory before video setup
                    System.gc()
                    
                    // Small delay to allow GC to complete
                    Thread.sleep(100)
                    
                    // Log memory usage
                    val runtime = Runtime.getRuntime()
                    val totalMemory = runtime.totalMemory()
                    val freeMemory = runtime.freeMemory()
                    val usedMemory = totalMemory - freeMemory
                    Log.d("SecureMediaPagerAdapter", "Memory after decryption - Used: ${usedMemory / 1024 / 1024}MB, Free: ${freeMemory / 1024 / 1024}MB, Total: ${totalMemory / 1024 / 1024}MB")
                    
                    // Copy the file to a more accessible location in the external files directory
                    val publicVideoDir = File(context.getExternalFilesDir(null), "temp_videos")
                    if (!publicVideoDir.exists()) {
                        publicVideoDir.mkdirs()
                    }
                    
                    val publicVideoFile = File(publicVideoDir, "playback_${System.currentTimeMillis()}.mp4")
                    try {
                        tempFile.copyTo(publicVideoFile, overwrite = true)
                        Log.d("SecureMediaPagerAdapter", "Video copied to public location: ${publicVideoFile.absolutePath}")
                        
                        // Use the public file for playback
                        tempFile = publicVideoFile
                    } catch (e: Exception) {
                        Log.w("SecureMediaPagerAdapter", "Failed to copy video to public location, using original", e)
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
    }
    
    private fun setupVideoView(holder: VideoViewHolder, tempFile: File, videoName: String) {
        try {
            Log.d("SecureMediaPagerAdapter", "Setting up video view for: $videoName, file exists: ${tempFile.exists()}, file size: ${tempFile.length()}")
            
            // Check file permissions
            Log.d("SecureMediaPagerAdapter", "File permissions - readable: ${tempFile.canRead()}, path: ${tempFile.absolutePath}")
            
            // First, let's try to get video information to verify the file is valid
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
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
            
            // Set video URI first
            holder.videoView.setVideoURI(uri)
            Log.d("SecureMediaPagerAdapter", "Video URI set successfully")
            
            // Set up video listeners
            holder.videoView.setOnPreparedListener { mediaPlayer ->
                Log.d("SecureMediaPagerAdapter", "Video prepared: $videoName")
                
                try {
                    // Hide loading indicator and show video
                    holder.loadingContainer.visibility = View.GONE
                    holder.videoView.visibility = View.VISIBLE
                    
                    // Set video to loop
                    mediaPlayer.isLooping = true
                    
                    // Start playing automatically
                    holder.videoView.start()
                    Log.d("SecureMediaPagerAdapter", "Video started playing: $videoName")
                } catch (e: Exception) {
                    Log.e("SecureMediaPagerAdapter", "Error in onPrepared for $videoName", e)
                }
            }
            
            // Add tap-to-pause functionality for VideoView
            holder.videoView.setOnClickListener {
                try {
                    if (holder.videoView.isPlaying) {
                        holder.videoView.pause()
                        Log.d("SecureMediaPagerAdapter", "Video paused by tap: $videoName")
                    } else {
                        holder.videoView.start()
                        Log.d("SecureMediaPagerAdapter", "Video resumed by tap: $videoName")
                    }
                } catch (e: Exception) {
                    Log.w("SecureMediaPagerAdapter", "Error handling VideoView tap", e)
                }
            }
            
            holder.videoView.setOnErrorListener { mediaPlayer, what, extra ->
                Log.e("SecureMediaPagerAdapter", "Video error for $videoName: what=$what, extra=$extra")
                holder.loadingContainer.visibility = View.GONE
                
                // Try to provide more specific error information
                val errorMsg = when (what) {
                    android.media.MediaPlayer.MEDIA_ERROR_IO -> "IO Error"
                    android.media.MediaPlayer.MEDIA_ERROR_MALFORMED -> "Malformed media"
                    android.media.MediaPlayer.MEDIA_ERROR_UNSUPPORTED -> "Unsupported media"
                    android.media.MediaPlayer.MEDIA_ERROR_TIMED_OUT -> "Timed out"
                    android.media.MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Server died"
                    android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Unknown error"
                    else -> "Error code $what"
                }
                Log.e("SecureMediaPagerAdapter", "Video error details: $errorMsg, extra: $extra")
                true
            }
            
            holder.videoView.setOnInfoListener { mediaPlayer, what, extra ->
                val infoMsg = when (what) {
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> "Buffering started"
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> "Buffering ended"
                    android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> "Video rendering started"
                    else -> "Info: what=$what, extra=$extra"
                }
                Log.d("SecureMediaPagerAdapter", "Video info for $videoName: $infoMsg")
                false
            }
            
            holder.videoView.setOnCompletionListener { mediaPlayer ->
                // This shouldn't be called since we set looping to true
                Log.d("SecureMediaPagerAdapter", "Video completed: $videoName")
            }
            
            Log.d("SecureMediaPagerAdapter", "Video view setup completed for: $videoName")
            
            // Add a timeout to detect if VideoView never calls onPrepared
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (holder.loadingContainer.visibility == View.VISIBLE) {
                    Log.w("SecureMediaPagerAdapter", "Video preparation timeout for $videoName - onPrepared never called")
                    Log.w("SecureMediaPagerAdapter", "Switching to MediaPlayer with SurfaceView...")
                    
                    // Switch to MediaPlayer approach
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
                }
            }, 3000) // 3 second timeout
            
        } catch (e: Exception) {
            Log.e("SecureMediaPagerAdapter", "Exception in setupVideoView for $videoName", e)
            holder.loadingContainer.visibility = View.GONE
        }
    }
    
    fun cleanup() {
        // Clean up temporary video files
        for (file in tempFiles) {
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to delete temp file: ${file.name}", e)
            }
        }
        tempFiles.clear()
        
        // Clean up preload cache
        for (file in preloadCache.values) {
            try {
                file.delete()
            } catch (e: Exception) {
                Log.w("SecureMediaPagerAdapter", "Failed to delete preload file: ${file.name}", e)
            }
        }
        preloadCache.clear()
        
        // Cleanup photo cache
        synchronized(photoPreloadCache) {
            photoPreloadCache.values.forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            }
            photoPreloadCache.clear()
        }
        
        Log.d("SecureMediaPagerAdapter", "Full adapter cleanup completed")
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
                photoView.setImageBitmap(bitmap)
                Log.d("SecureMediaPagerAdapter", "Image bitmap set successfully")
            } else {
                Log.w("SecureMediaPagerAdapter", "Cannot set null or recycled bitmap")
                photoView.setImageBitmap(null)
            }
        }
        
        fun cleanup() {
            Log.d("SecureMediaPagerAdapter", "Cleaning up PhotoViewHolder")
            isLoaded = false
            
            // Clear the PhotoView first
            photoView.setImageBitmap(null)
            
            // Then recycle the bitmap
            currentBitmap?.let { bitmap ->
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                    Log.d("SecureMediaPagerAdapter", "Bitmap recycled during cleanup")
                }
            }
            currentBitmap = null
            
            // Reset PhotoView scale to prevent state issues
            photoView.setScale(1.0f, false)
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
            
            // Reset UI state
            loadingContainer.visibility = View.GONE
            videoView.visibility = View.GONE
            
            Log.d("SecureMediaPagerAdapter", "VideoViewHolder cleanup completed")
        }
    }
}
