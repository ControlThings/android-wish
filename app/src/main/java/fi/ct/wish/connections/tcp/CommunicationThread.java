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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

public class CommunicationThread implements Runnable {
    private final int CONNECT_TIMEOUT = 10*1000;    /* connect() timetout in Milliseconds */

    private final String TAG = "CommunicationThread";

    private Socket socket;
    private TcpListener listener;
    private Connections connections;

    private int id = 0;
    public int getId() {
        return id;
    }

    private Thread actualThread;

    public void setActualThread(Thread actualThread) {
        this.actualThread = actualThread;
    }

    public void waitForThreadToStop() {
        try {
            if (actualThread != null) {
                actualThread.join();
            }
        } catch (InterruptedException ie) {
            Log.d(TAG, "InterruptedException while waiting for thread to join");
        }
    }

    private InputStream inputStream;
    private OutputStream outputStream;
    private boolean running = true;

    private byte[] address;
    private int port;

    /** This flag is (re)set to false if we use the constructor where we supply the address as IP byte array and port.
     * This signals the thread to first initialise Socket based on address and port, before starting with normal business.
     * The reason is that when we initialise a CommunicationThread we might not be allowed to instantiate a Socket (no networking is allowed on Main thread!)
     */
    private boolean socketInitialised = false;
    private boolean isServer;

    public CommunicationThread(byte[] address, int port, TcpListener listener, Connections connections, int id, boolean isServer) {
        /* XXX this is not too cool */
        this(null, listener, connections, id, isServer);
        socketInitialised = false;
        this.address = address;
        this.port = port;

    }

    public CommunicationThread(Socket socket, TcpListener listener, Connections connections, int id, boolean isServer) {
        this.socket = socket;
        this.listener = listener;
        this.connections = connections;
        this.id = id;

        connections.setConnection(this, id);
        socketInitialised = true;
        this.isServer = isServer;
    }

    /** This function is used right at the beginning of run() method to convert any addresses in byte buffers and port numbers to a Java Socket.
     * The reason for this odd construction is that you are not allowed to perform these operations on the main thread in Andoid.
     *
     * @return true, if the socket was successfully initted
     */
    private boolean initSocket() {
        if (address == null) {
            Log.d(TAG, "Address is null!");
            return false;
        }

        InetAddress ipAddr;
        try {
            ipAddr = InetAddress.getByAddress(address);
        } catch (UnknownHostException uhe) {
            Log.d(TAG, "Unknown host exception when getByAddress");
            return false;
        }

        socket = new Socket();

        try {
            socket.connect(new InetSocketAddress(ipAddr, port), CONNECT_TIMEOUT); /* This blocks until timeout */
        } catch (SocketTimeoutException ste) {
            Log.d(TAG, "Socket timeout exception when connecting socket; msg: " + ste.getMessage() + " toString " + ste.toString());
            return false;
        } catch (IOException ioe) {
            Log.d(TAG, "IOexception when connecting socket; msg: " + ioe.getMessage() + " toString " + ioe.toString());
            return false;
        }
        /* If we get here, the socket is connected. Success. */

        return true;
    }

