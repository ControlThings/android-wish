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

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public class TcpClient {

    private final String TAG = "TcpClient";

    private TcpListener _listener;
    private Connections _connections;


    public TcpClient(TcpListener listener, Connections connections) {
        this._listener = listener;
        this._connections = connections;
    }



    public void start(byte[] address, int port, int id) {
        CommunicationThread commThread = new CommunicationThread(address, port, _listener, _connections, id, false);
        Thread thread = new Thread(commThread);
        thread.setDaemon(true);
        thread.start();
        commThread.setActualThread(thread);

    }



}
