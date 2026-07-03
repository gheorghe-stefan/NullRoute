package com.nullroute

import com.nullroute.utils.DomainNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainNormalizerTest {

    @Test
    fun testNormalizeValidDomains() {
        assertEquals("instagram.com", DomainNormalizer.normalize("https://www.instagram.com/"))
        assertEquals("instagram.com", DomainNormalizer.normalize("instagram.com"))
        assertEquals("instagram.com", DomainNormalizer.normalize("http://instagram.com/home?user=1"))
        assertEquals("facebook.com", DomainNormalizer.normalize("www.facebook.com/"))
        assertEquals("sub.domain.co.uk", DomainNormalizer.normalize("https://sub.domain.co.uk/path"))
    }

    @Test
    fun testNormalizeInvalidDomains() {
        assertNull(DomainNormalizer.normalize("abc"))
        assertNull(DomainNormalizer.normalize(""))
        assertNull(DomainNormalizer.normalize(null))
        assertNull(DomainNormalizer.normalize("http://"))
    }
}
