package com.darkempire78.opencalculator.securegallery

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
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

class GalleryActivity : AppCompatActivity(), SensorEventListener {
    companion object {
        const val PHOTO_VIEWER_REQUEST = 1002
    }

    private var deleteDialog: android.app.AlertDialog? = null
    private var isDeleteMode = false
    private val selectedPhotosForDeletion = mutableSetOf<Int>()
    private var photosAdapter: RecyclerView.Adapter<PhotoThumbnailViewHolder>? = null
    
    // Note management
    private var isNoteDeleteMode = false
    private val selectedNotesForDeletion = mutableSetOf<Int>()
    private var notesAdapter: RecyclerView.Adapter<NoteViewHolder>? = null
    
    // Organize mode for drag-and-drop reordering
    private var isOrganizeMode = false
    private var organizePhotos = mutableListOf<android.graphics.Bitmap?>()
    
    // Security features
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var isActivityVisible = true
    private var screenOffReceiver: BroadcastReceiver? = null
    private var isPhotoPickerActive = false
    
    // Activity result launcher for photo viewer
    private val photoViewerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val returnPosition = result.data?.getIntExtra("return_position", -1) ?: -1
            if (returnPosition >= 0) {
                // Optionally scroll to the position in the gallery that was being viewed
                val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
                photosRecyclerView.scrollToPosition(returnPosition)
            }
        }
    }

    // Activity result launcher for adding multiple pictures
    private val addPicturesLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        isPhotoPickerActive = false // Reset the flag when picker returns
        if (uris.isNotEmpty()) {
            handleSelectedImages(uris)
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

    // Handler for Add Pictures menu item
    private fun addPicturesToGallery() {
        isPhotoPickerActive = true
        addPicturesLauncher.launch("image/*")
    }

    private fun handleSelectedImages(uris: List<android.net.Uri>) {
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        val encryptedPhotos = mutableListOf<SecurePhoto>()
        val deletableUris = mutableListOf<android.net.Uri>()
        
        for (uri in uris) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: continue
                inputStream.close()
                if (key != null) {
                    val (iv, encrypted) = CryptoUtils.encrypt(bytes, key)
                    val combined = iv + encrypted
                    encryptedPhotos.add(SecurePhoto(encryptedData = combined, name = "photo_${System.currentTimeMillis()}.jpg", date = System.currentTimeMillis()))
                    
                    // Only add URIs that can actually be deleted (not photo picker temporary URIs)
                    val scheme = uri.scheme
                    val authority = uri.authority
                    if (scheme == "content" && authority != "com.android.providers.media.photopicker" && !uri.toString().contains("picker_get_content")) {
                        deletableUris.add(uri)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("SecureGallery", "Failed to encrypt photo: $uri", e)
            }
        }
        
        // Add encrypted photos to gallery
        gallery.photos.addAll(encryptedPhotos)
        // Save gallery data (this will persist the added photos)
        GalleryManager.setContext(this)
        GalleryManager.saveGalleries()
        
        // Only prompt to delete originals if we have deletable URIs
        if (deletableUris.isNotEmpty()) {
            deleteDialog = android.app.AlertDialog.Builder(this)
                .setTitle("Delete Original Photos?")
                .setMessage("Do you want to delete the original photos from your device? (${deletableUris.size} of ${uris.size} photos can be deleted)")
                .setPositiveButton("Delete") { _, _ ->
                    var deletedCount = 0
                    var failedCount = 0
                    for (uri in deletableUris) {
                        try {
                            contentResolver.delete(uri, null, null)
                            deletedCount++
                        } catch (e: Exception) {
                            android.util.Log.e("SecureGallery", "Failed to delete original photo: $uri", e)
                            failedCount++
                        }
                    }
                    val message = if (failedCount > 0) {
                        "Deleted $deletedCount photos, failed to delete $failedCount"
                    } else {
                        "Original photos deleted ($deletedCount)"
                    }
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    deleteDialog = null
                    // Refresh gallery UI (reload photos)
                    recreate()
                }
                .setNegativeButton("Keep") { _, _ ->
                    deleteDialog = null
                    // Refresh gallery UI (reload photos)
                    recreate()
                }
                .create()
            deleteDialog?.show()
        } else {
            // No deletable photos - show info message if photos came from photo picker
            val hasPickerUris = uris.any { uri -> 
                uri.authority == "com.android.providers.media.photopicker" || uri.toString().contains("picker_get_content")
            }
            if (hasPickerUris) {
                Toast.makeText(this, "Photos imported successfully. Original photos remain in your gallery.", Toast.LENGTH_LONG).show()
            }
            // Refresh gallery UI (reload photos)
            recreate()
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
        recreate()
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
                recreate() // Refresh the activity to show updated notes
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
        recreate()
    }

    override fun onDestroy() {
        deleteDialog?.dismiss()
        deleteDialog = null
        cleanupSecurity()
        super.onDestroy()
    }
    
    override fun onPause() {
        super.onPause()
        isActivityVisible = false
        // Security feature: close gallery when app loses focus
        // BUT don't close if photo picker is active
        if (!isPhotoPickerActive) {
            closeGalleryForSecurity()
        }
    }
    
    override fun onResume() {
        super.onResume()
        isActivityVisible = true
        // Don't reset security trigger here - only reset on successful gallery entry
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
                    closeGalleryForSecurity()
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
        if (!TempPinHolder.securityTriggered) {
            TempPinHolder.securityTriggered = true
            finish() // Close gallery and return to calculator
        }
    }
    
    // SensorEventListener implementation for accelerometer
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            
            // Check if phone is face down (Z-axis negative with significant magnitude)
            // Threshold of -8.0 for face down detection (gravity is ~9.8, allowing for some tolerance)
            if (z < -8.0 && Math.abs(x) < 3.0 && Math.abs(y) < 3.0) {
                closeGalleryForSecurity()
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for this implementation
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        // Initialize GalleryManager context
        GalleryManager.setContext(this)
        
        // Initialize security features
        initializeSecurity()
        
        // Clear security trigger since we successfully entered the gallery
        TempPinHolder.clearSecurityTrigger()

        val galleryName = intent.getStringExtra("gallery_name") ?: "Gallery"
        
        // Get gallery data from GalleryManager instead of Intent
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName }
        val notes = gallery?.notes ?: mutableListOf()
        val photos = gallery?.photos ?: mutableListOf()

        // Apply sort order to photos
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

        // Setup photos RecyclerView with two-column grid
        val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
        photosRecyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)
        
        // Decrypt and display photos as thumbnails
        val decryptedPhotos = photos.mapNotNull { photo ->
            if (key != null) {
                // Try main decryption
                try {
                    val iv = photo.encryptedData.copyOfRange(0, 16)
                    val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                    val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
                    android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Primary decryption failed for photo: ${photo.name}", e)
                    
                    // Try legacy decryption with default salt
                    try {
                        val legacySalt = ByteArray(16) // Empty salt for legacy photos
                        val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                        val iv = photo.encryptedData.copyOfRange(0, 16)
                        val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ct, legacyKey)
                        android.util.Log.d("SecureGallery", "Legacy decryption succeeded for photo: ${photo.name}")
                        android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                    } catch (legacyE: Exception) {
                        android.util.Log.e("SecureGallery", "Both primary and legacy decryption failed for photo: ${photo.name}", legacyE)
                        null
                    }
                }
            } else {
                null
            }
        }

        photosAdapter = object : RecyclerView.Adapter<PhotoThumbnailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoThumbnailViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_thumbnail, parent, false)
                return PhotoThumbnailViewHolder(v)
            }
            override fun getItemCount() = if (isOrganizeMode) organizePhotos.size else decryptedPhotos.size
            override fun onBindViewHolder(holder: PhotoThumbnailViewHolder, position: Int) {
                // Use organize photos if in organize mode, otherwise use decrypted photos
                val bitmap = if (isOrganizeMode && position < organizePhotos.size) {
                    organizePhotos[position]
                } else if (position < decryptedPhotos.size) {
                    decryptedPhotos[position]
                } else {
                    null
                }
                
                holder.bind(bitmap, isDeleteMode, selectedPhotosForDeletion.contains(position), isOrganizeMode)
                
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
                        
                        // Auto-cancel delete mode if no photos are selected
                        if (selectedPhotosForDeletion.isEmpty()) {
                            exitDeleteMode()
                        }
                    }
                    
                    // No long press needed in delete mode
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode - click to view photo
                    holder.itemView.setOnClickListener {
                        val intent = android.content.Intent(this@GalleryActivity, SecurePhotoViewerActivity::class.java)
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
            Pair("Add Pictures", R.id.action_add_pictures),
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
                    R.id.action_add_pictures -> addPicturesToGallery()
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
    
    class PhotoThumbnailViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        fun bind(bitmap: android.graphics.Bitmap?, isDeleteMode: Boolean = false, isSelected: Boolean = false, isOrganizeMode: Boolean = false) {
            val imageView = itemView.findViewById<android.widget.ImageView>(R.id.photoThumbnail)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                imageView.setImageResource(android.R.drawable.ic_menu_gallery)
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
        recreate() // Refresh to show new order
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
        android.util.Log.d("SecureGallery", "Gallery details: id=${gallery.id}, photoCount=${gallery.photos.size}, hasHash=${gallery.pinHash != null}")
        
        if (key == null) {
            android.util.Log.e("SecureGallery", "Cannot derive key: pin='$pin', salt is null=${salt == null}")
            Toast.makeText(this, "Cannot organize photos: decryption key unavailable", Toast.LENGTH_LONG).show()
            return
        }
        
        organizePhotos.clear()
        organizePhotos.addAll(gallery.photos.mapIndexed { index, photo ->
            if (key != null) {
                // Try main decryption
                try {
                    android.util.Log.d("SecureGallery", "Decrypting photo $index (${photo.name}): data size=${photo.encryptedData.size}")
                    val iv = photo.encryptedData.copyOfRange(0, 16)
                    val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                    android.util.Log.d("SecureGallery", "Photo $index: IV size=${iv.size}, CT size=${ct.size}")
                    val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
                    android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Primary decryption failed for photo ${photo.name} (index $index)", e)
                    
                    // Try legacy decryption with default salt
                    try {
                        android.util.Log.d("SecureGallery", "Attempting legacy decryption for photo $index")
                        val legacySalt = ByteArray(16) // Empty salt for legacy photos
                        val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                        val iv = photo.encryptedData.copyOfRange(0, 16)
                        val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ct, legacyKey)
                        android.util.Log.d("SecureGallery", "Legacy decryption succeeded for photo $index")
                        android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                    } catch (legacyE: Exception) {
                        android.util.Log.e("SecureGallery", "Both primary and legacy decryption failed for photo ${photo.name} (index $index)", legacyE)
                        null
                    }
                }
            } else {
                android.util.Log.w("SecureGallery", "No key available for photo decryption in organize mode")
                null
            }
        })
        
        android.util.Log.d("SecureGallery", "Organize mode: ${organizePhotos.size} photos prepared (${organizePhotos.count { it != null }} non-null)")
        
        // Change action bar title
        supportActionBar?.title = "Organize Photos - Hold & Drag to Reorder"
        
        // Show organize mode instructions
        Toast.makeText(this, "Hold and drag photos to reorder. Tap 'Done' when finished.", Toast.LENGTH_LONG).show()
        
        // Add organize mode toolbar with Done button
        showOrganizeModeToolbar()
        
        // Refresh adapter to enable drag functionality
        recreatePhotosAdapter()
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
        recreatePhotosAdapter()
        
        Toast.makeText(this, "Photo order saved", Toast.LENGTH_SHORT).show()
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
        
        // Update gallery sort order to custom and save current photo order
        gallery.sortOrder = GallerySortOrder.CUSTOM
        gallery.customOrder = (0 until gallery.photos.size).toMutableList()
        
        GalleryManager.saveGalleries()
    }
    
    private fun recreatePhotosAdapter() {
        // Recreate the photos adapter to handle organize mode properly
        val photosRecyclerView = findViewById<RecyclerView>(R.id.photosRecyclerView)
        
        // Get current decrypted photos if not in organize mode
        val galleryName = intent.getStringExtra("gallery_name") ?: return
        val gallery = GalleryManager.getGalleries().find { it.name == galleryName } ?: return
        val pin = TempPinHolder.pin ?: ""
        val salt = gallery.salt
        val key = if (pin.isNotEmpty() && salt != null) CryptoUtils.deriveKey(pin, salt) else null
        
        val decryptedPhotos = gallery.photos.mapNotNull { photo ->
            if (key != null) {
                // Try main decryption
                try {
                    val iv = photo.encryptedData.copyOfRange(0, 16)
                    val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                    val decryptedBytes = CryptoUtils.decrypt(iv, ct, key)
                    android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                } catch (e: Exception) {
                    android.util.Log.e("SecureGallery", "Primary decryption failed for photo: ${photo.name}", e)
                    
                    // Try legacy decryption with default salt
                    try {
                        val legacySalt = ByteArray(16) // Empty salt for legacy photos
                        val legacyKey = CryptoUtils.deriveKey(pin, legacySalt)
                        val iv = photo.encryptedData.copyOfRange(0, 16)
                        val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
                        val decryptedBytes = CryptoUtils.decrypt(iv, ct, legacyKey)
                        android.util.Log.d("SecureGallery", "Legacy decryption succeeded for photo: ${photo.name}")
                        android.graphics.BitmapFactory.decodeByteArray(decryptedBytes, 0, decryptedBytes.size)
                    } catch (legacyE: Exception) {
                        android.util.Log.e("SecureGallery", "Both primary and legacy decryption failed for photo: ${photo.name}", legacyE)
                        null
                    }
                }
            } else {
                null
            }
        }
        
        photosAdapter = object : RecyclerView.Adapter<PhotoThumbnailViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoThumbnailViewHolder {
                val v = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_thumbnail, parent, false)
                return PhotoThumbnailViewHolder(v)
            }
            override fun getItemCount() = if (isOrganizeMode) organizePhotos.size else decryptedPhotos.size
            override fun onBindViewHolder(holder: PhotoThumbnailViewHolder, position: Int) {
                // Use organize photos if in organize mode, otherwise use decrypted photos
                val bitmap = if (isOrganizeMode && position < organizePhotos.size) {
                    organizePhotos[position]
                } else if (position < decryptedPhotos.size) {
                    decryptedPhotos[position]
                } else {
                    null
                }
                
                holder.bind(bitmap, isDeleteMode, selectedPhotosForDeletion.contains(position), isOrganizeMode)
                
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
                        
                        // Auto-cancel delete mode if no photos are selected
                        if (selectedPhotosForDeletion.isEmpty()) {
                            exitDeleteMode()
                        }
                    }
                    
                    // No long press needed in delete mode
                    holder.itemView.setOnLongClickListener(null)
                } else {
                    // Normal mode - click to view photo
                    holder.itemView.setOnClickListener {
                        val intent = android.content.Intent(this@GalleryActivity, SecurePhotoViewerActivity::class.java)
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
                
                if (fromPosition < gallery.photos.size && toPosition < gallery.photos.size) {
                    val item = gallery.photos.removeAt(fromPosition)
                    gallery.photos.add(toPosition, item)
                    
                    // Also move the decrypted bitmap
                    val bitmap = organizePhotos.removeAt(fromPosition)
                    organizePhotos.add(toPosition, bitmap)
                    
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
                    val iv = photo.encryptedData.copyOfRange(0, 16)
                    val ct = photo.encryptedData.copyOfRange(16, photo.encryptedData.size)
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
        
        try {
            contentResolver.openOutputStream(fileUri)?.use { outputStream ->
                // Create a serializable export format
                val exportData = GalleryExportData(
                    name = gallery.name,
                    salt = gallery.salt,
                    pinHash = gallery.pinHash,
                    photos = gallery.photos,
                    notes = gallery.notes,
                    sortOrder = gallery.sortOrder,
                    customOrder = gallery.customOrder,
                    exportVersion = 1,
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
            
            // Add photos and notes
            newGallery.photos.addAll(importData.photos)
            newGallery.notes.addAll(importData.notes)
            
            // Add to gallery manager
            GalleryManager.addGallery(newGallery)
            GalleryManager.saveGalleries()
            
            Toast.makeText(this, "Gallery '${galleryName}' imported successfully with ${importData.photos.size} photos and ${importData.notes.size} notes", Toast.LENGTH_LONG).show()
            
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
    val notes: List<SecureNote>,
    val sortOrder: GallerySortOrder,
    val customOrder: List<Int>,
    val exportVersion: Int,
    val exportDate: Long
) : java.io.Serializable
