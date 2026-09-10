package com.nullroute

import com.nullroute.utils.CompanionDomainHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CompanionDomainHelperTest {

    @Test
    fun testCompanionDomainMappings() {
        val insta = CompanionDomainHelper.getCompanionDomain("instagram.com")
        assertNotNull(insta)
        assertEquals("cdninstagram.com", insta?.companionDomain)

        val instaUrl = CompanionDomainHelper.getCompanionDomain("https://www.instagram.com/explore")
        assertNotNull(instaUrl)
        assertEquals("cdninstagram.com", instaUrl?.companionDomain)

        val fb = CompanionDomainHelper.getCompanionDomain("facebook.com")
        assertNotNull(fb)
        assertEquals("fbcdn.net", fb?.companionDomain)

        val tiktok = CompanionDomainHelper.getCompanionDomain("tiktok.com")
        assertNotNull(tiktok)
        assertEquals("tiktokv.com", tiktok?.companionDomain)

        val reddit = CompanionDomainHelper.getCompanionDomain("reddit.com")
        assertNotNull(reddit)
        assertEquals("redditmedia.com", reddit?.companionDomain)

        val x = CompanionDomainHelper.getCompanionDomain("x.com")
        assertNotNull(x)
        assertEquals("twimg.com", x?.companionDomain)

        val twitter = CompanionDomainHelper.getCompanionDomain("twitter.com")
        assertNotNull(twitter)
        assertEquals("twimg.com", twitter?.companionDomain)

        val yt = CompanionDomainHelper.getCompanionDomain("youtube.com")
        assertNotNull(yt)
        assertEquals("googlevideo.com", yt?.companionDomain)
    }

    @Test
    fun testUnknownOrInvalidDomains() {
        assertNull(CompanionDomainHelper.getCompanionDomain("google.com"))
        assertNull(CompanionDomainHelper.getCompanionDomain("example.com"))
        assertNull(CompanionDomainHelper.getCompanionDomain(""))
        assertNull(CompanionDomainHelper.getCompanionDomain("abc"))
    }
}
