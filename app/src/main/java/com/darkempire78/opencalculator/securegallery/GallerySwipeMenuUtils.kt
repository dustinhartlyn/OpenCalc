package com.darkempire78.opencalculator.securegallery

object GallerySwipeMenuUtils {
    fun showSwipeMenu(context: android.content.Context, onOptionSelected: (String) -> Unit) {
        val options = arrayOf(
            "Add Pictures",
            "Export Gallery",
            "Delete Gallery",
            "Create New Gallery",
            "Sort"
        )
        val builder = android.app.AlertDialog.Builder(context)
        builder.setTitle("Gallery Menu")
        builder.setItems(options) { _, which ->
            onOptionSelected(options[which])
        }
        builder.show()
    }
}
