package com.lightwraith8268.libraryiq.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) : PurchasesUpdatedListener {

    companion object {
        const val PRODUCT_ID = "sync_monthly"
        private val ADMIN_EMAILS = setOf(
            "padraig.antrobus@gmail.com"
        )
    }

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private val _isAdmin = MutableStateFlow(false)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _productDetails = MutableStateFlow<ProductDetails?>(null)
    val productDetails: StateFlow<ProductDetails?> = _productDetails.asStateFlow()

    private val _billingReady = MutableStateFlow(false)

    private var billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    val hasProAccess: Boolean
        get() = _isSubscribed.value || _isAdmin.value

    init {
        connectBillingClient()
    }

    fun refreshAdminStatus() {
        val email = try {
            FirebaseAuth.getInstance().currentUser?.email?.lowercase()
        } catch (_: Exception) {
            null
        }
        _isAdmin.value = email != null && email in ADMIN_EMAILS
    }

    private fun connectBillingClient() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    _billingReady.value = true
                    querySubscription()
                    queryProductDetails()
                }
            }

            override fun onBillingServiceDisconnected() {
                _billingReady.value = false
            }
        })
    }

    private fun querySubscription() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                val hasActive = purchases.any { purchase ->
                    purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                        purchase.products.contains(PRODUCT_ID)
                }
                _isSubscribed.value = hasActive
            }
        }
    }

    private fun queryProductDetails() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                _productDetails.value = productDetailsList.firstOrNull()
            }
        }
    }

    fun launchSubscription(activity: Activity): BillingResult? {
        val details = _productDetails.value ?: return null

        val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken ?: return null

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        return billingClient.launchBillingFlow(activity, billingFlowParams)
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            val hasActive = purchases.any { purchase ->
                purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                    purchase.products.contains(PRODUCT_ID)
            }
            if (hasActive) {
                _isSubscribed.value = true
            }
        }
    }

    fun refresh() {
        refreshAdminStatus()
        if (_billingReady.value) {
            querySubscription()
        } else {
            connectBillingClient()
        }
    }
}
