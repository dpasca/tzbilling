// See LICENSE for full license text.

package com.turbulenz.turbulenz;

import android.app.Activity;
import android.content.Intent;
import android.text.TextUtils;
import android.util.Log;

import com.android.billingclient.api.*;

import org.json.JSONException;
import org.json.JSONObject;

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

        _log("Purchase succeeded");
        sendPurchaseResult(mPurchaseContext, purchase.getSkus().get(0), purchase.getOriginalJson(),
                purchase.getPurchaseToken(), purchase.getDeveloperPayload(), purchase.getSignature());
    }

    @Override
    public boolean handleActivityResult(int requestCode, int resultCode, Intent data)
    {
        // This method is not used with BillingClient, but we need to implement it
        // as it's abstract in the parent class
        return false;
    }

    @Override
    public boolean doPurchase(final String sku, final String devPayload, final long context)
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

        List<String> skuList = new ArrayList<>();
        skuList.add(sku);
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        params.setSkusList(skuList).setType(BillingClient.SkuType.INAPP);

        mBillingClient.querySkuDetailsAsync(params.build(),
                (billingResult, skuDetailsList) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _error("Failed to query SKU details: " + billingResult.getResponseCode());
                        sendPurchaseFailure(mPurchaseContext, "failed to create Android buy Intent");
                        return;
                    }

                    if (skuDetailsList == null || skuDetailsList.isEmpty()) {
                        _error("No SKU details found for " + sku);
                        sendPurchaseFailure(mPurchaseContext, "failed to create Android buy Intent");
                        return;
                    }

                    SkuDetails skuDetails = skuDetailsList.get(0);
                    BillingFlowParams flowParams = BillingFlowParams.newBuilder()
                            .setSkuDetails(skuDetails)
                            .setObfuscatedAccountId(devPayload)
                            .build();
                    BillingResult result = mBillingClient.launchBillingFlow(mActivity, flowParams);
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _error("Failed to launch billing flow: " + result.getResponseCode());
                        sendPurchaseFailure(mPurchaseContext, "failed to launch billing flow");
                        mPurchaseContext = 0;
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
        mBillingClient.queryPurchasesAsync(BillingClient.SkuType.INAPP,
                (billingResult, purchases) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _error("doQueryPurchases: error retrieving purchased SKUs");
                        sendPurchaseInfoError(context, "error getting purchase data");
                        return;
                    }

                    for (Purchase purchase : purchases) {
                        try {
                            JSONObject o = new JSONObject(purchase.getOriginalJson());
                            String googleToken = o.optString("token", o.optString("purchaseToken"));
                            String devPayload = o.optString("developerPayload");

                            _print(" - " + purchase.getSkus().get(0));
                            _log("   - (data:" + purchase.getOriginalJson() + ", sig: " + purchase.getSignature() + ")");

                            sendPurchaseInfo(context, purchase.getSkus().get(0), purchase.getOriginalJson(), googleToken,
                                    devPayload, purchase.getSignature());
                        } catch (JSONException e) {
                            _error("threadQueryPurchases: bad JSON: " + purchase.getOriginalJson());
                            sendPurchaseInfoError(context, "error in purchase data");
                            return;
                        }
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

        List<String> skuList = new ArrayList<>();
        skuList.add(sku);
        SkuDetailsParams.Builder params = SkuDetailsParams.newBuilder();
        params.setSkusList(skuList).setType(BillingClient.SkuType.INAPP);

        mBillingClient.querySkuDetailsAsync(params.build(),
                (billingResult, skuDetailsList) -> {
                    if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        _log("threadQueryProduct: bad response from getSkuDetails: " + billingResult.getResponseCode());
                        sendProductInfoError(context, sku);
                        return;
                    }

                    if (skuDetailsList == null || skuDetailsList.isEmpty()) {
                        _log("threadQueryProduct: bundle doesn't contain list");
                        sendProductInfoError(context, sku);
                        return;
                    }

                    SkuDetails skuDetails = skuDetailsList.get(0);
                    sendProductInfo(context, sku, skuDetails.getTitle(), skuDetails.getDescription(), skuDetails.getPrice());
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
