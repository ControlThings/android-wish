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
#include <string.h>
#include <jni.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdarg.h>
#include <stdio.h>
#include <android/log.h>
#include <time.h>
#include <signal.h>
#include <unistd.h>


#include "wish_connection.h"
#include "wish_event.h"
#include "wish_platform.h"
#include "wish_connection_mgr.h"
#include "wish_fs.h"
#include "wish_identity.h"
#include "wish_time.h"
#include "wish_local_discovery.h"

#include "wish_relay_client.h"

#include "jni_utils.h"

#include "utlist.h"

/* To re-generate Java native interface;
 * 1) Compile the java project so that class files are updated (Build->Make project)
 * 2) Generate C header: javah -classpath ../../../build/intermediates/classes/debug:/home/jan/Android/Sdk/platforms/android-16/android.jar -o android_wish.h fi.ct.wish.os.WishOsJni
 *
 * To recompile, run ndk-build in this directory.
 *
 * PLEASE READ AND UNDERSTAND:
 * http://www.ibm.com/developerworks/library/j-jni/
 *
 */
#include "android_wish.h"

/* Prototypes for filesystem functions implemented later in this file */
wish_file_t my_fs_open(const char *filename);
int32_t my_fs_close(wish_file_t fileId);
int32_t my_fs_read(wish_file_t fileId, void* buf, size_t count);
int32_t my_fs_write(wish_file_t fileId, const void* buf, size_t count);
int32_t my_fs_lseek(wish_file_t fileId, wish_offset_t offset, int whence);
int32_t my_fs_rename(const char *oldname, const char *newname);
int32_t my_fs_remove(const char *file_name);


/*
 * Global variables referring to the Java context
 *
 * See:
 * http://www.math.uni-hamburg.de/doc/java/tutorial/native1.1/implementing/refs.html
 * http://adamish.com/blog/archives/327
 * http://stackoverflow.com/questions/12900695/how-to-obtain-jni-interface-pointer-jnienv-for-asynchronous-calls
 * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/functions.html#global_local
 *
 * */

/* This is the reference to the VM environment - this should be safe to cache between function calls.
 * Initialised in register() */
static JavaVM * javaVM;
/* A global reference to the WishOsJni instance will be stored here by register() */
static jobject wishOsJniInstance;


static wish_core_t core_inst;

static wish_core_t* core = &core_inst;

uint8_t relayed_uid[WISH_ID_LEN];

struct resolve_data {
    wish_core_t* core;
    wish_relay_client_t *relay;
    wish_connection_t *conn;
    int resolve_id;
    struct resolve_data *next;
};

static struct resolve_data *resolver_list = NULL;



static int enqueue_dns_resolving(wish_core_t *core, wish_connection_t *conn, wish_relay_client_t *relay, char *qname) {
    static int next_resolve_id = 0;
    bool did_attach = false;
    
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }
    
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID connectMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "startDnsResolving", "(Ljava/lang/String;I)I");
    if (connectMethodId == NULL) {
        android_wish_printf("Method cannot be found in enqueue_dns_resolving");
    }
    
    /* Convert cstring "qname" to java.lang.String object */
    jstring java_qname = (*my_env)->NewStringUTF(my_env, qname);
    
    /* Allocate resolve_data struct, and add it to list */
    int resolve_id = next_resolve_id++;
    struct resolve_data *rdata = calloc(sizeof(struct resolve_data), 1);
    rdata->conn = conn;
    rdata->core = core;
    rdata->relay = relay;
    rdata->resolve_id = resolve_id;
    LL_APPEND(resolver_list, rdata);
    
    enter_WishOsJni_monitor();
    (*my_env)->CallIntMethod(my_env, wishOsJniInstance, connectMethodId, java_qname, resolve_id);
    exit_WishOsJni_monitor();
    
    (*my_env)->DeleteLocalRef(my_env, java_qname);
    
    if (did_attach) {
        detachThread(javaVM);
    }
    return 0;
}

static int seed_random_init() {
    unsigned int randval;
    
    FILE *f;
    f = fopen("/dev/urandom", "r");
    
    if (f == NULL) {
        android_wish_printf("urandom file handler is NULL!!");
        return -1;
    }
    
    int c;
    for (c=0; c<32; c++) {
        fread(&randval, sizeof(randval), 1, f);
        srandom(randval);
    }
    
    fclose(f);
    
    return 0;


}


