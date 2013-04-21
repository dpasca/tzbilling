// Copyright (c) 2013 Turbulenz Limited
// See LICENSE for full license text.

#ifndef __TURBULENZ_ANDROID_PAYMENT_H__
#define __TURBULENZ_ANDROID_PAYMENT_H__

#include <string>
#include <vector>
#include <jni.h>

namespace turbulenz
{

class GooglePlayBilling
{
public:
    struct Product
    {
        std::string   sku;
        std::string   title;
        std::string   description;
        std::string   price;
    };

    struct Purchase
    {
        std::string   sku;
        std::string   details;
        std::string   googleToken;
        std::string   clientToken;
        std::string   signature;
    };

    typedef std::vector<Purchase> PurchaseList;

    typedef void (*ReadyStatusCB)(void *ctx, bool ready);

    typedef void (*ProductQueryCB)(void *ctx, const Product &product);

    /// sku == "", details == null, signature == null means end of purchases
    /// sku == null, details != null means error (msg in 'details')
    typedef void (*PurchaseQueryCB)(void *ctx, const PurchaseList &purchases);

    typedef void (*PurchaseSuccessCB)(void *ctx, const Purchase &purchase);

    /// message == null means the user cancelled the purchase,
    /// otherwise message contains the error message.
    typedef void (*PurchaseFailureCB)(void *ctx, const char *message);

    GooglePlayBilling(JNIEnv *jniEnv, jclass paymentClass = 0);

    ~GooglePlayBilling();

    // Set a callback to be notified of ready status changes.  Returns
    // the current status.
    bool SetReadyStatusCallback(void *ctx, ReadyStatusCB callback);

    bool QueryPurchases(void *ctx, PurchaseQueryCB callback);

    bool QueryProduct(void *ctx, const char *sku, ProductQueryCB callback);

    bool ConfirmPurchase(void *ctx, const char *sku, const char *clientToken,
                         PurchaseSuccessCB success, PurchaseFailureCB failure);

    bool ConsumePurchase(const char *googleToken);

    void          *mReadyStatusContext;
    ReadyStatusCB  mReadyStatusCallback;

protected:

    bool CallJavaMethod(jmethodID method, ...);

    JNIEnv        *mJNIEnv;
    jclass         mPaymentClass;
    jmethodID      mDoCheckReadyMethod;
    jmethodID      mDoPurchaseMethod;
    jmethodID      mDoQueryPurchasesMethod;
    jmethodID      mDoQueryProductMethod;
    jmethodID      mDoConsumeMethod;

};

} // namespace turbulenz

#endif // __TURBULENZ_ANDROID_PAYMENT_H__
