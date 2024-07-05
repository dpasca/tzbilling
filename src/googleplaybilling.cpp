// Copyright (c) 2013 Turbulenz Limited
// See LICENSE for full license text.

#include "googleplaybilling.h"

#include <android/log.h>

using namespace turbulenz;

#define LOGI(...)
#ifndef LOGI
# define LOGI(...) \
    ((void)__android_log_print(ANDROID_LOG_INFO, "nativebilling", __VA_ARGS__))
#endif

#define LOGE(...) \
    ((void)__android_log_print(ANDROID_LOG_ERROR, "nativebilling", __VA_ARGS__))

static void
InitStringFromJString(std::string &out_string, JNIEnv *env, jstring jstr)
{
    const char *jstrChars = env->GetStringUTFChars(jstr, 0);
    out_string = jstrChars;
    env->ReleaseStringUTFChars(jstr, jstrChars);
}

struct PurchaseContext
{
    void                                 *callerContext;
    GooglePlayBilling::PurchaseSuccessCB  successCallback;
    GooglePlayBilling::PurchaseFailureCB  failureCallback;
};

extern "C" void
Java_com_turbulenz_turbulenz_payment_nativeOnPurchaseComplete
(JNIEnv *env, jobject thiz, jlong context, jstring sku, jstring details,
 jstring token, jstring devPayload,jstring sig)
{
    if (0 == context)
    {
        LOGE("purchase complete callback called with null context");
        return;
    }

    PurchaseContext *purchaseCtx = (PurchaseContext *)(size_t )context;

    GooglePlayBilling::Purchase purchase;
    InitStringFromJString(purchase.sku, env, sku);
    InitStringFromJString(purchase.details, env, details);
    InitStringFromJString(purchase.googleToken, env, token);
    InitStringFromJString(purchase.clientToken, env, devPayload);
    InitStringFromJString(purchase.signature, env, sig);

    purchaseCtx->successCallback(purchaseCtx->callerContext, purchase);

    delete purchaseCtx;
}

extern "C" void
Java_com_turbulenz_turbulenz_payment_nativeOnPurchaseFailed
(JNIEnv *env, jobject thiz, jlong context, jstring msg)
{
    if (0 == context)
    {
        LOGE("purchase failed callback called with null context");
        return;
    }

    PurchaseContext *purchaseCtx = (PurchaseContext *)(size_t )context;

    const char *msgChars = 0;

    std::string msgStr;
    if (0 != msg)
    {
        InitStringFromJString(msgStr, env, msg);
        msgChars = msgStr.c_str();
    }

    purchaseCtx->failureCallback(purchaseCtx->callerContext, msgChars);

    delete purchaseCtx;
}

struct QueryContext
{
    void                               *callerContext;
    GooglePlayBilling::PurchaseQueryCB  callback;
    GooglePlayBilling::PurchaseList     purchases;
};

extern "C" void
Java_com_turbulenz_turbulenz_payment_nativePurchaseQueryResponse
(JNIEnv *env, jobject thiz, jlong context, jstring sku, jstring details,
 jstring token, jstring devPayload, jstring sig)
{
    if (0 == context)
    {
        LOGE("purchase query callback called with null context");
        return;
    }

    QueryContext *queryCtx = (QueryContext *)(size_t )context;

    // Conditions are:
    // sku == null, details != null means error (msg in 'details')
    // sku == "", details == null, signature == null means end of purchases

    if (0 == sku)
    {
        if (0 != details)
        {
            std::string errStr;
            InitStringFromJString(errStr, env, details);
            LOGE("query failed: %s", errStr.c_str());
        }
        else
        {
            LOGE("query failed: (internal error - no details available)");
        }

        // Create a single empty entry to indicate an error.

        GooglePlayBilling::PurchaseList &list = queryCtx->purchases;
        list.clear();
        list.push_back({ "", "", "", "", "" });
        queryCtx->callback(queryCtx->callerContext, queryCtx->purchases);

        delete queryCtx;
    }
    else if (0 == details)
    {
        // The list has been terminated.  We can make the callback now.

        queryCtx->callback(queryCtx->callerContext, queryCtx->purchases);

        delete queryCtx;
    }
    else
    {
        // We have a purchase to add to the list

        GooglePlayBilling::PurchaseList &list = queryCtx->purchases;
        list.push_back(GooglePlayBilling::Purchase());

        GooglePlayBilling::Purchase &purchase = list[list.size() - 1];
        InitStringFromJString(purchase.sku, env, sku);
        InitStringFromJString(purchase.details, env, details);
        InitStringFromJString(purchase.googleToken, env, token);
        InitStringFromJString(purchase.clientToken, env, devPayload);
        InitStringFromJString(purchase.signature, env, sig);
    }
}