static void process_wish_core() {
    while (1) {
        struct wish_event *ev = wish_get_next_event();
        if (ev != NULL) {
            wish_message_processor_task(core, ev);
        }
        else {
            break;
        }
    }
}

static wish_relay_client_t* wish_relay_client_get_by_id(int id) {
    wish_relay_client_t* relay = NULL;
    LL_FOREACH(core->relay_db, relay) {
        if (relay->sockfd == id) {
            break;
        }
    }
    return relay;
}

JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_reportPeriodic(JNIEnv *env, jobject jthis) {
    wish_time_report_periodic(core);
    process_wish_core();
}

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    register
 * Signature: (int)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_register(JNIEnv *env, jobject jthis, jint corePort) {
    /* Register a refence to the JVM */
    if ((*env)->GetJavaVM(env, &javaVM) < 0) {
        android_wish_printf("Failed to GetJavaVM");
        return;
    }

    /* Create a global reference to the WishService instance here */
    wishOsJniInstance = (*env)->NewGlobalRef(env, jthis);
    if (wishOsJniInstance == NULL) {
        android_wish_printf("Out of memory!");
        return;
    }

    /* Register the platform dependencies with Wish/Mist */

    wish_platform_set_malloc(malloc);
    wish_platform_set_realloc(realloc);
    wish_platform_set_free(free);
    wish_platform_set_rng(random);
    wish_platform_set_vprintf(android_wish_vprintf);
    wish_platform_set_vsprintf(vsprintf);
    wish_fs_set_open(my_fs_open);
    wish_fs_set_close(my_fs_close);
    wish_fs_set_read(my_fs_read);
    wish_fs_set_write(my_fs_write);
    wish_fs_set_lseek(my_fs_lseek);
    wish_fs_set_rename(my_fs_rename);
    wish_fs_set_remove(my_fs_remove);
    
    // Will provide some random, but not to be considered cryptographically secure
    seed_random_init();

    core->wish_server_port = corePort;
    wish_core_init(core);
    wish_core_update_identities(core);
}

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    unRegister
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_unRegister
        (JNIEnv * env, jobject jthis) {
    (*env)->DeleteGlobalRef(env, wishOsJniInstance);
}

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    getBufferFree
 * Signature: (I)I
 */
JNIEXPORT jint JNICALL Java_fi_ct_wish_os_WishOsJni_getRxBufferFree
        (JNIEnv *env, jobject jthis, jint connection_id) {

    wish_connection_t *ctx = wish_core_lookup_ctx_by_connection_id(core, connection_id);

    if (ctx == NULL) {
        android_wish_printf("get RxBuffer Free: Error! Did not find context with connection id = %d", connection_id);
        return -1;
    }

    return wish_core_get_rx_buffer_free(core, ctx);
}

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    isRingbufferEmpty
 * Signature: (I)Z
 */
JNIEXPORT jboolean JNICALL Java_fi_ct_wish_os_WishOsJni_isRingbufferEmpty
        (JNIEnv *env, jobject jthis, jint connection_id) {

    wish_connection_t *ctx = wish_core_lookup_ctx_by_connection_id(core, connection_id);

    if (ctx == NULL) {
        android_wish_printf("isRingbufferEmpty:  Error! Did not find context with connection id = %d", connection_id);
        return true;    /* Return true, because that way we ensure that the CommunicationThread actually exits in case of a killed connection that has a CommunicationThread blocked on socket read.*/
    }

    /* The ring buffer is empty if the number of free bytes in ring buffer matches the size of the ring buffer */
    return wish_core_get_rx_buffer_free(core, ctx) == WISH_PORT_RX_RB_SZ;
}



