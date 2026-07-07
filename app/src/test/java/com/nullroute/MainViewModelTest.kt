package com.nullroute

import com.nullroute.data.BlocklistRepository
import com.nullroute.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var mockRepository: MockBlocklistRepository
    private lateinit var viewModel: MainViewModel

    class MockBlocklistRepository : BlocklistRepository {
        val initial = setOf("facebook.com", "tiktok.com")
        val custom = mutableSetOf<String>()

        override fun getInitialBlockedDomains(): Set<String> = initial
        override fun getCustomBlockedDomains(): Set<String> = custom
        override fun getAllBlockedDomains(): Set<String> = initial + custom
        override fun addBlockedDomain(domain: String): Boolean {
            val normalized = com.nullroute.utils.DomainNormalizer.normalize(domain) ?: return false
            if (initial.contains(normalized)) return false
            return custom.add(normalized)
        }

        override fun removeBlockedDomain(domain: String): Boolean {
            val normalized = com.nullroute.utils.DomainNormalizer.normalize(domain) ?: return false
            if (initial.contains(normalized)) return false
            return custom.remove(normalized)
        }
    }

    @Before
    fun setUp() {
        mockRepository = MockBlocklistRepository()
        viewModel = MainViewModel(mockRepository)
    }

    @Test
    fun testInitialDataLoaded() {
        assertEquals(mockRepository.initial, viewModel.initialDomains.value)
        assertTrue(viewModel.customDomains.value.isEmpty())
    }

    @Test
    fun testAddCustomDomain() {
        assertTrue(viewModel.addDomain("instagram.com"))
        assertTrue(viewModel.customDomains.value.contains("instagram.com"))
        assertEquals(1, viewModel.customDomains.value.size)
    }

    @Test
    fun testAddDuplicateDomain() {
        assertTrue(viewModel.addDomain("instagram.com"))
        assertFalse(viewModel.addDomain("instagram.com")) // Duplicate custom domain
        assertEquals(1, viewModel.customDomains.value.size)
    }

    @Test
    fun testAddInitialDuplicateDomain() {
        assertFalse(viewModel.addDomain("facebook.com")) // Already present in initial set
        assertTrue(viewModel.customDomains.value.isEmpty())
    }

    @Test
    fun testRemoveCustomDomain() {
        assertTrue(viewModel.addDomain("instagram.com"))
        assertTrue(viewModel.removeDomain("instagram.com"))
        assertFalse(viewModel.customDomains.value.contains("instagram.com"))
        assertTrue(viewModel.customDomains.value.isEmpty())
    }

    @Test
    fun testRemoveInitialDomainFails() {
        assertFalse(viewModel.removeDomain("facebook.com")) // Initial domain cannot be removed
    }

    @Test
    fun testRemoveNonExistentDomainFails() {
        assertFalse(viewModel.removeDomain("twitter.com"))
    }
}
