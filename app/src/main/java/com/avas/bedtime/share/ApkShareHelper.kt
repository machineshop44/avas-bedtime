package com.avas.bedtime.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ApkShareHelper {
    private const val TAG = "ApkShare"

    /**
     * Copies this install's APK into a zip and opens the system share sheet.
     *
     * Quick Share / Nearby Share often refuses raw APKs (target phone appears
     * but cannot be selected). A zip transfers cleanly; open it on the other
     * device and tap the APK inside to install.
     */
    fun shareInstalledApk(context: Context) {
        runCatching {
            val src = File(context.applicationInfo.sourceDir)
            if (!src.exists()) error("Installed APK not found")

            val outDir = File(context.cacheDir, "share").apply { mkdirs() }
            val zipOut = File(outDir, "AvaBedtime-update.zip")
            ZipOutputStream(zipOut.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("AvaBedtime-update.apk"))
                src.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }

            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, zipOut)

            val send = Intent(Intent.ACTION_SEND).apply {
                // Zip MIME is accepted by Quick Share; raw APK MIME often is not.
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Ava Bedtime update")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Open this zip on Ava's phone, tap AvaBedtime-update.apk, then Install. " +
                        "Allow installs from Files/your browser if Android asks."
                )
                clipData = ClipData.newUri(context.contentResolver, "Ava Bedtime update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(send, "Share Ava Bedtime to tablet").apply {
                // Chooser must also carry ClipData or some receivers get no read access.
                clipData = send.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            Log.i(TAG, "Sharing zip ${zipOut.length()} bytes (APK wrapped for Quick Share)")
        }.onFailure { err ->
            Log.e(TAG, "Share failed", err)
            Toast.makeText(
                context,
                "Could not share app: ${err.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