/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    feedData
 * Signature: (I[BI)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_feedData
        (JNIEnv *env, jobject jthis, jint connection_id, jbyteArray java_buffer, jint buffer_len) {

    if (wishOsJniInstance == NULL) {
        return;
    }
    wish_connection_t *ctx = wish_core_lookup_ctx_by_connection_id(core, connection_id);

    if (ctx == NULL) {
        android_wish_printf("feedData: Error! Did not find context with connection id = %d", connection_id);
        return  ;
    }

    //__android_log_print(ANDROID_LOG_DEBUG, "my_native", "feeding data to Wish, connection id %i, len = %i", connection_id, buffer_len);

    jbyte *native_buffer = calloc(buffer_len, 1);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, "my_native", "native buffer allocation fails!");
        return;
    }
    (*env)->GetByteArrayRegion(env, java_buffer, 0, buffer_len, native_buffer);

    wish_core_feed(core, ctx, native_buffer, buffer_len);
    struct wish_event ev = { .event_type = WISH_EVENT_NEW_DATA,
                        .context = ctx };
    wish_message_processor_notify(&ev);
    free(native_buffer);
    
    process_wish_core();
}

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    feedLocalDiscoveryData
 * Signature: ([BI[B)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_feedLocalDiscoveryData
        (JNIEnv *env, jobject jthis, jbyteArray java_ipAddr, jint java_port, jbyteArray java_localDiscoveryData) {

    const int ipv4_addr_len = 4;
    uint8_t ipv4_addr[ipv4_addr_len];

    int ip_length = (*env)->GetArrayLength(env, java_ipAddr);

    if (ip_length != ipv4_addr_len) {
        android_wish_printf("Unexpected IPv4 address length");
        return;
    }

    (*env)->GetByteArrayRegion(env, java_ipAddr, 0, ipv4_addr_len, ipv4_addr);

    int data_length = (*env)->GetArrayLength(env, java_localDiscoveryData);

    uint8_t *data = (uint8_t *) calloc(data_length, 1);
    (*env)->GetByteArrayRegion(env, java_localDiscoveryData, 0, data_length, data);

    wish_ip_addr_t ip;
    memcpy(ip.addr, ipv4_addr, ipv4_addr_len);

    //android_wish_printf("Local discovery: IP addr %d.%d.%d.%d", ipv4_addr[0], ipv4_addr[1], ipv4_addr[2], ipv4_addr[3]);
    //android_wish_printf("Local discovery: data len: %d", data_length);

    wish_ldiscover_feed(core, &ip, java_port, data, data_length);
    free(data);
    
    process_wish_core();
}


int write_to_socket(wish_connection_t *ctx, unsigned char* buffer, int buffer_len) {
    if (ctx == NULL) {
        android_wish_printf("Fail: ctx is null!");
        return 1;
    }
    wish_connection_id_t id = ctx->connection_id;



    /* This will be set to true, if the thread of execution was not a JavaVM thread */
    bool did_attach = false;

    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 1;
    }

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID sendDataMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "sendData", "([BI)I");
    if (sendDataMethodId == NULL) {
        android_wish_printf("Method cannot be found");
        return 1;
    }

    /* See JNI spec: http://docs.oracle.com/javase/6/docs/technotes/guides/jni/spec/functions.html#array_operations */
    jbyteArray java_buffer = (*my_env)->NewByteArray(my_env, buffer_len);
    /* See http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html for information on
     * how the "C" char and "JNI" jchar are the same thing! */
    (*my_env)->SetByteArrayRegion(my_env, java_buffer, 0, buffer_len, (const jbyte *) buffer);

    enter_WishOsJni_monitor();
    int bytes_sent = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, sendDataMethodId, java_buffer, id);
    exit_WishOsJni_monitor();

    //android_wish_printf("write to socket; and here; return value is %i", bytes_sent);
    if (bytes_sent != buffer_len) {
        android_wish_printf("write to socket; unexpected: bytes_send %i buffer_len %i", bytes_sent, buffer_len);
    }

    if (did_attach) {
        detachThread(javaVM);
    }

    (*my_env)->DeleteLocalRef(my_env, java_buffer);
    return 0;
}


/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    signalEvent
 * Signature: (II)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_signalEvent
        (JNIEnv *env, jobject jthis, jint connection_id, jint sig) {
    android_wish_printf("in signal event, connection id %i", connection_id );
    wish_connection_t *ctx = wish_core_lookup_ctx_by_connection_id(core, connection_id);

    if (ctx == NULL) {
        android_wish_printf("ctx is NULL, unknown connection_id?");
        return;
    }

    switch (sig) {
        case TCP_CONNECTED:
            wish_core_register_send(core, ctx, write_to_socket, ctx);
            if (ctx->via_relay) {
                /* When via_relay is true, we have just opened a connection to the relay server in order to accept an incoming
                 * connection waiting at the relay server. Now tell the core that the connection is ready */
                android_wish_printf("Signaling TCP_RELAY_SESSION_CONNECTED");
                wish_core_signal_tcp_event(core, ctx, TCP_RELAY_SESSION_CONNECTED);
            }
            else {
                /* Normal wish connection (initated by us) has connected */
                android_wish_printf("Signaling TCP_CONNECTED");
                wish_core_signal_tcp_event(core, ctx, TCP_CONNECTED);
            }
            break;
        default:
            wish_core_signal_tcp_event(core, ctx, sig);
            break;
    }
    
    process_wish_core();

}