    public void run() {
        /* Check if we need initialisation of socket etc. */
        if (!socketInitialised) {
            boolean success = initSocket();
            if (!success) {
                Log.d(TAG, "Error: Socket init fail");

                try {
                    socket.close();
                } catch (IOException ioe) {
                    Log.d(TAG, "IOException while closing socket");
                }
                listener.onConnectionError(id);
                return;
            }

            if (socket == null) {
                Log.d(TAG, "Error: Socket is null when it should not!");
                listener.onConnectionError(id);
                try {
                    socket.close();
                } catch (IOException ioe) {
                    Log.d(TAG, "IOException while closing socket");
                }
                listener.onConnectionError(id);
                return;
            }
        }

        if (socket == null) {
            Log.d(TAG, "Error: Socket is null!");
            listener.onError(id);
            return;
        }

        try {
            inputStream = socket.getInputStream();
        } catch (IOException ioe) {
            Log.d(TAG, "IOException when getting input stream");
            try {
                socket.close();
            } catch (IOException ioec) {
                Log.d(TAG, "IOException while closing socket");
            }
            listener.onError(id);
            return;
        }

        try {
            outputStream = socket.getOutputStream();
        } catch (IOException ioe) {
            Log.d(TAG, "IOException when getting output stream");
            try {
                socket.close();
            } catch (IOException ioec) {
                Log.d(TAG, "IOException while closing socket");
            }
            listener.onError(id);
            return;
        }

        connectionEstablished(isServer);


        try {
            socket.setTcpNoDelay(true);
        } catch (SocketException se) {
            Log.d(TAG, "While setting tcpNoDelay: " + se.getMessage());
            try {
                socket.close();
            } catch (IOException ioe) {
                Log.d(TAG, "IOException while closing socket");
            }
            listener.onError(id);
            return;
        }

        Thread.currentThread().setName("CommunicationThread: " + id);
        /* Thread main loop */
        while (getRunningStatus()) {
            /* Here we query over JNI how much space is available in the WishCore ring buffer of this connection */
            int rbSpace = listener.getMaxInputDataLength(id);
            if (rbSpace < 0) {
                Log.d(TAG, "JNI says rbSpace < 0, just exiting thread");
                this.stop();
                continue;
            } else if (rbSpace == 0) {
                Log.d(TAG, "JNI says RB is full, sleeping");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Log.d(TAG, "Sleeping was interrupted while waiting for rb space to free up");
                }
                continue;
            } else {
                /* There is at least some space in ring buffer */
                /* Try to read as much as the ring buffer can take.  */
                int readLen = rbSpace; /* Note that here rbSpace is > 0 */
                byte buffer[] = new byte[readLen];
                int numBytesRead = 0;
                try {
                    if (inputStream.available() > 0) {
                        numBytesRead = inputStream.read(buffer, 0, readLen); /* Blocks until at least one byte can be read */

                    } else {
                        try {
                            Thread.sleep(100); //numBytesRead will be 0 after sleeping
                        } catch (InterruptedException ie) {
                            Log.d(TAG, "Interrupted!");
                        }
                    }
                }
                catch (IOException ioe) {
                    Log.d(TAG, "IOException while reading: " + ioe.getMessage() + "Num byte read: " + numBytesRead + " buffer.len " + buffer.length);
                    this.stop();
                }

                if (numBytesRead > 0 && getRunningStatus()) {
                    /* Read success, some bytes were read */

                    /* Copy the bytes from 'buffer' to 'trimmedBuffer' which is just the correct size matching the number of bytes we actually read.
                     * Note that we read at most readLen bytes */
                    byte trimmedBuffer[] = new byte[numBytesRead];
                    System.arraycopy(buffer, 0, trimmedBuffer, 0, numBytesRead);
                    listener.dataReceived(trimmedBuffer, id);
                } else if (numBytesRead < 0 && getRunningStatus()){
                    /* Connection closed by remote peer */

                    Log.d(TAG, "Connection closed by remote peer, id " + id);
                    /* Because we might have unconsumed data in the the connection's ringbuffer, we must allow Wish core to consume it first before we can tear down the connection */
                    int bailOut = 0;
                    while (!listener.isAllInputDataConsumed(id)) {
                        bailOut++;
                        /* While there is input data in ringbuffer which is not consumed... sleep */
                        Log.d(TAG, "There is some unconsumed data, id " + id);
                        if (bailOut > 100) {
                            Log.d(TAG, "Break out, abandoning unconsumed data for id " + id);
                            break;
                        }
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ie) {
                            Log.d(TAG, "interrupted while waiting for the ring buffer to become empty before socket close");
                        }
                    }

                    this.stop();
                }
                else {
                    //Log.d(TAG, "Bytes read from remote peer was 0!");
                }
            }
        }

        /* We are done. Close the socket */
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ioe) {
                Log.d(TAG, "IOException while closing socket");
            }
        }

        if (isServer) {
            listener.onServerClose(id);
        }
        else {
            listener.onClose(id);
        }

    }

    private void connectionEstablished(boolean isServer) {

        listener.connectionEstablished(id, isServer);
    }

    public void sendMessage(byte data[]) {
        if (getRunningStatus() == false) {
            Log.d(TAG, "Will not send data, because the connection is no longer running");
            return;
        }
        if (socket.isClosed()) {
            Log.d(TAG, "Will not send data, because the socket is closed");
            return;
        }



        //Log.i("Debug", "sending data, len = " + data.length);
        if (outputStream != null) {
            try {
                outputStream.write(data, 0, data.length);
                outputStream.flush();
            } catch (IOException e) {
                Log.d(TAG, "While writing data: " + e);

                listener.onError(id);
                stop();
            }
        }
        else {
            Log.d(TAG, "output stream was null");
            listener.onError(id);
            stop();
        }
    }

    public synchronized void stop() {
        running = false;
    }

    private synchronized boolean getRunningStatus() {
        return running;
    }
}
