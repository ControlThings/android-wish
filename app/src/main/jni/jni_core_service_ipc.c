/**
 * Copyright (C) 2020, ControlThings Oy Ab
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * @license Apache-2.0
 */
//
// Created by jan on 6/7/16.
//

#include <string.h>
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <android/log.h>

/* To re-generate the JNI interface: javah -classpath ../../../build/intermediates/classes/debug:/home/jan/Android/Sdk/platforms/android-16/android.jar -o jni_core_service_ipc.h fi.ct.wish.bridge.WishCoreJni */
#include "jni_core_service_ipc.h"
#include "wish_core.h"
#include "wish_connection.h"
#include "core_service_ipc.h"
#include "wish_dispatcher.h"
#include "jni_utils.h"
#include "wish_service_registry.h"
#include "bson.h"

/* If this is defined, all RPC messages from Wish core to App are examined for
 * RPC error 63, which means general failure in Wish core */
#define DETECT_WISH_RPC_ERROR_63


/* Reference to the JavaVM, saved by register_core_bridge */
static JavaVM * javaVM;

/* Reference to the Instance of the WishCoreBridge, registered in register_core_bridge */
static jobject core_bridge_instance;

static wish_core_t* core = NULL;

void core_service_ipc_init(wish_core_t* wish_core) {
    // locally global core set to wish_core
    core = wish_core;
}

static void close_wish_service() {
    android_wish_printf("in close_wish_service()");
    bool did_attach;
    JNIEnv *my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return;
    }

    if (core_bridge_instance == NULL) {
        android_wish_printf("close_wish_service: core bridge instance is NULL");
        return;
    }

    /* Get the class matching the core bridge instance, and get method id for requestWishCoreServiceClose()  */
    jclass core_bridge_class = (*my_env)->GetObjectClass(my_env, core_bridge_instance);
    jmethodID closeServiceMethodId = (*my_env)->GetMethodID(my_env, core_bridge_class, "requestWishCoreServiceClose", "()V");
    if (closeServiceMethodId == NULL) {
        android_wish_printf("Method requestWishCoreServiceClose cannot be found");
        return;
    }

    (*my_env)->CallVoidMethod(my_env, core_bridge_instance, closeServiceMethodId);
}

/*
 * Class:     fi_ct_wish_bridge_WishCoreJni
 * Method:    jni_receive_app_to_core
 * Signature: ([B)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_bridge_WishCoreJni_jni_1receive_1app_1to_1core(JNIEnv *env, jobject jthis, jbyteArray java_wsid, jbyteArray java_data) {
    //android_wish_printf("in receive app to core");
    
    if (core == NULL) {
        android_wish_printf("================= FAIL: in receive app to core, core pointer is NULL.");
        return;
    }
    

    size_t data_len = (*env)->GetArrayLength(env, java_data);
    size_t wsid_len = (*env)->GetArrayLength(env, java_wsid);
    if (wsid_len != WISH_WSID_LEN) {
        android_wish_printf("Failing sanity check: wsid len is not correct");
        return;
    }
    uint8_t *data = (uint8_t *) calloc(data_len, 1);
    (*env)->GetByteArrayRegion(env, java_data, 0, data_len, data);

    uint8_t wsid[WISH_WSID_LEN] = { 0 };
    (*env)->GetByteArrayRegion(env, java_wsid, 0, WISH_WSID_LEN, wsid);

    enter_WishOsJni_monitor();
    wish_core_handle_app_to_core(core, wsid, data, data_len);
    exit_WishOsJni_monitor();

    free(data);
}

void send_core_to_app(wish_core_t* wish_core, const uint8_t *wsid, const uint8_t* data, size_t len) {
    //android_wish_printf("in send_core_to_app");
    /* This will be set to true, if the thread of execution was not a JavaVM thread */
    bool did_attach = false;

    JNIEnv *my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return;
    }

