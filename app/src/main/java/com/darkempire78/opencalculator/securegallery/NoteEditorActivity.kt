package com.darkempire78.opencalculator.securegallery

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.darkempire78.opencalculator.R

class NoteEditorActivity : AppCompatActivity() {
    
    private lateinit var noteTitleEdit: EditText
    private lateinit var noteBodyEdit: EditText
    private var noteIndex: Int = -1
    private var isNewNote: Boolean = true
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_note_editor)
        
        noteTitleEdit = findViewById(R.id.noteTitleEdit)
        noteBodyEdit = findViewById(R.id.noteBodyEdit)
        
        // Get data from intent
        val galleryName = intent.getStringExtra("gallery_name") ?: ""
        noteIndex = intent.getIntExtra("note_index", -1)
        isNewNote = noteIndex == -1
        
        // If editing existing note, populate fields
        if (!isNewNote) {
            val title = intent.getStringExtra("note_title") ?: ""
            val body = intent.getStringExtra("note_body") ?: ""
            noteTitleEdit.setText(title)
            noteBodyEdit.setText(body)
        }
        
        // Setup save button
        findViewById<Button>(R.id.saveNoteButton).setOnClickListener {
            saveNote()
        }
        
        // Setup cancel button
        findViewById<Button>(R.id.cancelNoteButton).setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }
    
    private fun saveNote() {
        val title = noteTitleEdit.text.toString().trim()
        val body = noteBodyEdit.text.toString().trim()
        
        android.util.Log.d("SecureGallery", "NoteEditorActivity saveNote() called - title: '$title', body: '$body'")
        
        if (title.isEmpty() && body.isEmpty()) {
            android.util.Log.d("SecureGallery", "Note is empty, showing toast and returning")
            Toast.makeText(this, "Please enter a title or content", Toast.LENGTH_SHORT).show()
            return
        }
        
        val resultIntent = Intent().apply {
            putExtra("note_title", title)
            putExtra("note_body", body)
            putExtra("note_index", noteIndex)
            putExtra("is_new_note", isNewNote)
        }
        
        android.util.Log.d("SecureGallery", "Setting result OK and finishing activity")
        setResult(Activity.RESULT_OK, resultIntent)
        finish()
    }
}
