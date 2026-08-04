package com.nullroute

import com.nullroute.data.BlockedDomain
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
        val domains = mutableListOf<BlockedDomain>()

        override fun getBlockedDomains(): List<BlockedDomain> = domains

        override fun getBlockedDomainStrings(): Set<String> {
            return domains.map { it.domain }.toSet()
        }

        override fun addBlockedDomain(domain: String, isRemovable: Boolean): Boolean {
            val normalized = com.nullroute.utils.DomainNormalizer.normalize(domain) ?: return false
            if (getBlockedDomainStrings().contains(normalized)) return false
            return domains.add(BlockedDomain(normalized, isRemovable = isRemovable))
        }

        override fun removeBlockedDomain(domain: String): Boolean {
            val normalized = com.nullroute.utils.DomainNormalizer.normalize(domain) ?: return false
            val item = domains.find { it.domain == normalized } ?: return false
            if (!item.isRemovable) return false
            return domains.remove(item)
        }
    }

    @Before
    fun setUp() {
        mockRepository = MockBlocklistRepository()
        viewModel = MainViewModel(mockRepository)
    }

    @Test
    fun testInitialDataIsEmpty() {
        assertTrue(viewModel.blockedDomains.value.isEmpty())
    }

    @Test
    fun testAddDomainRemovable() {
        assertTrue(viewModel.addDomain("instagram.com", isRemovable = true))
        assertEquals(1, viewModel.blockedDomains.value.size)
        assertTrue(viewModel.blockedDomains.value[0].isRemovable)
    }

    @Test
    fun testAddDomainPermanent() {
        assertTrue(viewModel.addDomain("instagram.com", isRemovable = false))
        assertEquals(1, viewModel.blockedDomains.value.size)
        assertFalse(viewModel.blockedDomains.value[0].isRemovable)
        assertFalse(viewModel.removeDomain("instagram.com"))
        assertEquals(1, viewModel.blockedDomains.value.size)
    }

    @Test
    fun testAddDuplicateDomain() {
        assertTrue(viewModel.addDomain("instagram.com", isRemovable = true))
        assertFalse(viewModel.addDomain("instagram.com", isRemovable = true))
        assertEquals(1, viewModel.blockedDomains.value.size)
    }

    @Test
    fun testRemoveRemovableDomain() {
        assertTrue(viewModel.addDomain("instagram.com", isRemovable = true))
        assertTrue(viewModel.removeDomain("instagram.com"))
        assertTrue(viewModel.blockedDomains.value.isEmpty())
    }

    @Test
    fun testRemoveNonExistentDomainFails() {
        assertFalse(viewModel.removeDomain("twitter.com"))
    }
}