/*
 * Class:     ct_fi_mist_bridge_MistBridge
 * Method:    processConnections
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_processConnections
        (JNIEnv *env, jobject jthis) {
    //__android_log_print(ANDROID_LOG_DEBUG, "my_native", "processing connections!");
    process_wish_core();
}


int wish_open_connection(wish_core_t* core, wish_connection_t *new_ctx, wish_ip_addr_t *ip, uint16_t port, bool via_relay) {



    android_wish_printf("We would now open connection to %d.%d.%d.%d", ip->addr[0], ip->addr[1], ip->addr[2], ip->addr[3]);

    /* Call the a function on the Java side for opening the connection.
     * The function is: MistBridge.connect(ip, port)
     * */

    /* This will be set to true, if the thread of execution was not a JavaVM thread */
    bool did_attach = false;


    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 1;
    }

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID connectMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "connect", "([BII)V");
    if (connectMethodId == NULL) {
        android_wish_printf("Method cannot be found");
        return 1;
    }

    const int ip_addr_len = 4;
    jbyteArray java_ip = (*my_env)->NewByteArray(my_env, ip_addr_len);
    (*my_env)->SetByteArrayRegion(my_env, java_ip, 0, ip_addr_len, ip->addr);

    enter_WishOsJni_monitor();
    (*my_env)->CallVoidMethod(my_env, wishOsJniInstance, connectMethodId, java_ip, port, new_ctx->connection_id);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_ip);

    if (did_attach) {
        detachThread(javaVM);
    }
    process_wish_core();

    return 0;
}

int wish_open_connection_dns(wish_core_t* core, wish_connection_t* connection, char* host, uint16_t port, bool via_relay) {
    
    connection->core = core;
    connection->remote_port = port;
    connection->via_relay = via_relay;
    connection->curr_transport_state = TRANSPORT_STATE_RESOLVING;
    
    enqueue_dns_resolving(core, connection, NULL, host);
    
    return 0;
}

void wish_close_connection(wish_core_t* core, wish_connection_t *ctx) {
    android_wish_printf("Close TCP connection");

    ctx->context_state = WISH_CONTEXT_CLOSING;
    /* Call the a function on the Java side for closing the connection.
 * The function is: WishOsJni.close(connection_id)
 * */

    /* This will be set to true, if the thread of execution was not a JavaVM thread */
    bool did_attach = false;


    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return;
    }

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID closeMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "closeConnection", "(I)V");
    if (closeMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    enter_WishOsJni_monitor();
    (*my_env)->CallVoidMethod(my_env, wishOsJniInstance, closeMethodId, ctx->connection_id);
    exit_WishOsJni_monitor();

    if (did_attach) {
        detachThread(javaVM);
    }
    
    process_wish_core();

}

int wish_send_advertizement(wish_core_t* core, uint8_t *ad, size_t ad_len) {
    //android_wish_printf("We would now send out an UDP local discovery broadcast");

    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 0;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID startBroadcastMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "startBroadcastAutodiscovery", "([B)V");
    if (startBroadcastMethodId == NULL) {
        android_wish_printf("Method cannot be found");
        return 0;
    }

    jbyteArray java_msg = (*my_env)->NewByteArray(my_env, ad_len);
    (*my_env)->SetByteArrayRegion(my_env, java_msg, 0, ad_len, ad);

    enter_WishOsJni_monitor();
    (*my_env)->CallVoidMethod(my_env, wishOsJniInstance, startBroadcastMethodId, java_msg);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_msg);

    if (did_attach) {
        detachThread(javaVM);
    }
    
    process_wish_core();
    return 0;
}

/*
 * This functoin is called when serverSocket.accept() returns, meaning that a new socket connection has been established.
 * The function creates a new wish connection, (assining a connection_id behind the scenes).
 * Returns the connection id, or a negative number indicating failure.
 *
 *
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    acceptServerConnection
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_fi_ct_wish_os_WishOsJni_acceptServerConnection
        (JNIEnv *env, jobject jthis) {
    android_wish_printf("in acceptServerConnection!");
    /* Start the wish core with null IDs.·
     * The actual IDs will be established during handshake
     */
    uint8_t null_id[WISH_ID_LEN] = { 0 };
    wish_connection_t *ctx = wish_connection_init(core, null_id, null_id);
    if (ctx == NULL) {
        android_wish_printf("ctx is null!");
        return -1;
    }
    wish_core_register_send(core, ctx, write_to_socket, ctx);
    
    process_wish_core();
    return ctx->connection_id;
}

