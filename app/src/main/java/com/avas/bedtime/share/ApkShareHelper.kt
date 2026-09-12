package com.avas.bedtime.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.avas.bedtime.BuildConfig
import java.io.File
import kotlin.concurrent.thread

object ApkShareHelper {
    private const val TAG = "ApkShare"
    private const val MIME_APK = "application/vnd.android.package-archive"

    /**
     * Copies this install's APK into cache and opens the system share sheet
     * (Nearby Share / Quick Share, Bluetooth, Drive, etc.).
     */
    fun shareInstalledApk(context: Context) {
        val app = context.applicationContext
        Toast.makeText(app, "Preparing APK…", Toast.LENGTH_SHORT).show()
        thread(name = "ApkShareCopy") {
            runCatching {
                val src = File(app.applicationInfo.sourceDir)
                if (!src.exists()) error("Installed APK not found")

                val outDir = File(app.cacheDir, "share").apply { mkdirs() }
                outDir.listFiles()?.forEach { old ->
                    if (old.isFile && old.name.endsWith(".apk")) old.delete()
                }
                val outName = "AvaBedtime-${BuildConfig.VERSION_NAME}.apk"
                val out = File(outDir, outName)
                src.inputStream().use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                if (out.length() < 1_000_000L) {
                    error("Shared APK looks truncated (${out.length()} bytes)")
                }

                val authority = "${app.packageName}.fileprovider"
                val uri = FileProvider.getUriForFile(app, authority, out)

                val send = Intent(Intent.ACTION_SEND).apply {
                    type = MIME_APK
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Ava Bedtime ${BuildConfig.VERSION_NAME}")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Install $outName.\n\n" +
                            "If Android says the app wasn't installed: uninstall the old Ava Bedtime first, " +
                            "then open this file again (signature mismatch on updates).\n\n" +
                            "Allow installs from Files / Nearby Share if asked."
                    )
                    clipData = ClipData.newUri(app.contentResolver, outName, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(send, "Share Ava Bedtime APK").apply {
                    clipData = send.clipData
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                app.startActivity(chooser)
                Log.i(TAG, "Sharing APK $outName (${out.length()} bytes)")
            }.onFailure { err ->
                Log.e(TAG, "Share failed", err)
                android.os.Handler(app.mainLooper).post {
                    Toast.makeText(
                        app,
                        "Could not share app: ${err.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
}
