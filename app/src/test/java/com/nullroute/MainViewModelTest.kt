package com.nullroute

import android.app.Activity
import com.nullroute.billing.BillingProvider
import com.nullroute.data.BlockedDomain
import com.nullroute.data.BlocklistRepository
import com.nullroute.ui.MainViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MainViewModelTest {

    private lateinit var mockRepository: MockBlocklistRepository
    private lateinit var mockBillingProvider: MockBillingProvider
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

        override fun addBlockedDomains(domains: List<String>, isRemovable: Boolean): Int {
            var count = 0
            for (d in domains) {
                if (addBlockedDomain(d, isRemovable)) count++
            }
            return count
        }

        override fun removeBlockedDomain(domain: String, force: Boolean): Boolean {
            val normalized = com.nullroute.utils.DomainNormalizer.normalize(domain) ?: return false
            val item = domains.find { it.domain == normalized } ?: return false
            if (!item.isRemovable && !force) return false
            return domains.remove(item)
        }
    }

    class MockBillingProvider(initialPro: Boolean = false) : BillingProvider {
        private val _isPro = MutableStateFlow(initialPro)
        override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

        private val _proPrice = MutableStateFlow("$0.99")
        override val proPrice: StateFlow<String> = _proPrice.asStateFlow()

        private val _userMessage = MutableSharedFlow<String>()
        override val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

        override fun queryPurchases() {}
        override fun launchPurchaseFlow(activity: Activity) {}
        override fun restorePurchases() {}
        override fun debugTogglePro(): Boolean {
            val newState = !_isPro.value
            _isPro.value = newState
            return newState
        }

        fun setPro(unlocked: Boolean) {
            _isPro.value = unlocked
        }
    }

    @Before
    fun setUp() {
        mockRepository = MockBlocklistRepository()
        mockBillingProvider = MockBillingProvider(initialPro = false)
        viewModel = MainViewModel(mockRepository, mockBillingProvider)
    }

    @Test
    fun testInitialDataIsEmpty() {
        assertTrue(viewModel.blockedDomains.value.isEmpty())
        assertFalse(viewModel.isPro.value)
    }

    @Test
    fun testFreeTierDomainLimit() {
        // Limit is 2 domains for free tier
        assertTrue(viewModel.canAddDomain())
        viewModel.addDomain("domain1.com", isRemovable = true)
        assertEquals(1, viewModel.blockedDomains.value.size)
        assertTrue(viewModel.canAddDomain())

        viewModel.addDomain("domain2.com", isRemovable = true)
        assertEquals(2, viewModel.blockedDomains.value.size)
        // Now free limit (2) is reached
        assertFalse(viewModel.canAddDomain())
    }

    @Test
    fun testProTierUnlimitedDomains() {
        mockBillingProvider.setPro(true)
        assertTrue(viewModel.isPro.value)

        viewModel.addDomain("domain1.com")
        viewModel.addDomain("domain2.com")
        // Beyond 2 domains, Pro users can still add domains
        assertTrue(viewModel.canAddDomain())
        viewModel.addDomain("domain3.com")
        assertTrue(viewModel.canAddDomain())
        assertEquals(3, viewModel.blockedDomains.value.size)
    }

    @Test
    fun testPermanentLockGatedByPro() {
        // In free tier, permanent lock is disallowed
        assertFalse(viewModel.canUsePermanentLock())

        // In pro tier, permanent lock is allowed
        mockBillingProvider.setPro(true)
        assertTrue(viewModel.canUsePermanentLock())
    }

    @Test
    fun testPresetsGatedByPro() {
        val preset = listOf("social1.com", "social2.com")
        // Free tier returns -1 (blocked)
        val freeResult = viewModel.addPresetDomains(preset)
        assertEquals(-1, freeResult)
        assertEquals(0, viewModel.blockedDomains.value.size)

        // Pro tier allows adding preset
        mockBillingProvider.setPro(true)
        val proResult = viewModel.addPresetDomains(preset)
        assertEquals(2, proResult)
        assertEquals(2, viewModel.blockedDomains.value.size)
    }

    @Test
    fun testAddDomainRemovable() {
        assertTrue(viewModel.addDomain("instagram.com", isRemovable = true))
        assertEquals(1, viewModel.blockedDomains.value.size)
        assertTrue(viewModel.blockedDomains.value[0].isRemovable)
    }

    @Test
    fun testAddDomainPermanent() {
        mockBillingProvider.setPro(true)
        assertTrue(viewModel.addDomain("instagram.com", isRemovable = false))
        assertEquals(1, viewModel.blockedDomains.value.size)
        assertFalse(viewModel.blockedDomains.value[0].isRemovable)
        // In Pro mode, cannot remove permanent domain
        assertFalse(viewModel.removeDomain("instagram.com"))
        assertEquals(1, viewModel.blockedDomains.value.size)

        // In Free mode (downgrade/refund), user is allowed to remove it
        mockBillingProvider.setPro(false)
        assertTrue(viewModel.removeDomain("instagram.com"))
        assertEquals(0, viewModel.blockedDomains.value.size)
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

    @Test
    fun testDowngradeToFreeTierCapsActiveDomainsToTwo() {
        mockBillingProvider.setPro(true)
        viewModel.addDomain("site1.com")
        viewModel.addDomain("site2.com")
        viewModel.addDomain("site3.com")
        viewModel.addDomain("site4.com")
        assertEquals(4, viewModel.blockedDomains.value.size)

        // Downgrade to Free tier (e.g. refunded or revoked)
        mockBillingProvider.setPro(false)
        viewModel.loadData()

        // App only exposes the first 2 domains
        assertEquals(2, viewModel.blockedDomains.value.size)
        assertEquals("site1.com", viewModel.blockedDomains.value[0].domain)
        assertEquals("site2.com", viewModel.blockedDomains.value[1].domain)

        // Underlying repository retains full list
        assertEquals(4, mockRepository.domains.size)

        // Upgrade back to Pro restores full list
        mockBillingProvider.setPro(true)
        viewModel.loadData()
        assertEquals(4, viewModel.blockedDomains.value.size)
    }
}
