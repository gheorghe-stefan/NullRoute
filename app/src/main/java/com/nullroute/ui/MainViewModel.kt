package com.nullroute.ui

import android.content.Context
import android.net.VpnService
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nullroute.accessibility.BlockerAccessibilityService
import com.nullroute.data.BlocklistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainViewModel(private val repository: BlocklistRepository) : ViewModel() {

    private val _isVpnActive = MutableStateFlow(false)
    val isVpnActive: StateFlow<Boolean> = _isVpnActive.asStateFlow()

    private val _isAccessibilityActive = MutableStateFlow(false)
    val isAccessibilityActive: StateFlow<Boolean> = _isAccessibilityActive.asStateFlow()

    private val _isPermanentLockActive = MutableStateFlow(false)
    val isPermanentLockActive: StateFlow<Boolean> = _isPermanentLockActive.asStateFlow()

    private val _isBypassProtectionActive = MutableStateFlow(false)
    val isBypassProtectionActive: StateFlow<Boolean> = _isBypassProtectionActive.asStateFlow()

    private val _initialDomains = MutableStateFlow<Set<String>>(emptySet())
    val initialDomains: StateFlow<Set<String>> = _initialDomains.asStateFlow()

    private val _customDomains = MutableStateFlow<Set<String>>(emptySet())
    val customDomains: StateFlow<Set<String>> = _customDomains.asStateFlow()

    init {
        loadData()
        viewModelScope.launch(Dispatchers.Default) {
            com.nullroute.vpn.VpnStateTracker.isRunning.collect { running ->
                _isVpnActive.value = running
            }
        }
    }

    fun loadData() {
        _initialDomains.value = repository.getInitialBlockedDomains()
        _customDomains.value = repository.getCustomBlockedDomains()
    }

    fun refreshStates(context: Context) {
        _isVpnActive.value = com.nullroute.vpn.VpnStateTracker.isRunning.value
        _isAccessibilityActive.value = isAccessibilityEnabled(context)
        
        val prefs = context.getSharedPreferences("nullroute_prefs", Context.MODE_PRIVATE)
        _isPermanentLockActive.value = prefs.getBoolean("permanent_lock", false)
        _isBypassProtectionActive.value = prefs.getBoolean("bypass_protection", false)
    }

    fun addDomain(domain: String): Boolean {
        if (_isPermanentLockActive.value) return false
        
        val success = repository.addBlockedDomain(domain)
        if (success) {
            _customDomains.value = repository.getCustomBlockedDomains()
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

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val serviceName = context.packageName + "/" + BlockerAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(serviceName)
    }
}

class MainViewModelFactory(private val repository: BlocklistRepository) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
