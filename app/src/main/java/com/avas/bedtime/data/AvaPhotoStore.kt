package com.avas.bedtime.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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

    fun decodeUri(context: Context, uri: Uri): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            var sample = 1
            val maxEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            while (maxEdge / sample > MAX_DECODE_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        }.onFailure {
            Log.e(TAG, "Failed to decode photo", it)
        }.getOrNull()
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
