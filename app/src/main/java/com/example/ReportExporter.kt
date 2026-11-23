package com.example

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports structured diagnostic telemetry JSON reports directly to the device Downloads
 * directory using MediaStore (Android 10+ / API 29+) with a zero-permission FileProvider
 * fallback on API 24-28.
 */
object ReportExporter {

    fun exportJsonToDownloads(
        context: Context,
        jsonContent: String,
        filePrefix: String = "webview_diagnostics"
    ): ExportReportOutcome {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${filePrefix}_$timestamp.json"
        return exportContentToDownloads(
            context = context,
            fileName = fileName,
            mimeType = "application/json",
            content = jsonContent
        )
    }

    fun exportTextLogDumpToDownloads(
        context: Context,
        textContent: String
    ): ExportReportOutcome {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "webview_log_dump_$timestamp.txt"
        return exportContentToDownloads(
            context = context,
            fileName = fileName,
            mimeType = "text/plain",
            content = textContent
        )
    }

    private fun exportContentToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): ExportReportOutcome {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, fileName, mimeType, content)
            } else {
                saveViaAppDownloads(context, fileName, mimeType, content)
            }
        } catch (e: Exception) {
            ExportReportOutcome(
                success = false,
                fileName = fileName,
                displayPath = "",
                mimeType = mimeType,
                errorMessage = e.localizedMessage ?: "Unknown storage error"
            )
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): ExportReportOutcome {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Unable to create MediaStore entry in Downloads.")

        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw IOException("Unable to open output stream for Downloads export.")

            contentValues.clear()
            contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }

        return ExportReportOutcome(
            success = true,
            fileName = fileName,
            displayPath = "Downloads/$fileName",
            contentUri = uri,
            mimeType = mimeType
        )
    }

    private fun saveViaAppDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        content: String
    ): ExportReportOutcome {
        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.cacheDir, "reports")
        if (!downloadsDir.exists()) {
            downloadsDir.mkdirs()
        }
        val outputFile = File(downloadsDir, fileName)
        outputFile.writeText(content, Charsets.UTF_8)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile
        )

        return ExportReportOutcome(
            success = true,
            fileName = fileName,
            displayPath = "Downloads/$fileName",
            contentUri = uri,
            mimeType = mimeType
        )
    }
}