/* This defines the maximum allowable length of the file name */
#define MAX_FILENAME_LENGTH 32

/*
 * This function is called by the Wish fs abstraction layer when a file is to be opened.
 */
wish_file_t my_fs_open(const char *filename) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 0;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID openFileMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "openFile", "(Ljava/lang/String;)I");
    if (openFileMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    jstring java_filename = (*my_env)->NewStringUTF(my_env, filename);
    if (java_filename == NULL) {
        android_wish_printf("Filename string creation error");
    }

    enter_WishOsJni_monitor();
    int file_id = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, openFileMethodId, java_filename);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_filename);
    (*my_env)->DeleteLocalRef(my_env, serviceClass);

    if (did_attach) {
        detachThread(javaVM);
    }
    return file_id;
}

/*
 * This function is called by the Wish fs abstraction layer when a file is to be closed
 */
int32_t my_fs_close(wish_file_t fileId) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID closeFileMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "closeFile", "(I)I");
    if (closeFileMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    enter_WishOsJni_monitor();
    int ret = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, closeFileMethodId, fileId);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, serviceClass);
    if (did_attach) {
        detachThread(javaVM);
    }
    return ret;
}

/*
 * This function is called when reading a file from Wish
 */
int32_t my_fs_read(wish_file_t fileId, void* buf, size_t count) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID readFileMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "readFile", "(I[BI)I");
    if (readFileMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    /* Create the Java byte array from the memory area we get as 'buf' */
    jbyteArray java_buf = (*my_env)->NewByteArray(my_env, count);

    (*my_env)->SetByteArrayRegion(my_env, java_buf, 0, count, buf);

    enter_WishOsJni_monitor();
    int ret = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, readFileMethodId, fileId, java_buf, count);
    exit_WishOsJni_monitor();

    if (ret > 0) {
        (*my_env)->GetByteArrayRegion(my_env, java_buf, 0, ret, buf);
    }
    (*my_env)->DeleteLocalRef(my_env, java_buf);
    (*my_env)->DeleteLocalRef(my_env, serviceClass);

    if (did_attach) {
        detachThread(javaVM);
    }
    return ret;

}

/*
 * This function is called when writing to a file from Wish
 */
int32_t my_fs_write(wish_file_t fileId, const void* buf, size_t count) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID writeFileMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "writeFile", "(I[BI)I");
    if (writeFileMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    /* Create the Java byte array from the memory area we get as 'buf' */
    jbyteArray java_buf = (*my_env)->NewByteArray(my_env, count);
    (*my_env)->SetByteArrayRegion(my_env, java_buf, 0, count, buf);

    enter_WishOsJni_monitor();
    int ret = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, writeFileMethodId, fileId, java_buf, count);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_buf);
    (*my_env)->DeleteLocalRef(my_env, serviceClass);


    if (did_attach) {
        detachThread(javaVM);
    }
    return ret;

}

/*
 * This function is called by Wish when it wishes to move around in a file
 */
int32_t my_fs_lseek(wish_file_t fileId, wish_offset_t offset, int whence) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID seekFileMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "seekFile", "(III)I");
    if (seekFileMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    enter_WishOsJni_monitor();
    int ret = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, seekFileMethodId, fileId, offset, whence);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, serviceClass);
    if (did_attach) {
        detachThread(javaVM);
    }
    return ret;
}

int32_t my_fs_rename(const char *oldname, const char *newname) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 0;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID renameMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "rename", "(Ljava/lang/String;Ljava/lang/String;)I");
    if (renameMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    jstring java_oldfilename = (*my_env)->NewStringUTF(my_env, oldname);
    if (java_oldfilename == NULL) {
        android_wish_printf("Old filename string creation error");
    }

    jstring java_newfilename = (*my_env)->NewStringUTF(my_env, newname);
    if (java_newfilename == NULL) {
        android_wish_printf("New filename string creation error");
    }

    enter_WishOsJni_monitor();
    int retval = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, renameMethodId, java_oldfilename, java_newfilename);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_oldfilename);
    (*my_env)->DeleteLocalRef(my_env, java_newfilename);
    (*my_env)->DeleteLocalRef(my_env, serviceClass);

    if (did_attach) {
        detachThread(javaVM);
    }
    return retval;
}

