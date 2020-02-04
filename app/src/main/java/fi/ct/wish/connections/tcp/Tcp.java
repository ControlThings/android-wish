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

import fi.ct.wish.os.WishOsJni;

public class Tcp {

    private final String TAG = "Tcp";

    private TcpListener _listener;

    TcpServer tcpServer;
    TcpClient tcpClient;
    public Connections connections;

    public static final int TCP_CONNECTED = 0;  //defined in wish/wish_io.h enum tcp_event
    public static final int TCP_DISCONNECTED = 1;  //defined in wish/wish_io.h enum tcp_event
    public static final int TCP_CLIENT_CONNECTED = 2;  //defined in wish/wish_io.h enum tcp_event
    public static final int TCP_CLIENT_DISCONNECTED = 3;  //defined in wish/wish_io.h enum tcp_event

    public Tcp(TcpListener listener) {
        this._listener = listener;
        connections = new Connections();
        tcpServer = new TcpServer(listener, connections);
        tcpClient = new TcpClient(listener, connections);
    }

    public void listen(int port, WishOsJni jni) {
        Log.d(TAG, "port: " + port);
        tcpServer.start(port, jni);
    }

    public void connect(byte[] address, int port, int id) {
        tcpClient.start(address, port, id);
    }

    /**
     * Close communication thread identified by id
     *
     * @param id The ID of the CommunicationThread we wish to close
     * @return true, if the id was valid. False if there were no such thread
     */
    public boolean closeConnection(int id) {
        CommunicationThread connection = null;
        connection = connections.getConnection(id);
        if (connection != null) {
            connection.stop();
            return true;
        }
        else {
            Log.d(TAG, "close connection: Cannot get communication thread id " + id);
            return false;
        }
    }

    public void cleanup() {
        tcpServer.stop();
    }

    public void sendMessage(byte[] buffer, int id) {
        CommunicationThread connection = null;
        connection = connections.getConnection(id);
        if (connection != null) {
            connection.sendMessage(buffer);
        }
        else {
            Log.d(TAG, "Error! There is no communication thread with id " + id);
        }
    }

}
