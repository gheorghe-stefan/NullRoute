package com.nullroute.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.nullroute.BuildConfig
import com.nullroute.vpn.DnsTelemetryTracker
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SupportLogExporter {

    private const val TAG = "SupportLogExporter"
    private const val SUPPORT_EMAIL = "stefangh.devapps@gmail.com"

    fun createDiagnosticsZip(context: Context): File? {
        return try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(exportDir, "nullroute_diagnostics.zip")
            if (zipFile.exists()) {
                zipFile.delete()
            }

            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                // 1. diagnostic_report.txt
                try {
                    val reportText = DnsTelemetryTracker.generateExportReport()
                    zos.putNextEntry(ZipEntry("diagnostic_report.txt"))
                    zos.write(reportText.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to include diagnostic report in zip", e)
                }

                // 2. device_info.txt
                try {
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
                    val deviceInfo = buildString {
                        appendLine("=== NULLROUTE DEVICE & SYSTEM INFO ===")
                        appendLine("Timestamp       : ${dateFormat.format(Date())}")
                        appendLine("App Version     : ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})")
                        appendLine("Device Model    : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})")
                        appendLine("Android Version : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("Fingerprint     : ${Build.FINGERPRINT}")
                    }
                    zos.putNextEntry(ZipEntry("device_info.txt"))
                    zos.write(deviceInfo.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to include device info in zip", e)
                }

                // 3. Versioned log files in filesDir/logs/
                try {
                    val logsDir = File(context.filesDir, "logs")
                    if (logsDir.exists() && logsDir.isDirectory) {
                        logsDir.listFiles()?.forEach { logFile ->
                            if (logFile.isFile) {
                                zos.putNextEntry(ZipEntry("logs/${logFile.name}"))
                                FileInputStream(logFile).use { it.copyTo(zos) }
                                zos.closeEntry()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to include versioned logs in zip", e)
                }

                // 4. Legacy root telemetry.log if exists
                try {
                    val rootLog = File(context.filesDir, "telemetry.log")
                    if (rootLog.exists() && rootLog.isFile) {
                        zos.putNextEntry(ZipEntry("telemetry.log"))
                        FileInputStream(rootLog).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                } catch (e: Exception) {
                    // Ignore
                }
            }

            zipFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create diagnostics zip archive", e)
            null
        }
    }

    fun shareDiagnosticsZip(context: Context) {
        val zipFile = createDiagnosticsZip(context)
        if (zipFile == null || !zipFile.exists() || zipFile.length() == 0L) {
            Toast.makeText(context, "Failed to generate diagnostics archive", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                zipFile
            )

            val emailBody = buildString {
                appendLine("Hello Support Team,")
                appendLine()
                appendLine("Please find attached my diagnostic log archive for NullRoute.")
                appendLine()
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine()
                appendLine("Description of issue:")
                appendLine("[Please write details here]")
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(
                    Intent.EXTRA_SUBJECT,
                    "[NullRoute Support] Diagnostics Log Archive (v${BuildConfig.VERSION_NAME})"
                )
                putExtra(Intent.EXTRA_TEXT, emailBody)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Send Diagnostic Logs to Support").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch share chooser for diagnostics", e)
            Toast.makeText(context, "Error sharing diagnostics: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
