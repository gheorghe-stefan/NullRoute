package com.nullroute.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*
import com.nullroute.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface BillingProvider {
    val isPro: StateFlow<Boolean>
    val proPrice: StateFlow<String>
    val userMessage: SharedFlow<String>
    fun queryPurchases()
    fun launchPurchaseFlow(activity: Activity)
    fun restorePurchases()
    fun debugTogglePro(): Boolean
}

class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope
) : BillingProvider, PurchasesUpdatedListener {

    companion object {
        private const val TAG = "NullRouteBilling"
        const val PRODUCT_ID_PRO = "nullroute_pro_lifetime"
        private const val PREFS_NAME = "nullroute_prefs"
        private const val PREF_KEY_PRO_UNLOCKED = "is_pro_unlocked"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isPro = MutableStateFlow(prefs.getBoolean(PREF_KEY_PRO_UNLOCKED, false))
    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _proPrice = MutableStateFlow("$0.99")
    override val proPrice: StateFlow<String> = _proPrice.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    override val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var productDetails: ProductDetails? = null

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    init {
        startBillingConnection()
    }

    fun startBillingConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing client setup successful.")
                    queryPurchases()
                    queryProductDetails()
                } else {
                    Log.w(TAG, "Billing setup failed: ${billingResult.debugMessage} (code ${billingResult.responseCode})")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected. Will retry connection on demand.")
            }
        })
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_PRO)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, queryProductDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val details = queryProductDetailsList.firstOrNull { it.productId == PRODUCT_ID_PRO }
                if (details != null) {
                    productDetails = details
                    val formattedPrice = details.oneTimePurchaseOfferDetails?.formattedPrice
                    if (!formattedPrice.isNullOrBlank()) {
                        _proPrice.value = formattedPrice
                        Log.d(TAG, "Retrieved Play Store price: $formattedPrice")
                    }
                } else {
                    Log.d(TAG, "Product $PRODUCT_ID_PRO not yet configured in Play Console.")
                }
            } else {
                Log.w(TAG, "Failed to query product details: ${billingResult.debugMessage}")
            }
        }
    }

    override fun queryPurchases() {
        if (!billingClient.isReady) {
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                for (purchase in purchases) {
                    if (purchase.products.contains(PRODUCT_ID_PRO) &&
                        purchase.purchaseState == Purchase.PurchaseState.PURCHASED
                    ) {
                        handlePurchase(purchase)
                    }
                }
            }
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases != null) {
                    for (purchase in purchases) {
                        handlePurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                emitMessage("Purchase cancelled.")
            }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                setProStatus(true)
                emitMessage("NullRoute Pro is already owned! Unlocked.")
            }
            else -> {
                emitMessage("Purchase failed: ${billingResult.debugMessage}")
            }
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            setProStatus(true)
            emitMessage("🎉 Thank you! NullRoute Pro is now unlocked.")

            // Acknowledge the purchase if not already acknowledged
            if (!purchase.isAcknowledged) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()

                billingClient.acknowledgePurchase(acknowledgePurchaseParams) { ackResult ->
                    if (ackResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        Log.d(TAG, "Purchase successfully acknowledged.")
                    } else {
                        Log.w(TAG, "Failed to acknowledge purchase: ${ackResult.debugMessage}")
                    }
                }
            }
        }
    }

    override fun launchPurchaseFlow(activity: Activity) {
        val details = productDetails
        if (details == null) {
            // Google Play product is not active yet in the Play Console
            emitMessage("Google Play product is not available yet. Please configure it in Google Play Console.")
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val responseCode = billingClient.launchBillingFlow(activity, billingFlowParams).responseCode
        if (responseCode != BillingClient.BillingResponseCode.OK) {
            emitMessage("Could not launch Google Play billing flow ($responseCode).")
        }
    }

    override fun restorePurchases() {
        if (!billingClient.isReady) {
            startBillingConnection()
            emitMessage("Connecting to Google Play Store...")
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasPro = purchases.any {
                    it.products.contains(PRODUCT_ID_PRO) && it.purchaseState == Purchase.PurchaseState.PURCHASED
                }
                if (hasPro) {
                    setProStatus(true)
                    emitMessage("Purchases restored! NullRoute Pro is active.")
                } else {
                    emitMessage("No previous NullRoute Pro purchase found.")
                }
            } else {
                emitMessage("Failed to query purchases from Google Play.")
            }
        }
    }

    private fun setProStatus(unlocked: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_PRO_UNLOCKED, unlocked).apply()
        _isPro.value = unlocked
    }

    private fun emitMessage(msg: String) {
        scope.launch {
            _userMessage.emit(msg)
        }
    }

    // Debug Mock Bypass: Allows instant testing without Google Play Console setup
    override fun debugTogglePro(): Boolean {
        if (BuildConfig.DEBUG) {
            val newState = !_isPro.value
            setProStatus(newState)
            emitMessage(if (newState) "[Debug] NullRoute Pro UNLOCKED!" else "[Debug] NullRoute Pro LOCKED (Free Tier)")
            return newState
        }
        return _isPro.value
    }

    fun endConnection() {
        billingClient.endConnection()
    }
}
