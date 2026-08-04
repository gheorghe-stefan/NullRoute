package com.nullroute.data

import android.content.Context
import android.util.Log
import com.nullroute.utils.DomainNormalizer

interface BlocklistRepository {
    fun getBlockedDomains(): List<BlockedDomain>
    fun getBlockedDomainStrings(): Set<String>
    fun addBlockedDomain(domain: String, isRemovable: Boolean = true): Boolean
    fun removeBlockedDomain(domain: String): Boolean
}

class SharedPreferencesBlocklistRepository(private val context: Context) : BlocklistRepository {

    companion object {
        private const val PREFS_NAME = "nullroute_prefs"
        private const val KEY_CUSTOM_DOMAINS = "custom_blocked_domains"
        private const val TAG = "NullRouteRepo"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val file = context.filesDir.resolve("custom_blocked_domains.txt")
    
    private var cachedDomains: List<BlockedDomain> = emptyList()
    private var lastModifiedTime: Long = -1L

    init {
        // Migrate custom domains from SharedPreferences if preference exists
        try {
            if (prefs.contains(KEY_CUSTOM_DOMAINS)) {
                val custom = prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()) ?: emptySet()
                if (custom.isNotEmpty() && !file.exists()) {
                    file.writeText(custom.joinToString("\n") { "$it|true" })
                }
                prefs.edit().remove(KEY_CUSTOM_DOMAINS).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating domains to file", e)
        }
    }

    override fun getBlockedDomains(): List<BlockedDomain> {
        return synchronized(this) {
            if (!file.exists()) {
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
            try {
                file.writeText(list.joinToString("\n") { "${it.domain}|${it.isRemovable}" })
                cachedDomains = list
                lastModifiedTime = file.lastModified()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun removeBlockedDomain(domain: String): Boolean {
        val normalized = DomainNormalizer.normalize(domain) ?: return false

        return synchronized(this) {
            val list = getBlockedDomains().toMutableList()
            val target = list.find { it.domain == normalized } ?: return@synchronized false
            
            // Non-removable domain cannot be removed
            if (!target.isRemovable) {
                return@synchronized false
            }

            list.remove(target)
            try {
                file.writeText(list.joinToString("\n") { "${it.domain}|${it.isRemovable}" })
                cachedDomains = list
                lastModifiedTime = file.lastModified()
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
