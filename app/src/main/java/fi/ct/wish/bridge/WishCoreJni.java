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
package fi.ct.wish.bridge;

import android.content.Intent;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import fi.ct.bridge.CoreBridge;
import fi.ct.wish.Util;
import fi.ct.wish.Wish;

public class WishCoreJni {

    /*
    From the documentation for Runtime.loadLibrary(String), which is called by System.loadLibrary(String):

   If this method is called more than once with the same library name,
   the second and subsequent calls are ignored.
     */

    static {
        System.loadLibrary("wish");
    }


    private final String TAG = "WishCoreJni";

    public WishCoreBridge wishCoreBridge;

    private Wish wish;

    Wish getWish() {
        return wish;
    }

    public WishCoreJni(Wish wish) {
        Log.d(TAG, "MistBridge started");
        wishCoreBridge = new WishCoreBridge(this);
        register_core_bridge(wishCoreBridge);
        this.wish = wish;
    }

    public native void register_core_bridge(WishCoreBridge wcb);
    public native void jni_receive_app_to_core(byte[] wsid, byte buffer[]);
    public native void remove_service(byte[] wsid);

    public WishCoreBridge getWishCoreBridge() {
        return wishCoreBridge;
    }

    /**
     * Handle the connection between wish android app and mist android app
     * @return
     */
    public CoreBridge.Stub getBridge(Intent intent) {
        Log.d(TAG, "Registering bridge to Mist");


        return wishCoreBridge.getBridge(intent);
    }

    public void closeService(byte[] wsid) {
        if (wsid != null) {
            Log.d(TAG, "Removing service: " + Util.prettyPrintBytes(wsid));
            remove_service(wsid);
            wishCoreBridge.removeFromAppList(wsid);
        } else {
            Log.d(TAG, "Could not remove service because WSID is unknown!");
        }
    }
}
