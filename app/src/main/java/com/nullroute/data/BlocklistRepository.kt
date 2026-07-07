package com.nullroute.data

import android.content.Context
import android.util.Log
import com.nullroute.utils.DomainNormalizer

interface BlocklistRepository {
    fun getInitialBlockedDomains(): Set<String>
    fun getCustomBlockedDomains(): Set<String>
    fun getAllBlockedDomains(): Set<String>
    fun addBlockedDomain(domain: String): Boolean
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
    
    private var cachedCustomDomains: Set<String> = emptySet()
    private var lastModifiedTime: Long = -1L

    init {
        // Migrate custom domains from SharedPreferences to file if preference exists
        try {
            if (prefs.contains(KEY_CUSTOM_DOMAINS)) {
                val custom = prefs.getStringSet(KEY_CUSTOM_DOMAINS, emptySet()) ?: emptySet()
                if (custom.isNotEmpty() && !file.exists()) {
                    file.writeText(custom.joinToString("\n"))
                }
                // Clear old preference to avoid re-migration
                prefs.edit().remove(KEY_CUSTOM_DOMAINS).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error migrating custom domains to file", e)
        }
    }

    // Cache initial list using lazy loading to avoid redundant asset parsing
    private val initialDomainsCache: Set<String> by lazy {
        loadInitialBlockedDomains()
    }

    override fun getInitialBlockedDomains(): Set<String> {
        return initialDomainsCache
    }

    override fun getCustomBlockedDomains(): Set<String> {
        return synchronized(this) {
            if (!file.exists()) {
                cachedCustomDomains = emptySet()
                lastModifiedTime = -1L
                return@synchronized emptySet()
            }
            val currentModified = file.lastModified()
            if (currentModified != lastModifiedTime) {
                try {
                    cachedCustomDomains = file.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .toSet()
                    lastModifiedTime = currentModified
                } catch (e: Exception) {
                    // Ignore and keep using cache on read failure
                }
            }
            cachedCustomDomains
        }
    }

    override fun getAllBlockedDomains(): Set<String> {
        return getInitialBlockedDomains() + getCustomBlockedDomains()
    }

    override fun addBlockedDomain(domain: String): Boolean {
        val normalized = DomainNormalizer.normalize(domain) ?: return false
        
        // Return false if it already exists in the immutable/initial list
        if (getInitialBlockedDomains().contains(normalized)) {
            return false
        }

        return synchronized(this) {
            val custom = getCustomBlockedDomains().toMutableSet()
            if (custom.add(normalized)) {
                try {
                    file.writeText(custom.joinToString("\n"))
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }

    override fun removeBlockedDomain(domain: String): Boolean {
        val normalized = DomainNormalizer.normalize(domain) ?: return false
        if (getInitialBlockedDomains().contains(normalized)) {
            return false
        }
        return synchronized(this) {
            val custom = getCustomBlockedDomains().toMutableSet()
            if (custom.remove(normalized)) {
                try {
                    file.writeText(custom.joinToString("\n"))
                    true
                } catch (e: Exception) {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun loadInitialBlockedDomains(): Set<String> {
        val domains = mutableSetOf<String>()
        try {
            context.assets.open("initial_blocked_domains.txt").use { inputStream ->
                inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val normalized = DomainNormalizer.normalize(line)
                        if (normalized != null) {
                            domains.add(normalized)
                        }
                    }
                }
            }
            Log.d(TAG, "Successfully loaded ${domains.size} initial domains from assets.")
        } catch (e: java.io.FileNotFoundException) {
            Log.w(TAG, "initial_blocked_domains.txt not found in assets. App starting with an empty initial list.")
        } catch (e: Exception) {
            Log.e(TAG, "Error reading initial_blocked_domains.txt from assets", e)
        }
        return domains
    }
}
