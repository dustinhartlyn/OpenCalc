package com.darkempire78.opencalculator.securegallery

// Media types supported by the secure gallery
enum class MediaType {
    PHOTO, VIDEO
}

// Size categories for memory management
enum class MediaSize {
    SMALL,   // < 10MB - safe to load into memory
    MEDIUM,  // 10MB - 100MB - use streaming but manageable
    LARGE    // > 100MB - use careful streaming and optimizations
}
