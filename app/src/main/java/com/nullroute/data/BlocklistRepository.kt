package com.nullroute.data

import android.content.Context
import android.util.Log
import com.nullroute.utils.DomainNormalizer

interface BlocklistRepository {
    fun getBlockedDomains(): List<BlockedDomain>
    fun getBlockedDomainStrings(): Set<String>
    fun addBlockedDomain(domain: String, isRemovable: Boolean = true): Boolean
    fun addBlockedDomains(domains: List<String>, isRemovable: Boolean = true): Int
    fun removeBlockedDomain(domain: String, force: Boolean = false): Boolean
    fun updateDomainRemovable(domain: String, isRemovable: Boolean): Boolean
}

class FileBlocklistRepository(private val context: Context) : BlocklistRepository {

    companion object {
        private const val TAG = "NullRouteRepo"
    }

    private val file = context.filesDir.resolve("blocked_domains.txt")
    
    private var cachedDomains: List<BlockedDomain> = emptyList()
    private var lastModifiedTime: Long = -1L

    private fun persistList(list: List<BlockedDomain>): Boolean {
        return try {
            file.writeText(list.joinToString("\n") { "${it.domain}|${it.isRemovable}" })
            cachedDomains = list
            lastModifiedTime = file.lastModified()
            ExternalBlocklistBackupHelper.saveBackup(context, list)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to persist domains", e)
            false
        }
    }

    override fun getBlockedDomains(): List<BlockedDomain> {
        return synchronized(this) {
            if (!file.exists() || file.length() == 0L) {
                // Attempt to restore from external backup if available (e.g. across reinstalls)
                try {
                    val prefs = context.getSharedPreferences("nullroute_prefs", Context.MODE_PRIVATE)
                    val isPro = prefs.getBoolean("is_pro_unlocked", false)
                    val restored = ExternalBlocklistBackupHelper.loadBackup(context, isPro)
                    if (!restored.isNullOrEmpty()) {
                        file.writeText(restored.joinToString("\n") { "${it.domain}|${it.isRemovable}" })
                        cachedDomains = restored
                        lastModifiedTime = file.lastModified()
                        Log.i(TAG, "Restored ${restored.size} domains from external backup")
                        return@synchronized cachedDomains
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore backup", e)
                }
                cachedDomains = emptyList()
                lastModifiedTime = -1L
                return@synchronized emptyList()
            }
            val currentModified = file.lastModified()
            if (currentModified != lastModifiedTime) {
                try {
                    cachedDomains = file.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .mapNotNull { line ->
                            val parts = line.split("|")
                            val normalized = DomainNormalizer.normalize(parts[0]) ?: return@mapNotNull null
                            val isRemovable = if (parts.size > 1) parts[1].toBooleanStrictOrNull() ?: true else true
                            BlockedDomain(domain = normalized, isRemovable = isRemovable)
                        }
                    lastModifiedTime = currentModified
                    if (cachedDomains.isNotEmpty()) {
                        ExternalBlocklistBackupHelper.saveBackup(context, cachedDomains)
                    }
                } catch (e: Exception) {
                    // Ignore and keep using cache on read failure
                }
            }
            cachedDomains
        }
    }

    override fun getBlockedDomainStrings(): Set<String> {
        return getBlockedDomains().map { it.domain }.toSet()
    }

    override fun addBlockedDomain(domain: String, isRemovable: Boolean): Boolean {
        val normalized = DomainNormalizer.normalize(domain) ?: return false
        
        // Return false if domain already exists
        if (getBlockedDomainStrings().contains(normalized)) {
            return false
        }

        return synchronized(this) {
            val list = getBlockedDomains().toMutableList()
            val newEntry = BlockedDomain(domain = normalized, isRemovable = isRemovable)
            list.add(newEntry)
            persistList(list)
        }
    }

    override fun addBlockedDomains(domains: List<String>, isRemovable: Boolean): Int {
        return synchronized(this) {
            val existing = getBlockedDomainStrings().toMutableSet()
            val list = getBlockedDomains().toMutableList()
            var addedCount = 0

            for (domain in domains) {
                val normalized = DomainNormalizer.normalize(domain) ?: continue
                if (!existing.contains(normalized)) {
                    list.add(BlockedDomain(domain = normalized, isRemovable = isRemovable))
                    existing.add(normalized)
                    addedCount++
                }
            }

            if (addedCount > 0) {
                if (persistList(list)) addedCount else 0
            } else {
                0
            }
        }
    }

    override fun removeBlockedDomain(domain: String, force: Boolean): Boolean {
        val normalized = DomainNormalizer.normalize(domain) ?: return false

        return synchronized(this) {
            val list = getBlockedDomains().toMutableList()
            val target = list.find { it.domain == normalized } ?: return@synchronized false
            
            // Non-removable domain cannot be removed unless force = true (e.g. Free tier downgrade)
            if (!target.isRemovable && !force) {
                return@synchronized false
            }

            list.remove(target)
            persistList(list)
        }
    }

    override fun updateDomainRemovable(domain: String, isRemovable: Boolean): Boolean {
        val normalized = DomainNormalizer.normalize(domain) ?: return false

        return synchronized(this) {
            val list = getBlockedDomains().toMutableList()
            val index = list.indexOfFirst { it.domain == normalized }
            if (index == -1) return@synchronized false

            list[index] = list[index].copy(isRemovable = isRemovable)
            persistList(list)
        }
    }
}

// Typealias for backwards compatibility
typealias SharedPreferencesBlocklistRepository = FileBlocklistRepository