#ifdef DETECT_WISH_RPC_ERROR_63
    bool request_close = false;
    /* Visit the BSON structure which is on the way up to the app.
     * Search for "error code 63", this signifies that the Core has experienced a serious internal error
     * and must restart. */
    bson_iterator iter;
    if (bson_find_from_buffer(&iter, (const char *) data, "err") == BSON_INT) {
        //android_wish_printf("ERR frame");
        /* Detected RPC error message */
        int data_type = bson_find_from_buffer(&iter, (const char *) data, "data");
        //android_wish_printf("err data type: %i", bson_iterator_type(&iter));
        if (data_type == BSON_OBJECT) {
            //android_wish_printf("err data");
            const char *data_doc = bson_iterator_value(&iter);
            bson_iterator sub_it;
            bson_iterator_subiterator(&iter, &sub_it);
            while (bson_iterator_type(&sub_it) != BSON_EOO) {
                //android_wish_printf("err data elem: %s type %i", bson_iterator_key(&sub_it), bson_iterator_type(&sub_it));
                const char *key = bson_iterator_key(&sub_it);
                if (strncmp(key, "code", 5) == 0) {
                    if (bson_iterator_int(&sub_it) == 63) {
                        /* General fail detected */
                        android_wish_printf("General lossage detected, closing Wish service!");
                        request_close = true;
                        break;
                    }
                }
                bson_iterator_next(&sub_it);
            }

        }
    }
#endif

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass core_bridge_class = (*my_env)->GetObjectClass(my_env, core_bridge_instance);
    jmethodID receiveCoreToAppMethodId = (*my_env)->GetMethodID(my_env, core_bridge_class, "receiveCoreToApp", "([B[B)V");
    if (receiveCoreToAppMethodId == NULL) {
        android_wish_printf("Method receiveCoreToApp cannot be found");
        return;
    }

    /* See JNI spec: http://docs.oracle.com/javase/6/docs/technotes/guides/jni/spec/functions.html#array_operations */
    jbyteArray java_buffer = (*my_env)->NewByteArray(my_env, len);
    /* See http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html for information on
     * how the "C" char and "JNI" jchar are the same thing! */
    (*my_env)->SetByteArrayRegion(my_env, java_buffer, 0, len, (const jbyte *) data);

    jbyteArray java_wsid = (*my_env)->NewByteArray(my_env, WISH_WSID_LEN);
    (*my_env)->SetByteArrayRegion(my_env, java_wsid, 0, WISH_WSID_LEN, (const jbyte *) wsid);

    //exit_WishOsJni_monitor();
    (*my_env)->CallVoidMethod(my_env, core_bridge_instance, receiveCoreToAppMethodId, java_wsid, java_buffer);
    //enter_WishOsJni_monitor();

    if (request_close) {
        close_wish_service();
    }

    if (did_attach) {
        detachThread(javaVM);
    }

    (*my_env)->DeleteLocalRef(my_env, java_wsid);
    (*my_env)->DeleteLocalRef(my_env, java_buffer);
}

/*
 * Class:     fi_ct_wish_bridge_WishCoreJni
 * Method:    register_core_bridge
 * Signature: (Lfi/ct/wish/bridge/WishCoreBridge;)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_bridge_WishCoreJni_register_1core_1bridge
  (JNIEnv *env, jobject jthis, jobject jWishCoreBridge) {

    /* Register a refence to the JVM */
    if ((*env)->GetJavaVM(env, &javaVM) < 0) {
        android_wish_printf("Failed to GetJavaVM");
        return;
    }

    /* Create a global reference to the WishService instance here */
    core_bridge_instance = (*env)->NewGlobalRef(env, jWishCoreBridge);
    if (core_bridge_instance == NULL) {
        android_wish_printf("Out of memory!");
        return;
    }


}

JNIEXPORT void JNICALL Java_fi_ct_wish_bridge_WishCoreJni_remove_1service(JNIEnv *env, jobject jthis, jbyteArray java_wsid) {
    // Actually removing service
    
    size_t wsid_len = (*env)->GetArrayLength(env, java_wsid);
    if (wsid_len != WISH_WSID_LEN) {
        android_wish_printf("Failing sanity check: wsid len is not correct");
        return;
    }

    uint8_t wsid[WISH_WSID_LEN] = { 0 };
    (*env)->GetByteArrayRegion(env, java_wsid, 0, WISH_WSID_LEN, wsid);

    enter_WishOsJni_monitor();
    wish_service_register_remove(core, wsid);
    exit_WishOsJni_monitor();
}