int32_t my_fs_remove(const char *file_name) {
    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 0;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID removeMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "remove", "(Ljava/lang/String;)I");
    if (removeMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    jstring java_filename = (*my_env)->NewStringUTF(my_env, file_name);
    if (java_filename == NULL) {
        android_wish_printf("Error creating string!");
    }

    enter_WishOsJni_monitor();
    int retval = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, removeMethodId, java_filename);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_filename);
    (*my_env)->DeleteLocalRef(my_env, serviceClass);

    if (did_attach) {
        detachThread(javaVM);
    }
    return retval;
}

/* This function is called by wish core when it wants to get the IP address of the host - for local discovery purposes */
int wish_get_host_ip_str(wish_core_t* core, char* addr_str, size_t addr_str_len) {

    bool did_attach = false;
    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 1;
    }

    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID getWifiIPMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "getWifiIP", "()Ljava/lang/String;");
    if (getWifiIPMethodId == NULL) {
        android_wish_printf("Method cannot be found");
        return 1;
    }

    enter_WishOsJni_monitor();
    jobject java_string = (*my_env)->CallObjectMethod(my_env, wishOsJniInstance, getWifiIPMethodId);
    exit_WishOsJni_monitor();

    const char *utf8_chars = (*my_env)->GetStringUTFChars(my_env, java_string, false);

    int retval = 0;
    if (strnlen(utf8_chars, addr_str_len) > 0) {
        /* WishOsJni.getWifiIp will return a zero length string if no wifi */
        snprintf(addr_str, addr_str_len, "%s", utf8_chars);
    }
    else {
        retval = 1; //indicate failure
    }

    (*my_env)->ReleaseStringUTFChars(my_env, java_string, utf8_chars);

    if (did_attach) {
        detachThread(javaVM);
    }

    return retval;
}

int wish_get_host_port(wish_core_t* core) {
    return core->wish_server_port;
}

/* Relay control connection */


int relay_control_send_data(int fd, unsigned char* buffer, int buffer_len) {
    /* This will be set to true, if the thread of execution was not a JavaVM thread */
    bool did_attach = false;

    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return 1;
    }

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID sendDataMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "relayControlSendData", "(I[B)I");
    if (sendDataMethodId == NULL) {
        android_wish_printf("Method cannot be found");
        return 1;
    }

    /* See JNI spec: http://docs.oracle.com/javase/6/docs/technotes/guides/jni/spec/functions.html#array_operations */
    jbyteArray java_buffer = (*my_env)->NewByteArray(my_env, buffer_len);
    /* See http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html for information on
     * how the "C" char and "JNI" jchar are the same thing! */
    (*my_env)->SetByteArrayRegion(my_env, java_buffer, 0, buffer_len, (const jbyte *) buffer);

    enter_WishOsJni_monitor();
    int bytes_sent = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, sendDataMethodId, fd, java_buffer);
    exit_WishOsJni_monitor();

    //android_wish_printf("write to socket; and here; return value is %i", bytes_sent);
    if (bytes_sent != buffer_len) {
        android_wish_printf("relay_control_send_data; unexpected: bytes_send %i buffer_len %i", bytes_sent, buffer_len);
    }

    if (did_attach) {
        detachThread(javaVM);
    }

    (*my_env)->DeleteLocalRef(my_env, java_buffer);
    return 0;
}



void port_relay_client_open(wish_core_t* core, wish_relay_client_t *relay, wish_ip_addr_t *relay_ip) {
    /* FIXME If and when we start supporting many concurrent relay control connections,
     * here we could implement a mapping between relay client contexts and Java connectionIds.
     * But for now we just do with one connection */
    
    relay->curr_state = WISH_RELAY_CLIENT_CONNECTING;


    /* Open TCP connection to relay server */

    relay->send = relay_control_send_data;
    /* This will be set to true, if the thread of execution was not a JavaVM thread */
    bool did_attach = false;

    JNIEnv * my_env = NULL;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return;
    }

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID connectMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "relayControlConnect", "([BI)I");
    if (connectMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    int ip_addr_len = 4;
    jbyteArray java_ip = (*my_env)->NewByteArray(my_env, ip_addr_len);
    (*my_env)->SetByteArrayRegion(my_env, java_ip, 0, ip_addr_len, relay_ip->addr);

    enter_WishOsJni_monitor();
    relay->sockfd = (*my_env)->CallIntMethod(my_env, wishOsJniInstance, connectMethodId, java_ip, relay->port);
    exit_WishOsJni_monitor();

    (*my_env)->DeleteLocalRef(my_env, java_ip);

    if (did_attach) {
        detachThread(javaVM);
    }
    process_wish_core();

}

