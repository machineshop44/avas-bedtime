package com.avas.bedtime.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AvaPhotoStore {
    private const val TAG = "AvaPhotoStore"
    private const val FILE_NAME = "ava_photo.jpg"
    private const val MAX_DECODE_EDGE = 2048

    fun photoFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    fun hasPhoto(context: Context): Boolean = photoFile(context).exists()

    /**
     * Decodes a gallery/camera URI upright.
     * API 28+ uses ImageDecoder (applies EXIF automatically — fixes Samsung A-series
     * phones that otherwise open sideways). Older APIs fall back to ExifInterface +
     * MediaStore orientation.
     */
    fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                decodeWithImageDecoder(context, uri)
            } else {
                decodeWithExifFallback(context, uri)
            }
        }.onFailure {
            Log.e(TAG, "Failed to decode photo", it)
        }.getOrNull()
    }

    private fun decodeWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val maxEdge = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
            if (maxEdge > MAX_DECODE_EDGE) {
                val scale = MAX_DECODE_EDGE.toFloat() / maxEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1)
                )
            }
            // Software bitmap so crop/rotate canvas ops always work.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
        }
    }

    private fun decodeWithExifFallback(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        val maxEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        while (maxEdge / sample > MAX_DECODE_EDGE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        val orientation = readOrientation(context, uri)
        return applyExifOrientation(raw, orientation)
    }

    private fun readOrientation(context: Context, uri: Uri): Int {
        val fromExif = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            }
        }.getOrNull()
        if (fromExif != null &&
            fromExif != ExifInterface.ORIENTATION_UNDEFINED &&
            fromExif != ExifInterface.ORIENTATION_NORMAL
        ) {
            return fromExif
        }

        // Some OEMs expose rotation only via MediaStore (degrees), not EXIF on the stream.
        val degrees = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Images.ImageColumns.ORIENTATION),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            } ?: 0
        }.getOrDefault(0)
        return when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    fun rotate90Clockwise(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
    }

    fun saveBitmap(context: Context, bitmap: Bitmap): Boolean {
        return runCatching {
            val out = photoFile(context)
            FileOutputStream(out).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
            Log.i(TAG, "Saved Ava photo ${out.length()} bytes")
            true
        }.onFailure {
            Log.e(TAG, "Failed to save Ava photo", it)
        }.getOrDefault(false)
    }

    /** Downsampled decode for on-screen display (home/settings) — avoids keeping full-res bitmaps. */
    fun decodeFileForDisplay(path: String, maxEdge: Int = 512): Bitmap? {
        if (path.isBlank()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            var sample = 1
            val edge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (edge / sample > maxEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, opts)
        }.onFailure {
            Log.e(TAG, "Failed to decode display photo", it)
        }.getOrNull()
    }

    fun copyFromUri(context: Context, uri: Uri): Boolean {
        val bitmap = decodeUri(context, uri) ?: return false
        return saveBitmap(context, bitmap)
    }

    fun clear(context: Context) {
        val file = photoFile(context)
        if (file.exists()) {
            file.delete()
            Log.i(TAG, "Removed Ava photo")
        }
    }
}