struct ProductQueryContext
{
    void                              *callerContext;
    GooglePlayBilling::ProductQueryCB  callback;
};

extern "C" void
Java_com_turbulenz_turbulenz_payment_nativeProductQueryResponse
(JNIEnv *env, jobject thiz, jlong context, jstring sku, jstring title,
 jstring description, jstring price)
{
    if (0 == context)
    {
        LOGE("product query callback called with null context");
        return;
    }

    ProductQueryContext *ctx = (ProductQueryContext *)(size_t )context;

    // sku should never be null.  If title is null, there is no such
    // product.

    if (0 == sku)
    {
        LOGE("product query has null SKU");
        delete ctx;
        return;
    }

    GooglePlayBilling::Product product;
    InitStringFromJString(product.sku, env, sku);

    if (0 != title)
    {
        InitStringFromJString(product.title, env, title);
        InitStringFromJString(product.description, env, description);
        InitStringFromJString(product.price, env, price);
    }

    ctx->callback(ctx->callerContext, product);

    delete ctx;
}

extern "C" void
Java_com_turbulenz_turbulenz_payment_nativeOnReadyStatus
(JNIEnv *env, jobject thiz, jlong context, jboolean ready)
{
    LOGI("ready state updated: %s", (ready)?("true"):("false"));

    if (0 == context)
    {
        LOGE("purchase query callback called with null context");
        return;
    }

    GooglePlayBilling *billing = (GooglePlayBilling *)context;
    if (0 != billing->mReadyStatusCallback)
    {
        LOGI("making onreadystate callback ...");
        billing->mReadyStatusCallback(billing->mReadyStatusContext, !!ready);
        LOGI("back from onreadystate callback.");
    }
    else
    {
        LOGE("!! no onreadystate callback");
    }
}

