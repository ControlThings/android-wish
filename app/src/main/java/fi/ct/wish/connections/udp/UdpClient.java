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
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Enumeration;

public class UdpClient extends AsyncTask<byte[], Integer, String> {
    private final String TAG = "UdpClient";


    private DatagramSocket socket = null;
    private DatagramPacket packet;

    private int port = 9090;
    private InetAddress address;


    public UdpClient() {
        //Log.d(TAG, "in constructor");

    }

    @Override
    protected void onPreExecute() {
        //Log.d(TAG, "onPreExecute: ");
        try {
            address = InetAddress.getByName("255.255.255.255");
        } catch (UnknownHostException e) {
            Log.d(TAG, "Not a valid address: " + e);
        }
        //Log.d(TAG, "onPreExecute exit");
    }


    @Override
    protected String doInBackground(byte[]... params) {
        //Log.d(TAG, "inBackground: " + params[0]);
        byte[] msg = params[0];
        return client(msg);
    }

    private String client(byte[] msg) {
        /* Get each interface's broadcast address, and send specifically to that */
        try {
            Enumeration interfaces = NetworkInterface.getNetworkInterfaces();

            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface networkInterface = (NetworkInterface) interfaces.nextElement();

                    if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    /* No broadcasting to loopback interfaces or interfaces which are down */
                        continue;
                    }

                    for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                        InetAddress broadcast = interfaceAddress.getBroadcast();
                        if (broadcast == null) {
                            continue;
                        }

                        try {
                            socket = new DatagramSocket();
                            socket.setBroadcast(true);
                            packet = new DatagramPacket(msg, msg.length, broadcast, port);
                            //Log.d(TAG, "sending to: " + address);
                            socket.send(packet);
                            socket.close();
                            //Log.d(TAG, "Wld packet sent to: " + broadcast.getHostAddress() + "; Interface: " + networkInterface.getDisplayName());
                        } catch (Exception e) {
                            Log.d(TAG, "Exception while sending broadcast, iface " + networkInterface.getDisplayName() + ": " + e);
                        }
                    }
                }
            } else {
                Log.d(TAG, "NetworkInterface.getNetworkInterfaces() returns null");
            }
        } catch (SocketException e) {
            Log.d(TAG, "Socket exception: " + e);
            return "error";
        }
        return "Executed";
    }


    @Override
    protected void onPostExecute(String res) {
        //Log.d(TAG, "onPostExecute: " + res);
        if (socket != null) {
            socket.close();
        }
    }

    @Override
    protected void onProgressUpdate(Integer... params) {
        //Log.d(TAG, "onProgressUpdate: " + params[0]);

    }

}
