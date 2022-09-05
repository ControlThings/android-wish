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
package fi.ct.wish;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import static android.app.PendingIntent.FLAG_IMMUTABLE;
import android.app.RemoteInput;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.NotificationCompat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.Iterator;

import fi.ct.bridge.CoreBridge;
import fi.ct.wish.bridge.WishCoreBridge;
import fi.ct.wish.bridge.WishCoreJni;
import fi.ct.wish.os.WishOsJni;


public class Wish extends Service {

    private final String TAG = "Wish_class";
    // private int notificationId = 102;
    // private String notificationChanel = "wish_notification";
    // private NotificationManager notificationManager;

    WishCoreJni wishCoreJni;
    WishOsJni wishOsJni;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.v(TAG, "in onStartCommand");
        return Service.START_STICKY;
    }

    @Override
    public void onCreate() {
        wishCoreJni = new WishCoreJni(this);
        wishOsJni = new WishOsJni(this, wishCoreJni.wishCoreBridge);

        IntentFilter filter = new IntentFilter("fi.ct.wish.KILL_WISH");
        registerReceiver(receiver, filter);
        Intent close = new Intent("fi.ct.wish.KILL_WISH");
        PendingIntent pClose = PendingIntent.getBroadcast(this, 0, close, FLAG_IMMUTABLE);

        /*
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);


        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            NotificationChannel channel = new NotificationChannel(notificationChanel, "wish", NotificationManager.IMPORTANCE_HIGH);
            notificationManager.createNotificationChannel(channel);

            startForeground(notificationId,  new Notification.Builder(this, notificationChanel)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.thin_1563_user_shared_connected_relations))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getResources().getString(R.string.notification_title))
                    .setContentText(getResources().getString(R.string.notification_text))
                    .setContentIntent(pClose)
                    .build());
        } else {

            Notification notification = new Notification.Builder(this)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.thin_1563_user_shared_connected_relations))
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle(getResources().getString(R.string.notification_title))
                    .setContentText(getResources().getString(R.string.notification_text))
                    .setContentIntent(pClose)
                    .build();
            notification.flags = Notification.FLAG_NO_CLEAR;
            notificationManager.notify(notificationId, notification);

        } */


        Log.v(TAG, "in onCreate");
        super.onCreate();
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.v(TAG, "in onBind intent");
        return wishCoreJni.getBridge(intent);
    }

    @Override
    public void onRebind(Intent intent) {
        Log.v(TAG, "in onRebind");
        super.onRebind(intent);

    }

    @Override
    public void onTaskRemoved(Intent intent) {
        Log.v(TAG, "in onTaskRemoved");

    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.v(TAG, "in onUnbind");
        stopWish();
        return true;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.v(TAG, "in onDestroy");
        if (receiver != null) {
            unregisterReceiver(receiver);
            receiver = null;
        }

        wishOsJni.cleanup();
        //android.os.Process.killProcess(android.os.Process.myPid());
    }

    public void stopWish() {
        Log.d(TAG, "in stopWish");
        wishCoreJni.getWishCoreBridge().shutdownAppToCoreWorker();
        wishCoreJni.getWishCoreBridge().releaseAll();
     /*   notificationManager.cancel(notificationId);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
            notificationManager.deleteNotificationChannel(notificationChanel);
        }
        */
        stopSelf();
    }

    BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            stopWish();
        }
    };
}