int port_dns_start_resolving_relay_client(wish_core_t* core, wish_relay_client_t *rc, char *qname) {
    enqueue_dns_resolving(core, NULL, rc, qname);
    return 0;
}


void wish_relay_client_open(wish_core_t* core, wish_relay_client_t* relay, uint8_t uid[WISH_ID_LEN]) {
    /* FIXME move these lines to a port-agnosgic common code */
    
    ring_buffer_init(&(relay->rx_ringbuf), relay->rx_ringbuf_storage,
                     RELAY_CLIENT_RX_RB_LEN);
    memcpy(relay->uid, uid, WISH_ID_LEN);
    
    wish_ip_addr_t relay_ip;
    if (wish_parse_transport_ip(relay->host, 0, &relay_ip) == RET_FAIL) {
        /* The relay's host was not an IP address. DNS Resolve first. */
        relay->curr_state = WISH_RELAY_CLIENT_RESOLVING;
        port_dns_start_resolving_relay_client(core, relay, relay->host);
    }
    else {
        relay->curr_state = WISH_RELAY_CLIENT_CONNECTING;
        port_relay_client_open(core, relay, &relay_ip);
    }
}

void wish_relay_client_close(wish_core_t* core, wish_relay_client_t *relay) {
    relay->curr_state = WISH_RELAY_CLIENT_CLOSING;
    JNIEnv * my_env = NULL;
    bool did_attach = false;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return;
    }

    /* For method signature strings, see:
     * http://docs.oracle.com/javase/1.5.0/docs/guide/jni/spec/types.html#wp16432 */
    jclass serviceClass = (*my_env)->GetObjectClass(my_env, wishOsJniInstance);
    jmethodID disconnectMethodId = (*my_env)->GetMethodID(my_env, serviceClass, "relayControlCloseConnection", "(I)V");
    if (disconnectMethodId == NULL) {
        android_wish_printf("Method cannot be found");
    }

    enter_WishOsJni_monitor();
    (*my_env)->CallVoidMethod(my_env, wishOsJniInstance, disconnectMethodId, relay->sockfd);
    exit_WishOsJni_monitor();

    if (did_attach) {
        detachThread(javaVM);
    }
    process_wish_core();
}

#define RELAY_CONTROL_SIG_DISCONNECTED  1 /* Must match definition WishOsJni.relayControlTcpListener.RELAY_CONTROL_SIG_DISCONNECTED */
#define RELAY_CONTROL_SIG_CONNECTED 2 /* Must match definition WishOsJni.relayControlTcpListener.RELAY_CONTROL_SIG_CONNECTED */

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    relayControlSignalEvent
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_relayControlSignalEvent(JNIEnv *env, jobject jthis, jint id, jint sig) {

    wish_relay_client_t* relay = wish_relay_client_get_by_id(id);
    if (relay == NULL) {
        android_wish_printf("relay is NULL");
        return;
    }

    switch (sig) {
    case RELAY_CONTROL_SIG_DISCONNECTED:
        android_wish_printf("Relay control connection disconnected!");
        relay_ctrl_disconnect_cb(core, relay);
        break;
    case RELAY_CONTROL_SIG_CONNECTED:
        android_wish_printf("Relay control connection established, id=%i", id);
        relay_ctrl_connected_cb(core, relay);
        wish_relay_client_periodic(core, relay);
        break;
    default:
        android_wish_printf("Unknown relay control signal!");
        break;
    }
    process_wish_core();
}

