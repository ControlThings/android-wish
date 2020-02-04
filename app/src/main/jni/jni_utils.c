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
#include "jni_utils.h"


int android_wish_printf(const char *format, ...) {
    va_list arg_list;
    va_start(arg_list, format);
    int ret = __android_log_vprint(ANDROID_LOG_DEBUG, "native", format, arg_list);
    va_end(arg_list);
    return ret;
}

/* A version of the printf command that takes a variable length argument list as a parameter.
 * This is needed as the function which will be passed down to the Wish library. (the "normal" printf version above will not do) */
int android_wish_vprintf(const char *format, va_list arg_list) {
    int ret = __android_log_vprint(ANDROID_LOG_DEBUG, "native", format, arg_list);
    return ret;
}


/* This function gets the JNIEnv associated with the current thread of execution.
 * Returns 0 if the operation was successful, or a negative value for a failure. The function uses a global reference to the JavaVM which is stored in function register().
 *
 * The function saves information on wether the current thread had to attached or not to the parameter didAttach. If didAttach==true after calling this function, it means that the thread must be detatched from the
 * javaVM after interaction over JNI is no longer needed. This should apply only attempt is made to access java methods from a thread (process?) not originally instantiated by the JavaVM.
 *
 *
 *
 */
int getJNIEnv(JavaVM *javaVM, JNIEnv **result_env, bool * didAttach) {
    int retval = 0;
    int getEnvStat = (*javaVM)->GetEnv(javaVM, (void **)result_env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        android_wish_printf("GetEnv: not attached. Trying to attach to current thread");
        if ((*javaVM)->AttachCurrentThread(javaVM, result_env, NULL) != 0) {
            /* Attaching did not work */
            android_wish_printf("Failed to attach");
            retval = -1;
        }
        else {
            /* Attaching was a success */
            *didAttach = true;
            retval = 0;
        }
    } else if (getEnvStat == JNI_OK) {
        if ((*result_env) == NULL) {
            android_wish_printf("GetEnv: fail");
            retval = -1;
        }
        else {
            retval = 0;
        }
    } else if (getEnvStat == JNI_EVERSION) {
        android_wish_printf("GetEnv: version not supported");
        retval = -1;
    }
    return retval;
}

/* This function detaches the current thread from the JavaVM.
 * Returns 0 if the call was successful */
int detachThread(JavaVM *javaVM) {
    int retval = 0;
    int detachStat = (*javaVM)->DetachCurrentThread(javaVM);
    if (detachStat != JNI_OK) {
        android_wish_printf("Could not detach thread");
        retval = -1;
    }
    return retval;
}