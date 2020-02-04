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
package fi.ct.wish.connections.tcp;

import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

import fi.ct.wish.os.WishOsJni;

public class TcpServer implements Runnable {

    private final String TAG = "TcpServer";

    private TcpListener _listener;
    private Connections _connections;

    private int port;

    private ServerSocket serverSocket = null;
    private boolean running = true;
    private WishOsJni _jni;


    public TcpServer(TcpListener listener, Connections connections) {
        this._listener = listener;
        this._connections = connections;
    }

    public void start(int port, WishOsJni jni) {
        this._jni = jni;
        this.port = port;
        Thread serverThread = new Thread(this);
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void run() {

        Socket socket = null;
        if (!running) {
            Log.d(TAG, "Server Stopped.");
            return;
        }

        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
        }
        catch (IOException ioe) {
            Log.d(TAG, "Error creating server socket: " + ioe);
            _listener.onListenError();
        }
        while (running) {
            try {
                socket = serverSocket.accept();
                socket.setTcpNoDelay(true); // Disable TCP Nagle algorithm
            } catch (IOException e) {
                Log.d(TAG, "Error accepting: " + e);
                _listener.onListenError();
                continue;
            }
            /* Call WishOsJni.acceptServerConnection(), which will return the connection_id */
            int id = _jni.acceptServerConnection();
            if (id < 0) {
                Log.d(TAG, "Could not accept the connection for Wish communications");
                try {
                    socket.close();
                }
                catch (Exception e){
                    Log.d(TAG, "Error closing socket: " + e);
                }
            }
            else {
                CommunicationThread commThread = new CommunicationThread(socket, _listener, _connections, id, true);
                Thread thread = new Thread(commThread);
                thread.setDaemon(true);
                thread.start();
                commThread.setActualThread(thread);
            }
        }


    }

    public void stop() {
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.d(TAG, "Error closeing socket: " + e);
        }
        serverSocket = null;
        running = false;
    }
}
