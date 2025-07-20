package com.darkempire78.opencalculator.activities

import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.darkempire78.opencalculator.R
import com.darkempire78.opencalculator.gallery.GalleryAdapter
import com.darkempire78.opencalculator.gallery.GalleryManager
import com.google.android.material.navigation.NavigationView

class GalleryActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var galleryAdapter: GalleryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        drawerLayout = findViewById(R.id.drawer_layout)
        val navView = findViewById<NavigationView>(R.id.nav_view)
        navView.setNavigationItemSelectedListener(this)

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        val galleryName = intent.getStringExtra("galleryName")!!
        val galleryPin = intent.getStringExtra("galleryPin")!!
        val galleryManager = GalleryManager(this)
        val gallery = galleryManager.getGallery(galleryName, galleryPin)

        val recyclerView = findViewById<RecyclerView>(R.id.gallery_recycler_view)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        galleryAdapter = GalleryAdapter(gallery.mediaItems)
        recyclerView.adapter = galleryAdapter
    }

    private val PICK_IMAGE_REQUEST = 1

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.nav_add_pictures -> {
                val intent = Intent(Intent.ACTION_GET_CONTENT)
                intent.type = "image/*"
                startActivityForResult(intent, PICK_IMAGE_REQUEST)
            }
            R.id.nav_export_gallery -> {
                exportGallery()
            }
            R.id.nav_delete_gallery -> {
                showDeleteGalleryDialog()
            }
            R.id.nav_create_new_gallery -> {
                showCreateNewGalleryDialog()
            }
            R.id.nav_sort_name -> {
                galleryAdapter.sortByName()
            }
            R.id.nav_sort_date -> {
                galleryAdapter.sortByDate()
            }
            R.id.nav_sort_custom -> {
                enableCustomSort()
            }
        }
        drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    private fun enableCustomSort() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.START or ItemTouchHelper.END,
            0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                galleryAdapter.onItemMove(viewHolder.adapterPosition, target.adapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        }
        val itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(findViewById(R.id.gallery_recycler_view))
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    private var sensorManager: android.hardware.SensorManager? = null
    private var accelerometer: android.hardware.Sensor? = null
    private var sensorEventListener: android.hardware.SensorEventListener? = null

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(sensorEventListener)
        finish()
    }

    override fun onResume() {
        super.onResume()
        sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
        accelerometer = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        sensorEventListener = object : android.hardware.SensorEventListener {
            override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: android.hardware.SensorEvent?) {
                if (event?.values?.get(2)!! < -9.5) {
                    finish()
                }
            }
        }
        sensorManager?.registerListener(sensorEventListener, accelerometer, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null && data.data != null) {
            when (requestCode) {
                PICK_IMAGE_REQUEST -> {
                    val uri = data.data!!
                    val galleryName = intent.getStringExtra("galleryName")!!
                    val galleryPin = intent.getStringExtra("galleryPin")!!
                    val galleryManager = GalleryManager(this)

                    val inputStream = contentResolver.openInputStream(uri)
                    val file = File(cacheDir, "temp_image")
                    val outputStream = file.outputStream()
                    inputStream?.copyTo(outputStream)

                    galleryManager.addPhoto(galleryName, galleryPin, file)
                    galleryAdapter.notifyDataSetChanged()

                    val dialog = android.app.AlertDialog.Builder(this)
                        .setTitle("Delete Original?")
                        .setMessage("Do you want to delete the original image from your device?")
                        .setPositiveButton("Yes") { _, _ ->
                            contentResolver.delete(uri, null, null)
                        }
                        .setNegativeButton("No", null)
                        .create()
                    dialog.show()
                }
                2 -> {
                    val uri = data.data!!
                    val galleryName = intent.getStringExtra("galleryName")!!
                    val galleryManager = GalleryManager(this)
                    val destination = File(uri.path)
                    galleryManager.exportGallery(galleryName, destination)
                }
            }
        }
    }

    private fun showCreateNewGalleryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_gallery, null)
        val galleryNameEditText = dialogView.findViewById<android.widget.EditText>(R.id.gallery_name_edit_text)
        val pinEditText = dialogView.findViewById<android.widget.EditText>(R.id.pin_edit_text)

        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Create New Gallery")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val galleryName = galleryNameEditText.text.toString()
                val pin = pinEditText.text.toString()
                val galleryManager = GalleryManager(this)
                galleryManager.createGallery(galleryName, pin)

                val intent = Intent(this, GalleryActivity::class.java)
                intent.putExtra("galleryName", galleryName)
                intent.putExtra("galleryPin", pin)
                startActivity(intent)
                finish()
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun showDeleteGalleryDialog() {
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Delete Gallery")
            .setMessage("Are you sure you want to delete this gallery?")
            .setPositiveButton("Yes") { _, _ ->
                val galleryName = intent.getStringExtra("galleryName")!!
                val galleryManager = GalleryManager(this)
                galleryManager.deleteGallery(galleryName)
                finish()
            }
            .setNegativeButton("No", null)
            .create()
        dialog.show()
    }

    private fun exportGallery() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, 2)
    }
}
