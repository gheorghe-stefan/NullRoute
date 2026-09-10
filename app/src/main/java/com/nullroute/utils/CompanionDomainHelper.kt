package com.nullroute.utils

data class CompanionDomainInfo(
    val mainDomain: String,
    val companionDomain: String,
    val description: String
)

object CompanionDomainHelper {

    private val COMPANION_MAPPINGS = listOf(
        CompanionDomainInfo(
            mainDomain = "instagram.com",
            companionDomain = "cdninstagram.com",
            description = "Instagram app reels, stories & images"
        ),
        CompanionDomainInfo(
            mainDomain = "facebook.com",
            companionDomain = "fbcdn.net",
            description = "Facebook app photos & video feed"
        ),
        CompanionDomainInfo(
            mainDomain = "tiktok.com",
            companionDomain = "tiktokv.com",
            description = "TikTok app video stream"
        ),
        CompanionDomainInfo(
            mainDomain = "reddit.com",
            companionDomain = "redditmedia.com",
            description = "Reddit app media & images"
        ),
        CompanionDomainInfo(
            mainDomain = "x.com",
            companionDomain = "twimg.com",
            description = "X app media & images"
        ),
        CompanionDomainInfo(
            mainDomain = "twitter.com",
            companionDomain = "twimg.com",
            description = "Twitter app media & images"
        ),
        CompanionDomainInfo(
            mainDomain = "youtube.com",
            companionDomain = "googlevideo.com",
            description = "YouTube app video stream"
        )
    )

    fun getCompanionDomain(input: String): CompanionDomainInfo? {
        val normalized = DomainNormalizer.normalize(input) ?: input.trim().lowercase(java.util.Locale.US)
        return COMPANION_MAPPINGS.firstOrNull { mapping ->
            normalized == mapping.mainDomain || normalized.endsWith(".${mapping.mainDomain}")
        }
    }
}
