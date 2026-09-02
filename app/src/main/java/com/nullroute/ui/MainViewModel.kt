package com.nullroute.ui

import android.app.Activity
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nullroute.accessibility.BlockerAccessibilityService
import com.nullroute.billing.BillingManager
import com.nullroute.billing.BillingProvider
import com.nullroute.data.BlockedDomain
import com.nullroute.data.BlocklistRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: BlocklistRepository,
    private val billingManager: BillingProvider
) : ViewModel() {

    companion object {
        const val FREE_DOMAIN_LIMIT = 2
    }

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _isAccessibilityActive = MutableStateFlow(false)
    val isAccessibilityActive: StateFlow<Boolean> = _isAccessibilityActive.asStateFlow()

    private val _isPermanentLockActive = MutableStateFlow(false)
    val isPermanentLockActive: StateFlow<Boolean> = _isPermanentLockActive.asStateFlow()

    private val _isBypassProtectionActive = MutableStateFlow(false)
    val isBypassProtectionActive: StateFlow<Boolean> = _isBypassProtectionActive.asStateFlow()

    private val _blockedDomains = MutableStateFlow<List<BlockedDomain>>(emptyList())
    val blockedDomains: StateFlow<List<BlockedDomain>> = _blockedDomains.asStateFlow()

    // Billing integration
    val isPro: StateFlow<Boolean> = billingManager.isPro
    val proPrice: StateFlow<String> = billingManager.proPrice
    val billingMessage: SharedFlow<String> = billingManager.userMessage

    val telemetrySnapshot: StateFlow<com.nullroute.vpn.TelemetrySnapshot> = com.nullroute.vpn.DnsTelemetryTracker.snapshot

    fun updateTelemetry() {
        com.nullroute.vpn.DnsTelemetryTracker.updateSnapshot()
    }

    fun getExportTelemetryReport(): String {
        return com.nullroute.vpn.DnsTelemetryTracker.generateExportReport()
    }

    init {
        loadData()
        viewModelScope.launch(Dispatchers.Default) {
            billingManager.isPro.collect {
                loadData()
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            com.nullroute.vpn.VpnStateTracker.isRunning.collect { running ->
                _isVpnActive.value = running
            }
        }
    }

    fun loadData() {
        val allDomains = repository.getBlockedDomains()
        _blockedDomains.value = if (isPro.value) allDomains else allDomains.take(FREE_DOMAIN_LIMIT)
    }

    fun refreshStates(context: Context) {
        _isVpnActive.value = com.nullroute.vpn.VpnStateTracker.isRunning.value
        _isAccessibilityActive.value = isAccessibilityEnabled(context)
        
        val prefs = context.getSharedPreferences("nullroute_prefs", Context.MODE_PRIVATE)
        _isPermanentLockActive.value = prefs.getBoolean("permanent_lock", false)
        _isBypassProtectionActive.value = prefs.getBoolean("bypass_protection", false)

        billingManager.queryPurchases()
    }

    fun canAddDomain(): Boolean {
        return isPro.value || _blockedDomains.value.size < FREE_DOMAIN_LIMIT
    }

    fun canUsePermanentLock(): Boolean {
        return isPro.value
    }

    fun addDomain(domain: String, isRemovable: Boolean = true): Boolean {
        val success = repository.addBlockedDomain(domain, isRemovable)
        if (success) {
            loadData()
        }
        return success
    }

    fun addPresetDomains(domains: List<String>): Int {
        if (!isPro.value) {
            return -1 // Pro required
        }
        val added = repository.addBlockedDomains(domains, isRemovable = true)
        if (added > 0) {
            loadData()
        }
        return added
    }

    fun removeDomain(domain: String): Boolean {
        val success = repository.removeBlockedDomain(domain)
        if (success) {
            loadData()
        }
        return success
    }

    fun freezeLock(context: Context) {
        val prefs = context.getSharedPreferences("nullroute_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("permanent_lock", true).apply()
        _isPermanentLockActive.value = true
    }

    fun setBypassProtection(context: Context, bypass: Boolean) {
        val prefs = context.getSharedPreferences("nullroute_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("bypass_protection", bypass).apply()
        _isBypassProtectionActive.value = bypass
    }

    // Billing actions
    fun launchPurchaseFlow(activity: Activity) {
        billingManager.launchPurchaseFlow(activity)
    }

    fun restorePurchases() {
        billingManager.restorePurchases()
    }

    fun debugTogglePro(): Boolean {
        return billingManager.debugTogglePro()
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val serviceName = context.packageName + "/" + BlockerAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }
}

class MainViewModelFactory(
    private val repository: BlocklistRepository,
    private val billingProvider: BillingProvider
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, billingProvider) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
