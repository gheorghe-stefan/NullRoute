package com.nullroute.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.nullroute.utils.DomainNormalizer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ExternalBlocklistBackupHelper {

    private const val TAG = "NullRouteBackup"
    const val HIDDEN_DIR_NAME = ".nullroute"
    const val HIDDEN_TXT_NAME = ".blocked_domains.txt"
    const val BACKUP_DIR_NAME = "NullRoute"
    const val TXT_FILE_NAME = "blocked_domains.txt"
    const val JSON_FILE_NAME = "blocked_domains.json"

    /**
     * Saves blocked domains in order to external storage (Documents / Downloads) as strictly ONE file.
     * Writes to hidden directories (.nullroute/.blocked_domains.txt) so it is not visible to users.
     */
    fun saveBackup(context: Context, domains: List<BlockedDomain>) {
        try {
            val txtString = serializeToTxt(domains)

            var savedDirectly = false
            // 1. Save to hidden Documents/.nullroute/.blocked_domains.txt
            if (trySaveToPublicDirectory(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                HIDDEN_DIR_NAME,
                HIDDEN_TXT_NAME,
                txtString
            )) {
                savedDirectly = true
            }

            // 2. Save to hidden Download/.nullroute/.blocked_domains.txt
            if (trySaveToPublicDirectory(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                HIDDEN_DIR_NAME,
                HIDDEN_TXT_NAME,
                txtString
            )) {
                savedDirectly = true
            }

            // 3. Also mirror to standard Documents/NullRoute/blocked_domains.txt
            trySaveToPublicDirectory(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                BACKUP_DIR_NAME,
                TXT_FILE_NAME,
                txtString
            )

            // 4. Also mirror to standard Download/NullRoute/blocked_domains.txt
            trySaveToPublicDirectory(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                BACKUP_DIR_NAME,
                TXT_FILE_NAME,
                txtString
            )

            // 5. Only use MediaStore fallback if direct write was blocked
            if (!savedDirectly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(context, TXT_FILE_NAME, "text/plain", txtString)
            }

            Log.i(TAG, "Successfully backed up ${domains.size} domains to hidden external storage")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to complete external backup", e)
        }
    }

    /**
     * Looks for existing backup file on the device across known hidden and public locations.
     * If found, returns the domains in preserved order.
     * If isPro is false, takes only the first 2 entries as requested.
     */
    fun loadBackup(context: Context, isPro: Boolean): List<BlockedDomain>? {
        val searchLocations = listOf(
            Pair(Environment.DIRECTORY_DOCUMENTS, HIDDEN_DIR_NAME to HIDDEN_TXT_NAME),
            Pair(Environment.DIRECTORY_DOWNLOADS, HIDDEN_DIR_NAME to HIDDEN_TXT_NAME),
            Pair(Environment.DIRECTORY_DOCUMENTS, BACKUP_DIR_NAME to TXT_FILE_NAME),
            Pair(Environment.DIRECTORY_DOWNLOADS, BACKUP_DIR_NAME to TXT_FILE_NAME)
        )

        for ((pubDirName, filePair) in searchLocations) {
            val (dirName, fileName) = filePair
            try {
                val dir = File(Environment.getExternalStoragePublicDirectory(pubDirName), dirName)
                val targetFile = File(dir, fileName)
                if (targetFile.exists() && targetFile.length() > 0) {
                    val list = parseTxt(targetFile.readText())
                    if (list.isNotEmpty()) {
                        Log.i(TAG, "Loaded ${list.size} domains from $pubDirName/$dirName/$fileName")
                        return applyProLimit(list, isPro)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading $pubDirName/$dirName/$fileName: ${e.message}")
            }
        }

        // Check directory file scans for legacy files
        val docsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), BACKUP_DIR_NAME)
        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_DIR_NAME)
        loadFromDirectory(docsDir, isPro)?.let { return it }
        loadFromDirectory(downloadDir, isPro)?.let { return it }

        // Fallback to MediaStore
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            loadFromMediaStore(context)?.let { list ->
                Log.i(TAG, "Loaded ${list.size} domains from MediaStore backup")
                return applyProLimit(list, isPro)
            }
        }

        return null
    }

    fun applyProLimit(domains: List<BlockedDomain>, isPro: Boolean): List<BlockedDomain> {
        return if (isPro) domains else domains.take(2)
    }

    fun trySaveToPublicDirectory(parentDir: File, dirName: String, fileName: String, txtContent: String): Boolean {
        return try {
            val dir = File(parentDir, dirName)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            if (dir.exists()) {
                // Clean up any other files in the directory to strictly keep ONE file
                try {
                    dir.listFiles()?.forEach { file ->
                        if (file.name != fileName) {
                            file.delete()
                        }
                    }
                } catch (e: Exception) {
                    // Ignore cleanup failure
                }
                val file = File(dir, fileName)
                file.writeText(txtContent)
                file.exists() && file.length() > 0
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun trySaveToPublicDirectory(parentDir: File, txtContent: String): Boolean {
        return trySaveToPublicDirectory(parentDir, BACKUP_DIR_NAME, TXT_FILE_NAME, txtContent)
    }

    @Suppress("UNUSED_PARAMETER")
    fun trySaveToPublicDirectory(parentDir: File, jsonContent: String, txtContent: String): Boolean {
        return trySaveToPublicDirectory(parentDir, txtContent)
    }

    fun loadFromDirectory(dir: File, isPro: Boolean = true): List<BlockedDomain>? {
        try {
            if (!dir.exists()) return null

            // Find all matching backup files sorted by most recently modified
            val files = dir.listFiles { _, name ->
                name.startsWith("blocked_domains") && (name.endsWith(".json") || name.endsWith(".txt"))
            }?.sortedByDescending { it.lastModified() } ?: emptyList()

            for (file in files) {
                if (file.exists() && file.length() > 0) {
                    try {
                        val content = file.readText()
                        val list = if (file.name.endsWith(".json")) parseJson(content) else parseTxt(content)
                        if (list.isNotEmpty()) {
                            Log.i(TAG, "Loaded ${list.size} domains from ${file.name}")
                            return applyProLimit(list, isPro)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed reading ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading from ${dir.absolutePath}", e)
        }
        return null
    }

    private fun saveToMediaStore(context: Context, fileName: String, mimeType: String, content: String) {
        try {
            val resolver = context.contentResolver
            val collectionUri = MediaStore.Files.getContentUri("external")
            
            // Check if file already exists in MediaStore
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf(fileName, "%$BACKUP_DIR_NAME%")
            
            var existingUri: Uri? = null
            resolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                    existingUri = Uri.withAppendedPath(collectionUri, id.toString())
                }
            }

            var written = false
            if (existingUri != null) {
                try {
                    resolver.openOutputStream(existingUri!!, "wt")?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                        written = true
                    }
                } catch (e: Exception) {
                    // Cannot write to existing file from older install UID; delete and recreate
                    try {
                        resolver.delete(existingUri!!, null, null)
                    } catch (ignored: Exception) {}
                }
            }

            if (!written) {
                // Delete any numbered duplicate files in MediaStore to strictly keep ONE file
                try {
                    val dupSelection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                    val dupArgs = arrayOf("blocked_domains%", "%$BACKUP_DIR_NAME%")
                    resolver.delete(collectionUri, dupSelection, dupArgs)
                } catch (ignored: Exception) {}

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$BACKUP_DIR_NAME/")
                }
                val newUri = resolver.insert(collectionUri, values)
                newUri?.let { uri ->
                    resolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save to MediaStore", e)
        }
    }

    private fun loadFromMediaStore(context: Context): List<BlockedDomain>? {
        return try {
            val resolver = context.contentResolver
            val collectionUris = mutableListOf<Uri>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collectionUris.add(MediaStore.Files.getContentUri("external"))
                collectionUris.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }

            val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("blocked_domains%")

            for (collectionUri in collectionUris) {
                try {
                    Log.i(TAG, "Querying MediaStore uri: $collectionUri")
                    resolver.query(
                        collectionUri,
                        projection,
                        selection,
                        selectionArgs,
                        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                    )?.use { cursor ->
                        Log.i(TAG, "MediaStore query returned ${cursor.count} rows for $collectionUri")
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                            val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                            val relPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) ?: ""
                            Log.i(TAG, "Found MediaStore row: id=$id name=$displayName relPath=$relPath")
                            if (!relPath.contains(BACKUP_DIR_NAME) && !displayName.contains("blocked_domains")) {
                                continue
                            }
                            val uri = Uri.withAppendedPath(collectionUri, id.toString())
                            try {
                                resolver.openInputStream(uri)?.use { stream ->
                                    val content = stream.bufferedReader().readText()
                                    val list = if (displayName.endsWith(".json")) parseJson(content) else parseTxt(content)
                                    if (list.isNotEmpty()) {
                                        Log.i(TAG, "Successfully read ${list.size} domains from MediaStore uri: $uri")
                                        return list
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed opening MediaStore stream for $uri: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error querying $collectionUri", e)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Top level error in loadFromMediaStore", e)
            null
        }
    }

    fun serializeToJson(domains: List<BlockedDomain>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        domains.forEachIndexed { index, item ->
            val type = if (item.isRemovable) "removable" else "permanent"
            sb.append("  {\n")
            sb.append("    \"domain\": \"${item.domain}\",\n")
            sb.append("    \"isRemovable\": ${item.isRemovable},\n")
            sb.append("    \"type\": \"$type\"\n")
            sb.append("  }")
            if (index < domains.size - 1) {
                sb.append(",")
            }
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    fun serializeToTxt(domains: List<BlockedDomain>): String {
        val sb = StringBuilder()
        sb.appendLine("# NullRoute Blocked Domains Backup (In-Order)")
        sb.appendLine("# Format: domain|removable|type")
        domains.forEach { item ->
            val type = if (item.isRemovable) "removable" else "permanent"
            sb.appendLine("${item.domain}|${item.isRemovable}|$type")
        }
        return sb.toString().trimEnd()
    }

    fun parseJson(jsonString: String): List<BlockedDomain> {
        val seen = mutableSetOf<String>()
        val list = mutableListOf<BlockedDomain>()
        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val domainRaw = obj.optString("domain", "")
                val normalized = DomainNormalizer.normalize(domainRaw) ?: continue
                if (!seen.add(normalized)) continue
                val isRemovable = obj.optBoolean("isRemovable", true)
                list.add(BlockedDomain(domain = normalized, isRemovable = isRemovable))
            }
            if (list.isNotEmpty()) return list
        } catch (e: Throwable) {
            // Fallback for JVM tests or environments without Android JSON stubbing
        }

        try {
            val domainRegex = Regex(""""domain"\s*:\s*"([^"]+)"""")
            val removableRegex = Regex(""""isRemovable"\s*:\s*(true|false)""")
            val objectRegex = Regex("""\{[^}]*\}""")
            for (match in objectRegex.findAll(jsonString)) {
                val objStr = match.value
                val domainMatch = domainRegex.find(objStr) ?: continue
                val domainRaw = domainMatch.groupValues[1]
                val normalized = DomainNormalizer.normalize(domainRaw) ?: continue
                if (!seen.add(normalized)) continue
                val removableMatch = removableRegex.find(objStr)
                val isRemovable = removableMatch?.groupValues?.get(1)?.toBooleanStrictOrNull() ?: true
                list.add(BlockedDomain(domain = normalized, isRemovable = isRemovable))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse JSON backup", e)
        }
        return list
    }

    fun parseTxt(txtString: String): List<BlockedDomain> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<BlockedDomain>()
        for (rawLine in txtString.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split("|")
            val normalized = DomainNormalizer.normalize(parts[0]) ?: continue
            if (!seen.add(normalized)) continue
            val isRemovable = if (parts.size > 1) parts[1].toBooleanStrictOrNull() ?: true else true
            result.add(BlockedDomain(domain = normalized, isRemovable = isRemovable))
        }
        return result
    }
}
