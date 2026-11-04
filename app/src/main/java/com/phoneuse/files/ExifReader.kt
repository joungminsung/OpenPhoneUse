package com.phoneuse.files

import androidx.exifinterface.media.ExifInterface
import java.io.File

data class ExifInfo(val takenAt: String?, val model: String?)

interface ExifReader {
    fun read(file: File): ExifInfo?
}

class AndroidExifReader : ExifReader {
    override fun read(file: File): ExifInfo? = try {
        val exif = ExifInterface(file.absolutePath)
        val takenAt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)
        if (takenAt == null && model == null) null else ExifInfo(takenAt, model)
    } catch (e: Exception) {
        null
    }
}
