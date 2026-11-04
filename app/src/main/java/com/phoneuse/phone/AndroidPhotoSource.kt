package com.phoneuse.phone

import android.Manifest
import android.app.WallpaperManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.phoneuse.cards.LatLng
import com.phoneuse.cards.isPhotoUri
import com.phoneuse.files.ImageDownscaler
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Instant

/** MediaStore photos: queries, EXIF locations of originals, thumbnails, album copies and the wallpaper. */
class AndroidPhotoSource(private val context: Context) : PhotoSource {
    private val resolver get() = context.contentResolver

    override val readPermissions: List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // Partial access (Android 14 "selected photos") counts: queries then see only those.
    override fun canRead() = readPermissions.any { granted(it) } || Environment.isExternalStorageManager()

    override fun canReadLocation() = granted(Manifest.permission.ACCESS_MEDIA_LOCATION)

    override fun rows(from: Instant?, to: Instant?, album: String?, max: Int): List<PhotoRow> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        // Photos without a date taken are matched by when they were added (seconds), as PhotoRow does.
        val taken = MediaStore.Images.Media.DATE_TAKEN
        val added = MediaStore.Images.Media.DATE_ADDED
        val untaken = "($taken IS NULL OR $taken <= 0)"
        from?.let {
            where += "(($taken >= ?) OR ($untaken AND $added >= ?))"
            args += it.toEpochMilli().toString()
            args += it.epochSecond.toString()
        }
        to?.let {
            where += "(($taken > 0 AND $taken < ?) OR ($untaken AND $added < ?))"
            args += it.toEpochMilli().toString()
            args += it.epochSecond.toString()
        }
        album?.let {
            where += "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} LIKE ?"
            args += it.replace("%", "").replace("_", "")
        }
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<PhotoRow>()
        resolver.query(
            COLLECTION, projection, where.joinToString(" AND ").ifEmpty { null }, args.toTypedArray(),
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < max) {
                val taken = c.getLong(1).takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
                    ?: c.getLong(2).takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
                out += PhotoRow(ContentUris.withAppendedId(COLLECTION, c.getLong(0)).toString(), taken, c.getString(3))
            }
        }
        return out
    }

    override fun location(uri: String): LatLng? = try {
        // Without setRequireOriginal the EXIF location is redacted.
        resolver.openInputStream(MediaStore.setRequireOriginal(Uri.parse(uri)))?.use { input ->
            ExifInterface(input).latLong?.let { (lat, lng) -> LatLng(lat, lng) }?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }
        }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    } catch (e: UnsupportedOperationException) {
        null
    }

    override fun thumbnail(uri: String, maxEdge: Int): ByteArray? {
        if (!isPhotoUri(uri)) return null
        return try {
            val thumb = resolver.loadThumbnail(Uri.parse(uri), Size(maxEdge, maxEdge), null)
            val (w, h) = ImageDownscaler.targetSize(thumb.width, thumb.height, maxEdge)
            val sized = if (w == thumb.width && h == thumb.height) thumb else Bitmap.createScaledBitmap(thumb, w, h, true)
            ByteArrayOutputStream().also { sized.compress(Bitmap.CompressFormat.JPEG, ImageDownscaler.JPEG_QUALITY, it) }.toByteArray()
        } catch (e: IOException) {
            null
        }
    }

    override fun copyToAlbum(name: String, uris: List<String>): Int = uris.count { copyOne(name, it) }

    private fun copyOne(name: String, uri: String): Boolean {
        if (!isPhotoUri(uri)) return false
        val src = Uri.parse(uri)
        var displayName = "photo_${System.currentTimeMillis()}.jpg"
        var mime = "image/jpeg"
        var taken = 0L
        resolver.query(
            src, arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE, MediaStore.Images.Media.DATE_TAKEN), null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { displayName = it }
                c.getString(1)?.let { mime = it }
                taken = c.getLong(2)
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$name/")
            if (taken > 0) put(MediaStore.Images.Media.DATE_TAKEN, taken)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val dest = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            // The original keeps its location in the copy when that may be read.
            val from = if (canReadLocation()) MediaStore.setRequireOriginal(src) else src
            val input = resolver.openInputStream(from) ?: throw IOException("cannot open")
            input.use { i -> resolver.openOutputStream(dest)?.use { o -> i.copyTo(o) } ?: throw IOException("cannot write") }
            resolver.update(dest, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            true
        } catch (e: Exception) {
            Log.w(TAG, "album copy failed: ${e.javaClass.simpleName}")
            resolver.delete(dest, null, null)
            false
        }
    }

    override fun setWallpaper(uri: String): Boolean {
        if (!isPhotoUri(uri)) return false
        return try {
            resolver.openInputStream(Uri.parse(uri))?.use {
                WallpaperManager.getInstance(context).setStream(it, null, true, WallpaperManager.FLAG_SYSTEM)
            } != null
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    private companion object {
        const val TAG = "PhoneUse"
        val COLLECTION: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
}
