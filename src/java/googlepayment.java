// See LICENSE for full license text.

package com.turbulenz.turbulenz;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.android.billingclient.api.*;

import java.util.ArrayList;
import java.util.List;

public class googlepayment extends payment.BillingAgent implements PurchasesUpdatedListener
{
    // Logging
    static private void _log(String msg)
    {
        Log.i("tzbilling(google)", msg);
    }
    static private void _print(String msg)
    {
        Log.i("tzbilling(google)", msg);
    }
    static private void _error(String msg)
    {
        Log.e("tzbilling(google)", msg);
    }

    private Activity mActivity;
    private BillingClient mBillingClient;
    private int mPurchaseRequestCode;
    private long mPurchaseContext = 0;
    private boolean mPendingConsumableFlag;

    private static String getFirstProductId(Purchase purchase)
    {
        List<String> products = purchase.getProducts();
        if (products == null || products.isEmpty()) {
            return "";
        }
        return products.get(0);
    }

    private static String getObfuscatedAccountId(Purchase purchase)
    {
        Purchase.AccountIdentifiers ids = purchase.getAccountIdentifiers();
        if (ids == null) {
            return null;
        }
        return ids.getObfuscatedAccountId();
    }

    public googlepayment(Activity activity, int purchaseRequestCode)
    {
        mActivity = activity;
        mPurchaseRequestCode = purchaseRequestCode;

        mBillingClient = BillingClient.newBuilder(mActivity)
                .setListener(this)
                .enablePendingPurchases()
                .build();

        _log("connecting to billing service...");
        mBillingClient.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult billingResult) {
                if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    _log("Billing service connected");
                    mIsReady = true;
                    reportReady(true);
                } else {
                    _log("Billing service setup failed with response code: " + billingResult.getResponseCode());
                    reportReady(false);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                _log("Billing service disconnected");
                mIsReady = false;
                reportReady(false);
            }
        });
    }

    @Override
    public void shutdown()
    {
        _log("shutting down...");
        mIsReady = false;
        if (mBillingClient != null && mBillingClient.isReady()) {
            mBillingClient.endConnection();
        }
        mActivity = null;
        _log("done shutting down.");
    }

    @Override
    public void onPurchasesUpdated(BillingResult billingResult, List<Purchase> purchases) {
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (Purchase purchase : purchases) {
                handlePurchase(purchase);
            }
        } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
            _log("User canceled the purchase");
            sendPurchaseFailure(mPurchaseContext, null);
        } else {
            _error("Purchase failed with response code: " + billingResult.getResponseCode());
            sendPurchaseFailure(mPurchaseContext, "Purchase did not complete");
        }
        mPurchaseContext = 0;
    }

    private void handlePurchase(Purchase purchase) {
        _log("handlePurchase: " + purchase.getPurchaseToken());
        if (!verifyPurchase(purchase.getOriginalJson(), purchase.getSignature())) {
            _log("Invalid signature");
            sendPurchaseFailure(mPurchaseContext, "invalid signature");
            return;
        }

        if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
            if (mPendingConsumableFlag) {
                // For consumable items, consume immediately
                doConsume(purchase.getPurchaseToken());
            } else {
                // Acknowledge the purchase if it hasn't been acknowledged yet.
                if (!purchase.isAcknowledged()) {
                    AcknowledgePurchaseParams acknowledgePurchaseParams =
                            AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.getPurchaseToken())
                                    .build();
                    mBillingClient.acknowledgePurchase(acknowledgePurchaseParams, billingResult -> {
                        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                            _log("Purchase acknowledged");
                        } else {
                            _error("Failed to acknowledge purchase: " + billingResult.getResponseCode());
                        }
                    });
                }
            }
        }

        String sku = getFirstProductId(purchase);
        String devPayload = getObfuscatedAccountId(purchase);

        _log("Purchase succeeded");
        sendPurchaseResult(mPurchaseContext, sku, purchase.getOriginalJson(),
                purchase.getPurchaseToken(), devPayload, purchase.getSignature());
    }

    @Override
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data)
    {
        // This method is not used with BillingClient, but we need to implement it
        // as it's abstract in the parent class
        return false;
    }

    @Override
    public boolean doPurchase(final String sku, final String devPayload, final boolean isConsumable, final long context)
    {
        _print("doPurchase: " + sku);
        if (!mIsReady) {
            _error("doPurchase: not ready. leaving.");
            return false;
        }

        if (0 != mPurchaseContext) {
            _error("doPurchase: !! purchase in progress (internal err)");
            return false;
        }

        mPurchaseContext = context;

        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        QueryProductDetailsParams queryParams = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        mBillingClient.queryProductDetailsAsync(queryParams,
                (billingResult, productDetailsList) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _error("Failed to query product details: " + billingResult.getResponseCode());
                        sendPurchaseFailure(mPurchaseContext, "failed to create Android buy Intent");
                        mPurchaseContext = 0;
                        return;
                    }

                    if (productDetailsList == null || productDetailsList.isEmpty()) {
                        _error("No product details found for " + sku);
                        sendPurchaseFailure(mPurchaseContext, "failed to create Android buy Intent");
                        mPurchaseContext = 0;
                        return;
                    }

                    ProductDetails productDetails = productDetailsList.get(0);
                    BillingFlowParams.ProductDetailsParams detailsParams =
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(productDetails)
                                    .build();

                    BillingFlowParams.Builder flowParamsBuilder = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(java.util.Collections.singletonList(detailsParams));
                    if (!TextUtils.isEmpty(devPayload)) {
                        flowParamsBuilder.setObfuscatedAccountId(devPayload);
                    }
                    BillingFlowParams flowParams = flowParamsBuilder.build();
                    BillingResult result = mBillingClient.launchBillingFlow(mActivity, flowParams);
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _error("Failed to launch billing flow: " + result.getResponseCode());
                        sendPurchaseFailure(mPurchaseContext, "failed to launch billing flow");
                        mPurchaseContext = 0;
                    }
                    else {
                        _log("Purchase started");
                        // Store the consumable so that we know if we have to acknowledge it later
                        mPendingConsumableFlag = isConsumable;
                    }
                });
        return true;
    }

    @Override
    public boolean doQueryPurchases(final long context)
    {
        if (!mIsReady) {
            _error("doQueryPurchases: not ready. leaving.");
            return false;
        }

        _log("doQueryPurchases: ");
        QueryPurchasesParams queryParams = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP)
                .build();

        mBillingClient.queryPurchasesAsync(queryParams,
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _error("doQueryPurchases: error retrieving purchased SKUs");
                        sendPurchaseInfoError(context, "error getting purchase data");
                        return;
                    }

                    for (Purchase purchase : purchases) {
                        String sku = getFirstProductId(purchase);
                        if (TextUtils.isEmpty(sku)) {
                            _error("doQueryPurchases: empty product list");
                            sendPurchaseInfoError(context, "error in purchase data");
                            return;
                        }

                        String devPayload = getObfuscatedAccountId(purchase);
                        _print(" - " + sku);
                        _log("   - (data:" + purchase.getOriginalJson() + ", sig: " + purchase.getSignature() + ")");

                        sendPurchaseInfo(context, sku, purchase.getOriginalJson(), purchase.getPurchaseToken(),
                                devPayload, purchase.getSignature());
                    }
                    sendPurchaseInfoTerminator(context);
                });
        return true;
    }

    @Override
    public boolean doQueryProduct(final String sku, final long context)
    {
        if (!mIsReady) {
            _error("doQueryProduct: not ready. leaving.");
            return false;
        }

        _log("doQueryProduct: " + sku);

        List<QueryProductDetailsParams.Product> productList = new ArrayList<>();
        productList.add(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(sku)
                .setProductType(BillingClient.ProductType.INAPP)
                .build());

        QueryProductDetailsParams queryParams = QueryProductDetailsParams.newBuilder()
                .setProductList(productList)
                .build();

        mBillingClient.queryProductDetailsAsync(queryParams,
                (billingResult, productDetailsList) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _log("threadQueryProduct: bad response from getProductDetails: " + billingResult.getResponseCode());
                        sendProductInfoError(context, sku);
                        return;
                    }

                    if (productDetailsList == null || productDetailsList.isEmpty()) {
                        _log("threadQueryProduct: bundle doesn't contain list");
                        sendProductInfoError(context, sku);
                        return;
                    }

                    ProductDetails productDetails = productDetailsList.get(0);
                    ProductDetails.OneTimePurchaseOfferDetails offerDetails =
                            productDetails.getOneTimePurchaseOfferDetails();
                    if (offerDetails == null) {
                        _log("threadQueryProduct: missing one-time purchase offer details");
                        sendProductInfoError(context, sku);
                        return;
                    }
                    sendProductInfo(context, sku, productDetails.getTitle(),
                            productDetails.getDescription(), offerDetails.getFormattedPrice());
                });
        return true;
    }

    @Override
    public boolean doConsume(final String token)
    {
        if (!mIsReady) {
            _error("doConsume: !! not ready. leaving.");
            return false;
        }

        if (TextUtils.isEmpty(token)) {
            _error("doConsume: !! null or empty token");
            return false;
        }

        _print("doConsume: token: " + token);
        ConsumeParams consumeParams = ConsumeParams.newBuilder()
                .setPurchaseToken(token)
                .build();

        mBillingClient.consumeAsync(consumeParams,
                (billingResult, purchaseToken) -> {
                    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        _log("doConsume: successfully consumed");
                    } else {
                        _error("doConsume: failed to consume. response: " + billingResult.getResponseCode());
                    }
                });
        return true;
    }

    private boolean verifyPurchase(String data, String sig)
    {
        // A VERY BIG TODO:
        // _error("verifyPurchase: !! NO CLIENT SIDE PURCHASE VERIFICATION !!");
        return true;
    }
}
