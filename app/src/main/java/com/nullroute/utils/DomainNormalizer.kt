package com.nullroute.utils

import java.net.URI
import java.util.Locale

object DomainNormalizer {
    /**
     * Normalizes a user-input domain or URL by:
     * 1. Converting to lowercase.
     * 2. Prepending a scheme if missing (so URI parser works).
     * 3. Extracting the host.
     * 4. Stripping leading "www." prefix.
     * Returns null if the domain is invalid or does not contain a dot.
     */
    fun normalize(input: String?): String? {
        if (input == null) return null
        var trimmed = input.trim().lowercase(Locale.US)
        if (trimmed.isEmpty()) return null

        // Prepend scheme if missing (URI requires scheme to identify host)
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://$trimmed"
        }

        return try {
            val uri = URI(trimmed)
            var host = uri.host ?: return null
            
            // Strip leading "www." if present
            if (host.startsWith("www.")) {
                host = host.substring(4)
            }
            
            // Basic validation: must contain a dot and have length > 3
            if (host.contains(".") && host.length > 3) {
                host
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
