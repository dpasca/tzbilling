// Copyright (c) 2013 Turbulenz Limited
// See LICENSE for full license text.

package com.turbulenz.turbulenz;

import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

import android.util.Log;
import android.app.Activity;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.text.TextUtils;
import android.content.Context;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.content.IntentSender.SendIntentException;

import com.android.vending.billing.IInAppBillingService;

public class googlepayment extends payment.BillingAgent
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

    // ------------------------------------------------------------------
    //
    // Billing response codes
    public static final int BILLING_RESPONSE_RESULT_OK = 0;
    public static final int BILLING_RESPONSE_RESULT_USER_CANCELED = 1;
    public static final int BILLING_RESPONSE_RESULT_BILLING_UNAVAILABLE = 3;
    public static final int BILLING_RESPONSE_RESULT_ITEM_UNAVAILABLE = 4;
    public static final int BILLING_RESPONSE_RESULT_DEVELOPER_ERROR = 5;
    public static final int BILLING_RESPONSE_RESULT_ERROR = 6;
    public static final int BILLING_RESPONSE_RESULT_ITEM_ALREADY_OWNED = 7;
    public static final int BILLING_RESPONSE_RESULT_ITEM_NOT_OWNED = 8;

    // Keys for the responses from InAppBillingService
    public static final String RESPONSE_CODE = "RESPONSE_CODE";
    public static final String RESPONSE_GET_SKU_DETAILS_LIST = "DETAILS_LIST";
    public static final String RESPONSE_BUY_INTENT = "BUY_INTENT";
    public static final String RESPONSE_INAPP_PURCHASE_DATA =
        "INAPP_PURCHASE_DATA";
    public static final String RESPONSE_INAPP_SIGNATURE =
        "INAPP_DATA_SIGNATURE";
    public static final String RESPONSE_INAPP_ITEM_LIST =
        "INAPP_PURCHASE_ITEM_LIST";
    public static final String RESPONSE_INAPP_PURCHASE_DATA_LIST =
        "INAPP_PURCHASE_DATA_LIST";
    public static final String RESPONSE_INAPP_SIGNATURE_LIST =
        "INAPP_DATA_SIGNATURE_LIST";
    public static final String INAPP_CONTINUATION_TOKEN =
        "INAPP_CONTINUATION_TOKEN";

    public static final String ITEM_TYPE_INAPP = "inapp";
    //
    // ------------------------------------------------------------------

    Activity             mActivity = null;
    int                  mPurchaseRequestCode;

    ServiceConnection    mServiceConnection = null;
    IInAppBillingService mService = null;

    boolean              mReady = false;

    // If not zero, indicates that a purchase is already in progress
    long                 mPurchaseContext = 0;

    public googlepayment(Activity activity, int purchaseRequestCode)
    {
        mActivity = activity;
        mPurchaseRequestCode = purchaseRequestCode;

        // Just listens for connection / disconnection
        mServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name)
            {
                _log("service disconnected :(");
                mService = null;
                mReady = false;
                reportReady(false);
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service)
            {
                _log("service connected :)");
                mService = IInAppBillingService.Stub.asInterface(service);

                String packageName = mActivity.getPackageName();
                _log("checking for billing.3 in " + packageName + "...");
                try {
                    int response =
                        mService.isBillingSupported(3, packageName,
                                                    ITEM_TYPE_INAPP);
                    if (BILLING_RESPONSE_RESULT_OK == response) {
                        mReady = true;
                    } else {
                        _log("billing v3 not supported for this package");
                    }
                } catch (RemoteException e) {
                    _error("remoteexception:");
                    e.printStackTrace();
                }

                reportReady(mReady);
            }
        };

        _log("binding service ...");
        Intent i = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        i.setPackage("com.android.vending");

        boolean bound = activity.bindService(
            i,
            mServiceConnection,
            Context.BIND_AUTO_CREATE);
        _log("back from bindService: bound: " + Boolean.toString(bound));
    }

    //
    public void shutdown()
    {
        _log("shutting down ...");
        mReady = false;
        if (null != mServiceConnection) {
            _log("unbinding service");
            mActivity.unbindService(mServiceConnection);
            mServiceConnection = null;
            mService = null;
            _log("service unbound");
        }
        mActivity = null;
        _log("done shutting down.");
    }

    // Workaround to bug where sometimes response codes come as Long
    // instead of Integer
    static int getResponseCodeFromBundle(Bundle b)
    {
        Object o = b.get(RESPONSE_CODE);
        if (o == null) {
            _log("response code is null, assuming OK"); // known issue
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) {
            return ((Integer)o).intValue();
        }
        else if (o instanceof Long) {
            return (int)((Long)o).longValue();
        }
        else {
            _error("!! Unexpected type for bundle response code." +
                   o.getClass().getName());

            throw new RuntimeException("Unexpected type for bundle response code: "
                                       + o.getClass().getName());
        }
    }

    // Workaround to bug where sometimes response codes come as Long
    // instead of Integer
    static int getResponseCodeFromIntent(Intent i)
    {
        Object o = i.getExtras().get(RESPONSE_CODE);
        if (o == null) {
            _log("Intent with no response code, assuming OK (known issue)");
            return BILLING_RESPONSE_RESULT_OK;
        }
        else if (o instanceof Integer) {
            return ((Integer)o).intValue();
        }
        else if (o instanceof Long) {
            return (int)((Long)o).longValue();
        }
        else {
            _log("Unexpected type for intent response code.");
            _log(o.getClass().getName());
            throw new RuntimeException("Unexpected intent response code type: "
                                       + o.getClass().getName());
        }
    }

    // ------------------------------------------------------------------
    // doPurchase
    // ------------------------------------------------------------------

    //
    protected boolean verifyPurchase(String data, String sig)
    {
        // A VERY BIG TODO:
        // _error("verifyPurchase: !! NO CLIENT SIDE PURCHASE VERIFICATION !!");
        return true;
    }

    // Return value indicates whether or not we handled the Intent,
    // not whether the purchase succeeded.
    public boolean handleActivityResult(int requestCode, int resultCode,
                                        Intent data)
    {
        _log("handleActivityResult: requestCode: " + requestCode +
             " resultCode: " + resultCode);

        if (0 == mPurchaseContext) {
            _error("handleActivityResult: no purchase context registered");
            return true;
        }

        if (Activity.RESULT_CANCELED == resultCode)  {
            _log("handleActivityResult: cancelled");
            sendPurchaseFailure(mPurchaseContext, null);
            mPurchaseContext = 0;
            return true;
        }

        if (Activity.RESULT_OK != resultCode) {
            _log("onActivityResult: unknown result code");
            sendPurchaseFailure(mPurchaseContext, "Unknown GooglePlay failure");
            mPurchaseContext = 0;
            return true;
        }

        _log("handleActivityResult: resultCode was OK");

        int purchaseResponse = getResponseCodeFromIntent(data);
        if (BILLING_RESPONSE_RESULT_OK != purchaseResponse) {
            _log("onActivityResult: bad purchaseResponse: " + purchaseResponse);
            sendPurchaseFailure(mPurchaseContext, "Purchase did not complete");
            mPurchaseContext = 0;
            return true;
        }

        String purchaseData = data.getStringExtra(RESPONSE_INAPP_PURCHASE_DATA);
        String purchaseSig = data.getStringExtra(RESPONSE_INAPP_SIGNATURE);
        String purchaseGoogleToken;
        String purchaseDevPayload;

        _log("onActivityResult: purchaseResponse: OK" +
             ", purchaseData: " + purchaseData +
             ", purchaseSig: " + purchaseSig);

        if (null == purchaseData || null == purchaseSig) {
            _log("onActivityResult: bad purchase data");
            sendPurchaseFailure(mPurchaseContext, "bad purchase data");
            mPurchaseContext = 0;
            return true;
        }

        if (!verifyPurchase(purchaseData, purchaseSig)) {
            _log("onActivityResult: invalid signature");
            sendPurchaseFailure(mPurchaseContext, "invalid signature");
            mPurchaseContext = 0;
            return true;
        }

        // Extract the sku name from the purchase data

        String sku;
        String googleToken;
        String devPayload;
        try {
            JSONObject o = new JSONObject(purchaseData);
            sku = o.optString("productId");
            googleToken = o.optString("token", o.optString("purchaseToken"));
            devPayload = o.optString("developerPayload");
        } catch(JSONException e) {
            sendPurchaseFailure(mPurchaseContext,
                                "no sku data in GooglePlaye response");
            mPurchaseContext = 0;
            return true;
        }

        if (TextUtils.isEmpty(sku)) {
            sendPurchaseFailure(mPurchaseContext, "sku name was empty");
            mPurchaseContext = 0;
            return true;
        }

        _log("onActivityResult: purchase succeeded");
        sendPurchaseResult(mPurchaseContext, sku, purchaseData, googleToken,
                           devPayload, purchaseSig);
        mPurchaseContext = 0;
        return true;
    }

    //
    void uiThreadDoPurchase(String sku, String extraData)
    {
        _log("uiThreadDoPurchase: sku: " + sku);

        try {
            Bundle buyIntentBundle =
                mService.getBuyIntent(3, mActivity.getPackageName(),
                                      sku, ITEM_TYPE_INAPP, extraData);
            int response = getResponseCodeFromBundle(buyIntentBundle);
            if (response != BILLING_RESPONSE_RESULT_OK) {
                _log("uiThreadDoPurchase: Failed to create intent bundle, " +
                     "response: " + response);
                sendPurchaseFailure(mPurchaseContext,
                                    "failed to create Android buy Intent");
                return;
            }

            PendingIntent pendingIntent =
                buyIntentBundle.getParcelable(RESPONSE_BUY_INTENT);
            _log("uiThreadDoPurchase: launching buy intent for sku: " + sku +
                 ", with request code: " + mPurchaseRequestCode);

            mActivity.startIntentSenderForResult
                (pendingIntent.getIntentSender(),
                 mPurchaseRequestCode, new Intent(),
                 Integer.valueOf(0),  // flagsMask
                 Integer.valueOf(0),  // flagsValues
                 Integer.valueOf(0)); // extraFlags
        }
        catch (SendIntentException e) {
            _error("uiThreadDoPurchase: SendIntentException");
            e.printStackTrace();

            sendPurchaseFailure(mPurchaseContext, "failed to send intent");
        }
        catch (RemoteException e) {
            _error("uiThreadDoPurchase: RemoteException");
            e.printStackTrace();

            sendPurchaseFailure(mPurchaseContext, "RemoteException: " + e);
        }
    }

    //
    public boolean doPurchase(final String sku, final String devPayload,
                              long context)
    {
        _print("doPurchase: " + sku);
        if (!mReady) {
            _error("doPurchase: not ready.  leaving.");
            return false;
        }

        if (0 != mPurchaseContext) {
            _error("doPurchase: !! purchase in progress (internal err)");
            return false;
        }

        mPurchaseContext = context;
        mActivity.runOnUiThread(new Runnable() {
                @Override public void run() {
                    uiThreadDoPurchase(sku, devPayload);
                }
            });
        return true;
    }

    // ------------------------------------------------------------------
    // doQueryPurchases
    // ------------------------------------------------------------------

    void threadQueryPurchases(final long context)
    {
        String continueToken = null;
        do {

            Bundle ownedItems;
            try {
                ownedItems = mService.getPurchases
                    (3, mActivity.getPackageName(), ITEM_TYPE_INAPP,
                     continueToken);
            } catch (RemoteException e) {
                _error("threadQueryPurchases: remote exception: " + e);
                e.printStackTrace();
                sendPurchaseInfoError(context, "failed to communicate "
                                      + "with Google Play");
                return;
            }

            int response = getResponseCodeFromBundle(ownedItems);

            if (BILLING_RESPONSE_RESULT_OK != response) {
                _error("doQueryPurchases: !! error retrieving purchased SKUs");
                // TODO: Should we grab something fom saved data here?
                sendPurchaseInfoError(context, "error getting purchase data");
                return;
            }

            if (!ownedItems.containsKey(RESPONSE_INAPP_ITEM_LIST) ||
                !ownedItems.containsKey(RESPONSE_INAPP_PURCHASE_DATA_LIST) ||
                !ownedItems.containsKey(RESPONSE_INAPP_SIGNATURE_LIST)) {

                _error("doQueryPurchases: !! missign fields in response");
                sendPurchaseInfoError(context, "response missing some fields");
                return;
            }

            ArrayList<String> ownedSkus =
                ownedItems.getStringArrayList(RESPONSE_INAPP_ITEM_LIST);
            ArrayList<String> purchaseData =
                ownedItems.getStringArrayList(RESPONSE_INAPP_PURCHASE_DATA_LIST);
            ArrayList<String> signatureData =
                ownedItems.getStringArrayList(RESPONSE_INAPP_SIGNATURE_LIST);

            final int numSKUs = purchaseData.size();
            _print("doQueryPurchases: " + numSKUs + " SKUs:");
            for (int itemIdx = 0 ; itemIdx < numSKUs ; ++itemIdx) {

                final String sku = ownedSkus.get(itemIdx);
                final String data = purchaseData.get(itemIdx);
                final String sig = signatureData.get(itemIdx);

                try {
                    JSONObject o = new JSONObject(data);
                    //o.optString("productId");
                    final String googleToken =
                        o.optString("token", o.optString("purchaseToken"));
                    final String devPayload = o.optString("developerPayload");

                    _print(" - " + sku);
                    _log("   - (data:" + data + ", sig: " + sig + ")");

                    sendPurchaseInfo(context, sku, data, googleToken,
                                     devPayload, sig);

                } catch(JSONException e) {
                    _error("threadQueryPurchases: bad JSON: " + data);
                    sendPurchaseInfoError(context, "error in purchase data");
                    return;
                }
            }

            continueToken = ownedItems.getString(INAPP_CONTINUATION_TOKEN);
            _log("doQueryPurchases: got continue token: " + continueToken);

        } while(!TextUtils.isEmpty(continueToken));

        sendPurchaseInfoTerminator(context);
    }

    // Call back to native code with the details of each purchase,
    public boolean doQueryPurchases(final long context)
    {
        if (!mReady) {
            _error("doQueryPurchases: not ready.  leaving.");
            return false;
        }

        _log("doQueryPurchases: ");

        (new Thread(new Runnable() {
                public void run() {
                    threadQueryPurchases(context);
                }
            })).start();

        _log("doQueryPurchases: launched thread");
        return true;
    }

    // ------------------------------------------------------------------
    // doQueryProduct
    // ------------------------------------------------------------------

    void threadQueryProduct(final String sku, final long context)
    {
        ArrayList<String> skuList = new ArrayList<String>();
        skuList.add(sku);
        Bundle productQueryBundle = new Bundle();
        productQueryBundle.putStringArrayList("ITEM_ID_LIST", skuList);

        Bundle skuDetails;
        try {
            skuDetails = mService.getSkuDetails
                (3, mActivity.getPackageName(), ITEM_TYPE_INAPP,
                 productQueryBundle);
        } catch (RemoteException e) {
            _error("threadQueryProduct: remote exception: " + e);
            e.printStackTrace();
            sendProductInfoError(context, sku);
            return;
        }

        int response = getResponseCodeFromBundle(skuDetails);
        if (BILLING_RESPONSE_RESULT_OK != response) {
            _log("threadQueryProduct: bad response from getSkuDetails: " +
                 response);
            sendProductInfoError(context, sku);
            return;
        }

        if (!skuDetails.containsKey(RESPONSE_GET_SKU_DETAILS_LIST)) {
            _log("threadQueryProduct: bundle doens't contain list");
            sendProductInfoError(context, sku);
            return;
        }

        ArrayList<String> responseList =
            skuDetails.getStringArrayList(RESPONSE_GET_SKU_DETAILS_LIST);

        if (1 != responseList.size()) {
            _log("threadQueryProduct: repsonse list has unexpected length: " +
                 responseList.size());
            sendProductInfoError(context, sku);
            return;
        }

        String responseString = responseList.get(0);
        try {
            JSONObject o = new JSONObject(responseString);
            final String _sku = o.getString("productId");
            final String title = o.getString("title");
            final String description = o.getString("description");

            // TODO: something with price
            final String price = o.getString("price");

            // TOOD: check _sku == sku

            sendProductInfo(context, sku, title, description, price);
        } catch(JSONException e) {
            _error("threadQueryProduct: failed parsing JSON");
            sendProductInfoError(context, sku);
        }
    }

    public boolean doQueryProduct(final String sku, final long context)
    {
        if (!mReady) {
            _log("doQueryProduct: no ready");
            return false;
        }

        _log("doQueryProduct: " + sku);

        (new Thread(new Runnable() {
                public void run() {
                    threadQueryProduct(sku, context);
                }
            })).start();

        _log("doQueryProduct: launched thread");
        return true;
    }

    // ------------------------------------------------------------------
    // doConsume
    // ------------------------------------------------------------------

    // TODO: Make this async?

    // Consume a sku
    public boolean doConsume(final String token)
    {
        if (!mReady) {
            _error("doConsume: !! not ready.  leaving.");
            return false;
        }

        if (null == token || token.equals("")) {
            _error("doConsume: !! null or empty token");
            return false;
        }

        _print("doConsume: token: " + token);
        try {
            int response =
                mService.consumePurchase(3, mActivity.getPackageName(), token);

            if (BILLING_RESPONSE_RESULT_OK == response) {
                _log("doConsume: successfully consumed");
                return true;
            } else {
                _error("doConsume: !! failed to consume.  response: " + response);
            }
        } catch (RemoteException e) {
            _error("doConsume: !! exception " + e.toString());
        }

        return false;
    }
}
