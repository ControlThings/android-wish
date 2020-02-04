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
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;

public class UdpServer extends AsyncTask<Integer, DatagramPacket, String> {

    private final String TAG = "UdpServer";

    private DatagramSocket socket = null;
    private int msgSize = 512;
    private int port = 9090;

    private UdpListener _listener;

    public UdpServer(UdpListener listener) {
        this._listener = listener;
    }

    @Override
    protected void onPreExecute() {
        Log.d(TAG, "onPreExecute: ");
    }


    @Override
    protected String doInBackground(Integer... params) {
        Log.d(TAG, "doInBackground: ");
        return server();
    }

    private String server() {
        byte[] msg = new byte[msgSize];
        DatagramPacket packet = new DatagramPacket(msg, msg.length);
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);

            if (socket.getReuseAddress() == false) {
                Log.d(TAG, "Wld warning: We could not set SO_REUSEADDR socket option! Receive UDP broadcasts may not work.");
            }
            socket.bind(new InetSocketAddress(port));
            while (!isCancelled()) {
                socket.receive(packet);

                //Log.d(TAG, "Wld got data from address " + packet.getAddress() + " port: " + packet.getPort());
                _listener.onLocalDiscoveryData(packet.getAddress().getAddress(), packet.getPort(), packet.getData());
            }
        } catch (Exception e) {
            return "End";
        }
        return "End";
    }


    public void stop() {
        Log.d(TAG, "in stop");
        if (socket != null) {
            try {
                cancel(true);
                socket.close();
            } catch (Exception e) {
            }
        }
    }


    /**
     * This function returns all the IP addresses on all the interfaces on this host
     *
     * @param ignoreLoopbackAddrs if true, loopback addresses will not be added to the list
     * @return an ArrayList of InetAddress representing the IP addresses on this host
     * @see fi.ct.wish.os.WishOsJni.getWifiIP()
     *
     */
    public ArrayList<InetAddress> getLocalIpAddresses(boolean ignoreLoopbackAddrs) {
        ArrayList<InetAddress> addressList = new ArrayList<InetAddress>();
        try {
            for (Enumeration<NetworkInterface> ifaceEnum = NetworkInterface.getNetworkInterfaces(); ifaceEnum.hasMoreElements(); ) {
                NetworkInterface iface = ifaceEnum.nextElement();
                for (Enumeration<InetAddress> ifaceAddrEnum = iface.getInetAddresses(); ifaceAddrEnum.hasMoreElements(); ) {
                    InetAddress inetAddress = ifaceAddrEnum.nextElement();
                    if (inetAddress.isLoopbackAddress()) {
                        if (!ignoreLoopbackAddrs) {
                            addressList.add(inetAddress);
                        }
                    } else {
                        addressList.add(inetAddress);
                    }

                }
            }
        } catch (SocketException ex) {

            Log.d(TAG, "While getting local IP addresses: " + ex);
        }
        return addressList;
    }

    @Override
    protected void onPostExecute(String res) {
        Log.d(TAG, "onPostExecute: " + res);

    }
}

