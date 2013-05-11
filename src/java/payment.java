// Copyright (c) 2013 Turbulenz Limited
// See LICENSE for full license text.

package com.turbulenz.turbulenz;

// import java.util.ArrayList;

// import org.json.JSONException;
// import org.json.JSONObject;

import android.util.Log;
import android.app.Activity;
// import android.app.PendingIntent;
// import android.os.Handler;
// import android.os.Bundle;
// import android.os.IBinder;
// import android.os.RemoteException;
// import android.text.TextUtils;
// import android.content.Context;
// import android.content.ComponentName;
// import android.content.ServiceConnection;
// import android.content.Intent;
// import android.content.IntentSender.SendIntentException;

// import com.android.vending.billing.IInAppBillingService;

public class payment
{
    // Logging
    static private void _log(String msg)
    {
        Log.i("tzbilling: ", msg);
    }
    static private void _print(String msg)
    {
        Log.i("tzbilling: ", msg);
    }
    static private void _error(String msg)
    {
        Log.e("tzbilling: ", msg);
    }

    //
    //
    //
    public static abstract class CallbackHandler
    {
        abstract public void post(Runnable r);
    };

    //
    //
    //
    static abstract class BillingAgent
    {
        abstract public void shutdown();

        /// Return true if everything is ready for purchases.  If (and
        /// only if) everything is not ready, call reportReady() later
        /// with the given context.
        public boolean doCheckReady();

        /// requestCode has already been checked.  If we have anything
        /// to do, do it and return true.
        abstract public boolean handleActivityResult(int requestCode,
                                                     int resultCode,
                                                     Intent data);

        /// If this returns true, it must call one of
        /// sendPurchaseFailure or sendPurchaseResult exactly once.
        abstract public boolean doPurchase(final String sku,
                                           final String devPayload,
                                           final long context);

        /// If this returns true, it must call sendPurchaseInfo() for
        /// each existing purchase, followed by
        /// sendPurchaseInfoTerminator().  If an error occurs, call
        /// sendPurchaseInfoError(), and ensure no further calls are
        /// made.
        abstract public boolean doQueryPurchases(final long context);

        /// If this returns true, it must call either
        /// sendProductInfoError() or sendProductInfo().
        abstract public boolean doQueryProduct(final String sku,
                                               final long context);

        /// Consume the purchase corresponding to the agent-token.
        abstract public boolean doConsume(final String token);

        long mReadyContext = 0;
        public boolean checkReady(long context)
        {
            if (0 != context) {
                if (0 != mReadyContext) {
                    _error("checkReady: onreadystatus already enabled with " +
                           "context: " + mReadyContext + ". Replacing with " +
                           context);
                }
                mReadyContext = context;
                return doCheckReady();
            }

            _log("doCheckReady: disabling onReady callbacks");
            mReadyContext = 0;
            return false;
        }

        // ------------------------------------------------------------
        // Internal methods
        // ------------------------------------------------------------

        // Either returns a known handler for the native thread, or
        // creates one.
        CallbackHandler getCallbackHandler()
        {
            if (null == sCallbackHandler) {
                sCallbackHandler = new CallbackHandler() {
                        Handler h = new Handler();
                        @Override public void post(Runnable r) {
                            h.post(r);
                        }
                    };
            }

            return sCallbackHandler;
        }

