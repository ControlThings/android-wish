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

import android.os.IBinder;
import android.os.RemoteException;
import android.support.v4.util.ArrayMap;
import android.util.Log;

import java.util.Map;

/**
 * Created by jeppe on 12/1/16.
 */

public class ClientDeathWatcher {

    private final String TAG = "ClientDeathWatcher";

    private ArrayMap<byte[], DeathCallBack> mCallbacks = new ArrayMap<>();
    private WishCoreJni wishCoreJni;
    private final static int releaseCore = 1;

    public ClientDeathWatcher(WishCoreJni jni) {
        this.wishCoreJni = jni;
    }

    private final class DeathCallBack implements IBinder.DeathRecipient {
        private byte[] wsid;
        private IBinder mBinder;

        DeathCallBack(byte[] wsid ,IBinder binder) {
            this.wsid = wsid;
            mBinder = binder;
        }

        public void binderDied() {
            Log.d(TAG, "binderDied");
            synchronized (mCallbacks) {
                mBinder.unlinkToDeath(this,0);
                mCallbacks.remove(wsid);
            }
            wishCoreJni.closeService(wsid);
        }

        public void release() {
            Log.d(TAG, "release");
            try {
                mBinder.transact(releaseCore, android.os.Parcel.obtain(), android.os.Parcel.obtain(), 0);
            } catch (RemoteException e) {
                Log.d(TAG, "Release exeption " + e);
            }
        }
    }

    public void releaseAll() {
        for (Map.Entry<byte[], DeathCallBack> entry: mCallbacks.entrySet()) {
            DeathCallBack callBack = entry.getValue();
            callBack.release();
        }
    }

    public boolean register(IBinder token, byte[] wsid) {
        synchronized (mCallbacks) {
            try {
                if (!mCallbacks.containsKey(wsid)) {
                    DeathCallBack mDeathCallBack = new DeathCallBack(wsid,token);
                    mCallbacks.put(wsid, mDeathCallBack);
                    //This is where the magic happens
                    token.linkToDeath(mDeathCallBack, 0);
                }
                return true;
            } catch (RemoteException e) {
                e.printStackTrace();
                return false;
            }
        }
    }


}