/*
 * Class:     fi_ct_wish_os_WishOsJni
 * Method:    relayControlFeed
 * Signature: ([B)V
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_relayControlFeed(JNIEnv *env, jobject jthis, jint id, jbyteArray java_buffer) {

    if (wishOsJniInstance == NULL) {
        return;
    }

    wish_relay_client_t* relay = wish_relay_client_get_by_id(id);
    if (relay == NULL) {
        android_wish_printf("relay is NULL");
        return;
    }


    int buffer_len = (*env)->GetArrayLength(env, java_buffer);
    jbyte *native_buffer = calloc(buffer_len, 1);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, "relay client", "native buffer allocation fails!");
        return;
    }
    (*env)->GetByteArrayRegion(env, java_buffer, 0, buffer_len, native_buffer);

    wish_relay_client_feed(core, relay, native_buffer, buffer_len);

    wish_relay_client_periodic(core, relay);
    free(native_buffer);
    process_wish_core();
}

int enter_WishOsJni_monitor(void) {

    JNIEnv * my_env = NULL;
    bool did_attach = false;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }

    if ((*my_env)->MonitorEnter(my_env, wishOsJniInstance) != 0) {
        android_wish_printf("Error while entering monitor");
        return -1;
    }

    if (did_attach) {
        detachThread(javaVM);
    }
    return 0;
}

int exit_WishOsJni_monitor(void) {
    JNIEnv * my_env = NULL;
    bool did_attach = false;
    if (getJNIEnv(javaVM, &my_env, &did_attach)) {
        android_wish_printf("Method invocation failure, could not get JNI env");
        return -1;
    }

    if ((*my_env)->MonitorExit(my_env, wishOsJniInstance) != 0) {
        android_wish_printf("Error while exiting monitor");
        return -1;
    }

    if (did_attach) {
        detachThread(javaVM);
    }
    return 0;
}

/**
 *
 * @param java_ip_bytes The result as a Java byte[] in network byte order: the highest order byte of the address is in element 0
 */
JNIEXPORT void JNICALL Java_fi_ct_wish_os_WishOsJni_dnsResolvingCompleted
        (JNIEnv *env, jobject jthis, jint resolve_id, jbyteArray java_ip_bytes, jint java_ip_length) {
    
    wish_connection_t *conn = NULL;
    wish_relay_client_t *relay = NULL;
    wish_core_t *core = NULL;
    
    struct resolve_data *rdata;
    struct resolve_data *tmp;
    
    bool rdata_found = false;
    LL_FOREACH_SAFE(resolver_list, rdata, tmp) {
        if (rdata->resolve_id == resolve_id) {
            conn = rdata->conn;
            relay = rdata->relay;
            core = rdata->core;
            LL_DELETE(resolver_list, rdata);
            free(rdata);
            rdata_found = true;
        }
    }
    
    if (!rdata_found) {
        __android_log_print(ANDROID_LOG_DEBUG, "my_native", "rdata not found in Java_fi_ct_wish_os_WishOsJni_dnsResolvingCompleted!");
        return;
    }
    
    assert(core);
    
    if (relay != NULL) {
        assert(conn == NULL);
    }
    
    if (conn != NULL) {
        assert(relay == NULL);
    }
    
    if (java_ip_bytes == NULL || java_ip_length == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, "my_native", "Resolving fails, Java_fi_ct_wish_os_WishOsJni_dnsResolvingCompleted!");
        if (conn) {
            wish_core_signal_tcp_event(core, conn, TCP_DISCONNECTED);
        }
        else {
            relay_ctrl_disconnect_cb(core, relay);
        }
        return;
    }
    
    assert(java_ip_length == 4);
    
    uint8_t *native_buffer = calloc(java_ip_length, 1);
    if (native_buffer == NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, "my_native", "native buffer allocation fails in Java_fi_ct_wish_os_WishOsJni_dnsResolvingCompleted!");
        return;
    }
    
    (*env)->GetByteArrayRegion(env, java_ip_bytes, 0, java_ip_length, native_buffer);
    
    wish_ip_addr_t ip;
    memset(&ip, 0, sizeof(wish_ip_addr_t));
  
    ip.addr[0] = native_buffer[0];
    ip.addr[1] = native_buffer[1];
    ip.addr[2] = native_buffer[2];
    ip.addr[3] = native_buffer[3];
    
    
    
    if (conn != NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, "my_native", "Resolving success for resolver_id %i, conn %p (conn id %i), relay %p", resolve_id, conn, conn->connection_id, relay);
        wish_open_connection(core, conn, &ip, conn->remote_port, conn->via_relay);
    }
    else if (relay != NULL) {
        __android_log_print(ANDROID_LOG_DEBUG, "my_native", "Resolving success for resolver_id %i, conn %p, relay %p", resolve_id, conn, relay);
        port_relay_client_open(core, relay, &ip);
    }
    
    free(native_buffer);
}

