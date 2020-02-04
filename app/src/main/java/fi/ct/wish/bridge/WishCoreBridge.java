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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import fi.ct.bridge.AppBridge;
import fi.ct.bridge.CoreBridge;
import fi.ct.wish.Util;
import fi.ct.wish.connections.tcp.TcpServer;
import fi.ct.wish.os.WishOsJni;

import static fi.ct.wish.Util.*;

public class WishCoreBridge {

    private final String TAG = "WishCoreBridge";

    private volatile ClientDeathWatcher clientDeathWatcher;
    private boolean appToCoreWorkerRunning = false;

    boolean mBound = false;

    WishCoreJni _jni;

    /** A reference to osJni is required because it acts as a synchronisation primitive for all methods that access the wish-core */
    WishOsJni osJni;

    public void setOsJni(WishOsJni osJni) {
        this.osJni = osJni;
    }

    private class AppListEntry {
        private byte[] wsid;
        private AppBridge appBridge;

        AppListEntry(byte[] wsid, AppBridge appBridge) {
            this.wsid = wsid;
            this.appBridge = appBridge;
        }

        boolean compareToWSID(byte[] otherWsid) {
            return Arrays.equals(this.wsid, otherWsid);
        }
    }

    private class AppList {
        LinkedList<AppListEntry> appList;

        AppList() {
            appList = new LinkedList<>();
        }

        boolean containsWSID(byte[] wsid) {
            synchronized (appList) {
                for (AppListEntry entry : appList) {
                    if (entry.compareToWSID(wsid)) {
                        return true;
                    }
                }
                return false;
            }
        }

        AppBridge getAppBridge(byte[] wsid) {
            synchronized (appList) {
                for (AppListEntry entry : appList) {
                    if (entry.compareToWSID(wsid)) {
                        return entry.appBridge;
                    }
                }
                return null;
            }
        }

        boolean add(byte[] wsid, AppBridge appBridge) {
            Log.d(TAG, "in AppList.add");
            listEntries();
            Log.d(TAG, "Adding entry: " + Util.prettyPrintBytes(wsid));
            synchronized (appList) {
                if (containsWSID(wsid)) {
                    return false;
                }
                appList.add(new AppListEntry(wsid, appBridge));

                return true;
            }
        }

        boolean remove(byte[] wsid) {
            Log.d(TAG, "in AppList.remove");
            listEntries();
            Log.d(TAG, "Removing entry: " + Util.prettyPrintBytes(wsid));
            synchronized (appList) {
                LinkedList<AppListEntry> toRemove = new LinkedList<>();
                for (AppListEntry entry : appList) {
                    if (entry.compareToWSID(wsid)) {
                        toRemove.add(entry);
                    }
                }
                return appList.removeAll(toRemove);
            }
        }

        void listEntries() {
            synchronized (appList) {
                for (AppListEntry entry : appList) {
                    Log.d(TAG, "Applist entry: " + Util.prettyPrintBytes(entry.wsid));
                }
            }
        }
    }

    AppList appList = new AppList();

    ConcurrentLinkedQueue<AppToCoreMessage> appToCoreFIFO;
    Thread appToCoreWorker = new Thread(new Runnable() {
        @Override
        public void run() {
            while (appToCoreWorkerRunning) {
                /* Process at most 100 app-to-core messages until we must sleep */
                boolean hasMoreMessages = false;
                if (osJni != null) {
                    synchronized (osJni) {
                        hasMoreMessages = processAppToCore();
                    }
                }
                synchronized (appToCoreWorker) {
                    try {
                        if (hasMoreMessages == false) {
                            appToCoreWorker.wait();
                        }
                    } catch (InterruptedException ie) {
                        Log.d(TAG, "appToCoreWorker unexpectedly interrupted!");
                    }
                }
            }
        }
    });

    public void notifyAppToCoreWorker() {
        synchronized (appToCoreWorker) {
            appToCoreWorker.notify();
        }
    }

