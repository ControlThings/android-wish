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
package fi.ct.wish.connections.udp;

import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.util.Log;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

public class Udp {

    private final String TAG = "Udp";

    UdpServer udpServer = null;
    UdpClient udpClient = null;


    public Udp(UdpListener listener) {
        udpServer = new UdpServer(listener);

    }



    public void udpServer(boolean activate) {
        //Log.d(TAG, "in udpServer state: " + activate + udpServer);
        if (activate == true) {
            Log.d(TAG, "execute");
            udpServer.execute();
        } else {
            udpServer.onPostExecute("end");
        }

    }

    public void sendBroadcastMessage(byte[] msg) {
        //Log.d(TAG, "in sendBroadcastMessage");
        if (udpClient != null) {
            if (!udpClient.isCancelled()) {
                udpClient.cancel(true);
            }
        }

        udpClient = new UdpClient();
        /* See on execute() vs. executeOnExecutor() on http://stackoverflow.com/questions/4068984/running-multiple-asynctasks-at-the-same-time-not-possible */
        udpClient.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, msg);

    }

    public void cleanup() {

        //Log.d(TAG, "cleanup");

        if (udpClient != null) {
            if (!udpClient.isCancelled()) {
                //Log.d(TAG, "cancel client");
                udpClient.cancel(true);
            }
        }
        if (udpServer != null) {
            udpServer.stop();
           /*
            if (!udpServer.isCancelled()) {
                Log.d(TAG, "cancel server");
                udpServer.cancel(true);
            }*/
        }
    }
}