        void reportReady(final boolean ready)
        {
            _log("reportReady: ready report: " + Boolean.toString(ready));

            if (0 == mReadyContext) {
                _log("reportReady: ignoring");
                return;
            }

            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    _log("reportReady (h): " + Boolean.toString(ready));
                    nativeOnReadyStatus(mReadyContext, ready);
                }
            });
        }

        void sendPurchaseFailure(final long ctx, final String msg)
        {
            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    _log("sendPurchaseFailure (runnable): context: " + ctx +
                         ", msg: " + msg);
                    nativeOnPurchaseFailed(ctx, msg);
                    _log("sendPurchaseFailure (runnable): back from native");
                }
            });
        }

        void sendPurchaseResult(final long ctx,
                                final String sku, final String data,
                                final String token, final String devPayload,
                                final String signature)
        {
            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    _log("sendPurchaseResult (runnable): context: " + ctx);

                    _log("sendPurchaseResult (h): " +
                         "sku: " + ((null == sku)?("null"):(sku)) +
                         ", data: " + ((null == data)?("null"):(data)) +
                         ", token: " + ((null == token)?("null"):(token)) +
                         ", devPayload: " + ((null == devPayload)?("null"):
                                             (devPayload)) +
                         ", sig: " + ((null == signature)?("null"):(signature)));

                    nativeOnPurchaseComplete(ctx, sku, data, token,
                                             devPayload, signature);
                    _log("sendPurchaseResult (runnable): back from native");
                }
            });
        }

        void sendPurchaseInfo(final long context,
                              final String sku, final String data,
                              final String token, final String devPayload,
                              final String sig)
        {
            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    nativePurchaseQueryResponse(context, sku, data, token,
                                                devPayload, sig);
                }
            });
        }

        void sendPurchaseInfoTerminator(final long context)
        {
            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    nativePurchaseQueryResponse(context, "",
                                                null, null, null, null);
                }
            });
        }

        void sendPurchaseInfoError(final long context, final String msg)
        {
            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    nativePurchaseQueryResponse(context, null, msg,
                                                null, null, null);
                }
            });
        }

        void sendProductInfoError(final long context, final String sku)
        {
            sendProductInfo(handler, context, sku, null, null, null);
        }

        void sendProductInfo(final long context, final String sku,
                             final String title, final String description,
                             final String price)
        {
            getCallbackHandler().post(new Runnable() {
                @Override public void run() {
                    nativeProductQueryResponse(context, sku, title,
                                               description, price);
                }
            });
        }
    }

    // ------------------------------------------------------------------

    static Activity             sActivity = null;
    static int                  sPurchaseRequestCode = 0;
    static CallbackHandler      sCallbackHandler = null;
    static BillingAgent         sBillingAgent = null;

    // ------------------------------------------------------------------

    public static boolean initialize(Activity activity, int purchaseRequestCode)
    {
        sActivity = activity;
        sPurchaseRequestCode = purchaseRequestCode;

        // TODO: Detect the best billing agent to use

        sBillingAgent = new googlepayment(sActivity, sPurchaseRequestCode);

        return (null != sBillingAgent);
    }

    public static boolean initialize(Activity activity, int purchaseRequestCode,
                                     CallbackHandler handler)
    {
        sCallbackHandler = handler;
        boolean ret = initialize(activity, purchaseRequestCode);
        return ret;
    }

    // ------------------------------------------------------------------
    // shutdown
    // ------------------------------------------------------------------

    public static void shutdown()
    {
        _log("shutting down ...");

        if (null != sBillingAgent) {
            sBillingAgent.shutdown();
            sBillingAgent = null;
        }

        sCallbackHandler = null;
        sActivity = null;
        _log("done shutting down.");
    }

    // ------------------------------------------------------------------
    // handleActivityResult
    // ------------------------------------------------------------------

    // Return value indicates whether or not we handled the Intent,
    // not whether the purchase succeeded.
    public static boolean handleActivityResult(int requestCode, int resultCode,
                                               Intent data)
    {
        _log("handleActivityResult: requestCode: " + requestCode +
             " resultCode: " + resultCode);

        if (sPurchaseRequestCode != requestCode) {
            _error("handleActivityResult: !! requestCode does not match");
            return false;
        }

        if (null != sBillingAgent) {
            return sBillingAgent.handleActivityResult(resultCode, resultCode,
                                                      data);
        } else {
            _error("handleActivityResult: no billing agent");
        }

        return true;
    }

    // ------------------------------------------------------------------
    // doPurchase
    // ------------------------------------------------------------------

    //
    public static boolean doPurchase(final String sku, final String devPayload,
                                     long context)
    {
        _print("doPurchase: " + sku);

        if (0 == context) {
            _error("context must be non-zero");
            return false;
        }

        // Should this class handle moving everything to a thread?

        if (null != sBillingAgent) {
            return sBillingAgent.doPurchase(sku, devPayload, context);
        }

        _error("doPurchase: no billing agent");
        return false;
    }

    // ------------------------------------------------------------------
    // doQueryPurchases
    // ------------------------------------------------------------------

    // Call back to native code with the details of each purchase,
    public static boolean doQueryPurchases(final long context)
    {
        _log("doQueryPurchases: ");

        if (null != sBillingAgent) {
            return sBillingAgent.doQueryPurchases(context);
        }

        _error("doQueryPurchases: no billing agent");
        return false;
    }

    // ------------------------------------------------------------------
    // doQueryProduct
    // ------------------------------------------------------------------

    public static boolean doQueryProduct(final String sku, final long context)
    {
        _log("doQueryProduct: " + sku);

        if (null != sBillingAgent) {
            return sBillingAgent.doQueryProduct(sku, context);
        }

        _error("doQueryProduct: no billing agent");
        return false;
    }

    // ------------------------------------------------------------------
    // doCheckInitialized
    // ------------------------------------------------------------------

    // Determine whether billing has been initialized in the java side
    public static boolean doCheckInitialized()
    {
        boolean initialized = (null != sActivity);
        _log("doCheckInitialized: " + Boolean.toString(initialized));
        return initialized;
    }

    // Returns whether the service is ready.  If not (and context is
    // non-zero), then a callback is scheduled for when it becomes
    // ready.
    public static boolean doCheckReady(long context)
    {
        _log("doCheckReady: ctx: " + context);

        if (null != mBillingAgent) {
            final boolean ready = mBillingAgent.doCheckReady(context);
            _log("doCheckReady: agent returned: " + Boolean.toString(ready));
            return ready;
        }

        // TODO: this is ambiguous.  The caller can't tell that no
        // callback will ever be made.

        return false;
    }

    // ------------------------------------------------------------------
    // doConsume
    // ------------------------------------------------------------------

    // Consume a sku
    public static boolean doConsume(final String token)
    {
        _log("doConsume: token: " + token);

        if (null != mBillingAgent) {
            final boolean result = mBillingAgent.doConsume(token);
            _log("doConsume: agent returned: " + Boolean.toString(result));
            returned result;
        }

        return false;
    }

    //------------------------------------------------------------------

    static native void nativeOnReadyStatus(long context, boolean ready);

    static native void nativeOnPurchaseComplete
        (long context, String sku, String details, String token,
         String devPayload, String sig);

    static native void nativeOnPurchaseFailed
        (long context, String msg);

    static native void nativeProductQueryResponse
        (long context, String sku, String title, String description,
         String price);

    // sku == "", details == null, signature == null means end of purchases
    // sku == null, details != null means error (msg in 'details')
    static native void nativePurchaseQueryResponse
        (long context, String sku, String details, String token,
         String devPayload, String sig);

}