    public WishCoreBridge(WishCoreJni jni) {
        this._jni = jni;
        clientDeathWatcher = new ClientDeathWatcher(jni);
        appToCoreFIFO = new ConcurrentLinkedQueue<>();
        appToCoreWorkerRunning = true;
        appToCoreWorker.setDaemon(true);
        appToCoreWorker.start();
    }

    /** Process one message from the appToCoreFIFO (it there are any).
     *
     * @return true, if the queue has more messages waiting
     */
    public boolean processAppToCore() {
        AppToCoreMessage msg = appToCoreFIFO.poll();
        if (msg != null) {
            _jni.jni_receive_app_to_core(msg.getWsid(), msg.getData());
        }
        return !appToCoreFIFO.isEmpty();
    }


    /**
     * This function is called by the Wish C99 core when it wants to send data to a Wish service.
     *
     * @param wsid TODO this was int, but should be byte[]
     * @param data
     */
    public void receiveCoreToApp(byte[] wsid, byte[] data) {
        //Log.d(TAG, "in receive core to app");
        if (!mBound) {
            Log.d(TAG, "Not bound!  Scream and shout very loudly!");
            /* FIXME Send down to core a notification that the app (service) has gone off line. */
            return;
        }


        if (!appList.containsWSID(wsid)) {
            Log.d(TAG, "WSID not found in appList:  len" + wsid.length + " " + Util.prettyPrintBytes(wsid));

            /* FIXME Send down to core a notification that the app (service) does not exist */
            return;
        }

        try {
            //Log.d(TAG, "Calling send core to app");
            AppBridge _appBridge;

            _appBridge = appList.getAppBridge(wsid);


            if (_appBridge == null) {
                Log.d(TAG, "AppBridge is null!");
            }
            else {
                _appBridge.sendCoreToApp(data);
            }
        } catch (RemoteException e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

            Log.d(TAG, "Remote exception, message: Stack trace:" + sw.toString());
            if (e.getMessage() != null) {
                Log.d(TAG, "Remote exception message: " + e.getMessage());
            }
            else {
                Log.d(TAG, "Remote exception message was null");
            }
            mBound = false;
        } catch (Exception e) {
            Log.d(TAG, "Some other very unexpected exception occurred!");
        }


    }

    public void removeFromAppList(byte[] wsid) {
        //Log.d(TAG, "Removing from applist: " + Util.prettyPrintBytes(wsid));

        if (appList.remove(wsid) == false) {
            Log.d(TAG, "WARNING FAIL trying to remove something unknown from appList: + " + Util.prettyPrintBytes(wsid));
        }
    }

    public void releaseAll() {
        clientDeathWatcher.releaseAll();
    }

    public void shutdownAppToCoreWorker() {
        appToCoreWorkerRunning = false;
        notifyAppToCoreWorker();
    }

    public CoreBridge.Stub getBridge(Intent intent) {
        return new CoreBridge.Stub() {

            @Override
            public void sendAppToCore(byte[] wsid, byte[] data) throws RemoteException {
                appToCoreFIFO.add(new AppToCoreMessage(wsid, data));
                synchronized (appToCoreWorker) {
                    appToCoreWorker.notify();
                }
            }

            @Override
            public void register(IBinder cb, byte[] wsid, AppBridge service) {
                Log.d(TAG, "in register()");
                clientDeathWatcher.register(cb, wsid);
                mBound = true;

                synchronized (appList) {
                    if (appList.containsWSID(wsid)) {
                        Log.d(TAG, "WARNING: WSID was already on appList: " + Util.prettyPrintBytes(wsid));
                        _jni.closeService(wsid);
                    }
                    //Log.d(TAG, "Adding WSID to appList " + Util.prettyPrintBytes(wsid));
                    appList.add(wsid, service);
                }
            }
        };
    }

    private void requestWishCoreServiceClose() {
        _jni.getWish().stopWish();
    }

}