namespace turbulenz
{

GooglePlayBilling::GooglePlayBilling(JNIEnv *jniEnv, jclass paymentClass)
    : mJNIEnv(jniEnv)
    , mPaymentClass(paymentClass)
{
    LOGI("initializing");

    if (0 == jniEnv)
    {
        LOGE("constructor called with null jniEnv parameter");
        return;
    }

    if (0 == mPaymentClass)
    {
        static const char *paymentClassName = "com/turbulenz/turbulenz/payment";
        jclass paymentLocal = jniEnv->FindClass(paymentClassName);
        if (0 == paymentLocal)
        {
            mJNIEnv = 0;
            LOGE("cannot find Java class");
            return;
        }
        mPaymentClass = (jclass )(jniEnv->NewGlobalRef(paymentLocal));
        jniEnv->DeleteLocalRef(paymentLocal);
    }

    jmethodID checkInitMethod =
        jniEnv->GetStaticMethodID(mPaymentClass, "doCheckInitialized", "()Z");
    if (0 != checkInitMethod)
    {
        if (CallJavaMethod(checkInitMethod))
        {
            mDoCheckReadyMethod = jniEnv->GetStaticMethodID
                (mPaymentClass, "doCheckReady", "(J)Z");
            mDoPurchaseMethod = jniEnv->GetStaticMethodID
                (mPaymentClass, "doPurchase",
                 "(Ljava/lang/String;Ljava/lang/String;ZJ)Z");
            mDoQueryPurchasesMethod = jniEnv->GetStaticMethodID
                (mPaymentClass, "doQueryPurchases", "(J)Z");
            mDoQueryProductMethod = jniEnv->GetStaticMethodID
                (mPaymentClass, "doQueryProduct", "(Ljava/lang/String;J)Z");
            mDoConsumeMethod = jniEnv->GetStaticMethodID
                (mPaymentClass, "doConsume", "(Ljava/lang/String;)Z");

            if (0 == mDoCheckReadyMethod     ||
                0 == mDoQueryPurchasesMethod ||
                0 == mDoQueryProductMethod   ||
                0 == mDoPurchaseMethod       ||
                0 == mDoConsumeMethod)
            {
                LOGE("Cannot find all methods on Java class");
            }
            else
            {
                LOGI("initialized");
                return;
            }
        }
        else
        {
            LOGE("Java side code has not been enabled");
        }
    }
    else
    {
        LOGE("cannot find 'doCheckInitialized' method");
    }

    jniEnv->DeleteGlobalRef(mPaymentClass);
    mPaymentClass = 0;
    mJNIEnv = 0;
}

GooglePlayBilling::~GooglePlayBilling()
{
    LOGI("shutting down");
    if (0 != mJNIEnv)
    {
        if (0 != mPaymentClass)
        {
            // Disable onready callbacks

            CallJavaMethod(mDoCheckReadyMethod, (jlong )0);

            mJNIEnv->DeleteGlobalRef(mPaymentClass);
            mPaymentClass = 0;
        }
    }
}

bool
GooglePlayBilling::CallJavaMethod(jmethodID method, ...)
{
    if (0 == mJNIEnv)
    {
        LOGE("attempt to call Java with no JNI env set");
        return false;
    }
    if (0 == mPaymentClass)
    {
        LOGE("attempt to call Java with no reference to payment class");
        return false;
    }
    if (0 == method)
    {
        LOGE("attempt to call null Java method");
        return false;
    }

    va_list args;
    va_start(args, method);

    LOGI("making call to Java ...");
    jboolean ret =
        mJNIEnv->CallStaticBooleanMethodV(mPaymentClass, method, args);
    jthrowable exc = mJNIEnv->ExceptionOccurred();
    if (exc)
    {
        LOGE("!! exception in Java call:");
        mJNIEnv->ExceptionDescribe();
        mJNIEnv->ExceptionClear();
    }
    LOGI("done");

    va_end(args);

    return !!ret;
}

bool
GooglePlayBilling::QueryPurchases(void *ctx,
                                  GooglePlayBilling::PurchaseQueryCB callback)
{
    QueryContext *queryCtx = new QueryContext;
    queryCtx->callerContext = ctx;
    queryCtx->callback = callback;

    if (!CallJavaMethod(mDoQueryPurchasesMethod, (jlong )(size_t )queryCtx))
    {
        delete queryCtx;
        return false;
    }

    return true;
}

bool
GooglePlayBilling::QueryProduct(void *ctx, const char *sku,
                                GooglePlayBilling::ProductQueryCB callback)
{
    if (0 == mJNIEnv)
    {
        LOGE("call to QueryProduct before initialization");
        return false;
    }

    ProductQueryContext *productQueryCtx = new ProductQueryContext;
    productQueryCtx->callerContext = ctx;
    productQueryCtx->callback = callback;

    jstring jSKU = mJNIEnv->NewStringUTF(sku);
    const jlong jCtx = (jlong )productQueryCtx;
    if (!CallJavaMethod(mDoQueryProductMethod, jSKU, jCtx))
    {
        delete productQueryCtx;
        return false;
    }

    return true;
}

bool
GooglePlayBilling::ConfirmPurchase(void *ctx, const char *sku,
                                   const char *clientToken,
                                   bool isConsumable,
                                   GooglePlayBilling::PurchaseSuccessCB success,
                                   GooglePlayBilling::PurchaseFailureCB failure)
{
    if (0 == mJNIEnv)
    {
        LOGE("call to ConfirmPurchase before initialization");
        return false;
    }

    PurchaseContext *purchaseCtx = new PurchaseContext;
    purchaseCtx->callerContext = ctx;
    purchaseCtx->successCallback = success;
    purchaseCtx->failureCallback = failure;

    jstring jSKU = mJNIEnv->NewStringUTF(sku);
    jstring jClientToken = mJNIEnv->NewStringUTF(clientToken);
    jboolean jIsConsumable = isConsumable;
    jlong jCtx = (jlong )(size_t )purchaseCtx;

    LOGI("Calling Java DoPurchase method ...");
    if (!CallJavaMethod(mDoPurchaseMethod, jSKU, jClientToken, jIsConsumable, jCtx))
    {
        LOGI("DoPurchase method returned FALSE");
        delete purchaseCtx;
        return false;
    }
    LOGI("DoPurchase method returned TRUE");

    return true;
}

bool
GooglePlayBilling::ConsumePurchase(const char *googleToken)
{
    // TODO: async?

    if (0 == mJNIEnv)
    {
        LOGE("call to ConsumePurchase before initialization");
        return false;
    }

    jstring jGoogleToken = mJNIEnv->NewStringUTF(googleToken);
    return CallJavaMethod(mDoConsumeMethod, jGoogleToken);
}

bool
GooglePlayBilling::SetReadyStatusCallback(void *ctx, ReadyStatusCB callback)
{
    LOGI("setting onreadystatus callback: %p, %p", ctx, callback);

    mReadyStatusContext = ctx;
    mReadyStatusCallback = callback;

    jlong jCtx = (jlong )(size_t )this;
    if (0 == callback)
    {
        jCtx = 0;
    }

    return CallJavaMethod(mDoCheckReadyMethod, jCtx);
}

} // namespace turbulenz
