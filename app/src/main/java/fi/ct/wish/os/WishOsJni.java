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
package fi.ct.wish.os;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.Timer;
import java.util.TimerTask;

import fi.ct.wish.Wish;
import fi.ct.wish.bridge.WishCoreBridge;
import fi.ct.wish.connections.tcp.CommunicationThread;
import fi.ct.wish.connections.tcp.Tcp;
import fi.ct.wish.connections.tcp.TcpListener;
import fi.ct.wish.connections.udp.Udp;
import fi.ct.wish.connections.udp.UdpListener;

public class WishOsJni {

    static {
        System.loadLibrary("wish");
    }

    /* Register the JNI interface.
     * Registers a global reference to WishOsJni, which is needed by many JNI functions and upcalls from Wish
     *
     * @param serverPort the port at which the core will listen for incoming connections
     */
    public synchronized native void register(int bindPort);

    /**
     * Unregister the JNI interface
     * This will delete a global reference to WishOsJni so most JNI calls are not OK after this!
     */
    public synchronized native void unRegister();

    /** Returns the number of bytes that are free on the ring buffer
     *
     * @param id the connection Id
     * @return the bytes available on the ringbuffer of the specified connection */
    public synchronized native int getRxBufferFree(int id);

    /** Returns true, if all the data supplied through dataReceived has been consumed
     * @param id the connection Id
     * @return true if the ring buffer is empty */
    public synchronized native boolean isRingbufferEmpty(int id);

    /** Feed data into the ring buffer
     * @param id the connection id */
    public synchronized native void feedData(int id, byte buffer[], int buffer_len);

    /** Signal an event which has occurred on a normal Wish connections */
    public synchronized native void signalEvent(int id, int ev);

    /** Report to Wish that a certain period of time (1 seconds) has elapsed. This provides the timebase for Wish, must be called at 1 second intervals */
    public synchronized native void reportPeriodic();

    /** This method yeilds to Wish so that Wish can process events related to Wish connections (such as new data arrived) */
    public synchronized native void processConnections();

    /** Feed Wish local discovery (wld) data to Wish core */
    public synchronized native void feedLocalDiscoveryData(byte inetAddrAsBytes[], int port, byte data[]);

    /** Method for signaling to Wish core that an incoming Wish connection has been accepted on the TCP server port
     * @return the connection id given by Wish core for this connection, if id < 0 then Core did not accept the connection (some error) */
    public synchronized native int acceptServerConnection();

    /* Interface for relay control connection */

    /** Report that an event occurred on a relay control connection identified by id */
    public synchronized native void relayControlSignalEvent(int id, int sig);

    /** Feed incoming TCP data related to connection identified by id */
    public synchronized native void relayControlFeed(int id, byte data[]);

    /**
     * Signal that DNS resolving completed
     *
     * @param resolveId The id of the resolver that now completed
     * @param inetAddrAsBytes The result is in network byte order: the highest order byte of the address is in getAddress()[0]
     */
    public synchronized native void dnsResolvingCompleted(int resolveId, byte inetAddrAsBytes[], int inetAddrLength);

    private final String TAG = "WishOsJni";

    private Udp udp;
    private Tcp tcp;
    /** This is the TCP controller for relay control TCP connection */
    private Tcp relayControlTcp;

    private WishFile file;

    Context context;

    private WishCoreBridge wishCoreBridge;

    /*
    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    */

    public WishOsJni(Wish wish, WishCoreBridge wishCoreBridge) {

        Log.d(TAG, "starting WishOSJni");

        this.context = wish.getBaseContext();

        /* According to some sources, obtaining a Multicast lock should improve UDP broadcast reception. However, I have not seen any difference in reality, and the documentation
         * says that it only affects Multicast reception (not broadcast) */
        /*
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifiManager.createMulticastLock(TAG);
        multicastLock.acquire();
        */
        /* Obtaining a Wifi "wake lock" does not seem to improve UDP broadcast reception reliability.
         * However, it might be required to allow receiving UDP packets when the unit is in powersave mode. To be investigated. */

        udp = new Udp(udpListener);
        tcp = new Tcp(tcpListener);
        relayControlTcp = new Tcp(relayControlTcpListener);

        file = new WishFile(context);

        final int corePort = 37010;
        register(corePort);
        this.wishCoreBridge = wishCoreBridge;
        this.wishCoreBridge.setOsJni(this);
        startOneSecondTimeBase();

        listen(corePort);
        udp.udpServer(true);
    }

    /**
     * Open a file
     *
     * @param filename the filename
     * @return the file ID number which can be used with read, write..., or -1 for an error
     */
    public int openFile(String filename) {
        return file.open(filename);
    }

    /**
     * Close a fileId
     *
     * @param fileId the fileId to close
     * @return 0 for success, -1 for an error
     */
    public int closeFile(int fileId) {
        return file.close(fileId);
    }

    /**
     * Read from a file
     *
     * @param fileId the fileId, obtained with open()
     * @param buffer The buffer to place the bytes into
     * @param count  the number of bytes to read
     * @return the amount of bytes read, or 0 if EOF, or -1 for an error
     */
    public int readFile(int fileId, byte[] buffer, int count) {
        if (count != buffer.length) {
            return -1;
        }
        return file.read(fileId, buffer, count);
    }

    /**
     * Write to a file in internal storage
     *
     * @param fileId the fileId
     * @param buffer the databuffer to be written
     * @param count  The
     * @return
     */
    public int writeFile(int fileId, byte[] buffer, int count) {
        if (count != buffer.length) {
            return -1;
        } else {
            return file.write(fileId, buffer);
        }
    }

    public int seekFile(int fileId, int offset, int whence) {
        return (int) file.seek(fileId, offset, whence);
    }

    public int rename(String oldName, String newName) {
        return file.rename(oldName, newName);
    }

    public int remove(String filename) {
        return file.remove(filename);
    }

    /**
     * Connect to remote host.
     *
     * @param ipAddr
     * @param port
     * @param id     needs a connection id
     */
    public void connect(byte ipAddr[], int port, int id) {
        tcp.connect(ipAddr, port, id);
    }



    /**
     * Gracefully close a TCP connection
     *
     * @param id the connection to close
     */
    public void closeConnection(int id) {
        boolean connectionFound = tcp.closeConnection(id);
        if (!connectionFound) {
            /* We tried to close, but we were told there is no such communication thread!
            This can connections which never connect. In this case just clean up
             */
            Log.d(TAG, "Signaling TCP_DISCONNECTED downwards despite no connection found");
            signalEvent(id, Tcp.TCP_DISCONNECTED);
        }
    }

    /**
     * Open tcp port for incoming connection.
     *
     * @param port
     */
    public void listen(int port) {
        tcp.listen(port, this);
    }


    /**
     * Send data over tcp connection.
     *
     * @param buffer
     * @param id     connection id
     * @return
     */
    public int sendData(byte buffer[], int id) {
        //Log.d(TAG, "trying to send data buffer len=" + buffer.length + " id is " + id);
        //Log.d(TAG, "Here is the data: " + new String(buffer));
        tcp.sendMessage(buffer, id);
        return buffer.length;
    }

    /**
     * Send broadcast message.
     *
     * @param msg
     */
    public void startBroadcastAutodiscovery(final byte[] msg) {
        udp.sendBroadcastMessage(msg);
    }

    private UdpListener udpListener = new UdpListener() {
        @Override
        public void onLocalDiscoveryData(byte[] address, int port, byte[] message) {
            //Log.d(TAG, "got local discovery data from: " + address);
            feedLocalDiscoveryData(address, port, message);
        }
    };

    private TcpListener tcpListener = new TcpListener() {
        @Override
        public void connectionEstablished(int id, boolean isServer) {
            Log.d(TAG, "connection established id: " + id);
            if (isServer) {
                signalEvent(id, Tcp.TCP_CLIENT_CONNECTED); // TCP_CLIENT_CONNECTED (client has connected to our server)
            } else {
                signalEvent(id, Tcp.TCP_CONNECTED); // TCP_CONNECTED (in client role)
            }
        }

        @Override
        public int getMaxInputDataLength(int id) {
            return getRxBufferFree(id);
        }

        @Override
        public boolean isAllInputDataConsumed(int id) { return isRingbufferEmpty(id); }

        @Override
        public void dataReceived(byte[] buffer, int id) {
            //Log.d(TAG, "data received id: " + id);
            feedData(id, buffer, buffer.length);
        }

        @Override
        public void onConnectionError(int id) {
            signalEvent(id, Tcp.TCP_DISCONNECTED); // TCP_DISCONNECTED
            Log.i(TAG, "There was an error during connect in the TCP connection id = " + id);
        }

        public void onError(int id) {
            signalEvent(id, Tcp.TCP_DISCONNECTED); // TCP_DISCONNECTED
            Log.i(TAG, "There was an error in the TCP connection id = " + id);
        }

        public void onServerError(int id) {
            signalEvent(id, Tcp.TCP_CLIENT_DISCONNECTED);
            Log.i(TAG, "There was an error in the (server) TCP connection id = " + id);
        }

        public void onClose(int id) {
            signalEvent(id, Tcp.TCP_DISCONNECTED); // TCP_DISCONNECTED
        }

        public void onServerClose(int id) {
            signalEvent(id, Tcp.TCP_CLIENT_DISCONNECTED);
        }

        public void onListenError() {
            Log.i(TAG, "There was an error when starting to listen TCP port");
        }

    };

    private int startDnsResolving(final String hostName, final int resolveId) {

        Thread thread = new Thread() {
            public void run(){

                try {
                    InetAddress addresses[] = InetAddress.getAllByName(hostName);

                    boolean ipv4Found = false;
                    for (InetAddress addr : addresses){
                        byte ipAddr[] = addr.getAddress();
                        if (ipAddr.length == 4) {
                            System.out.println("Resolved " + hostName + " to " + addr.getHostAddress() + ", id" + resolveId);
                            dnsResolvingCompleted(resolveId, ipAddr, ipAddr.length); //The result is in network byte order: the highest order byte of the address is in getAddress()[0]
                            ipv4Found = true;
                            break;
                        }
                    }

                    if (!ipv4Found) {
                        throw new UnknownHostException("Resolving " + hostName + " yielded no IPv4 addresses!");
                    }
                } catch (UnknownHostException uhe) {
                    System.out.println("Error Resolving " + hostName +", id" + resolveId + ": " + uhe.getMessage());
                    dnsResolvingCompleted(resolveId, null, 0);
                }

            }
        };

        thread.start();


        return 0;
    }



    private int latestRelayConnectionId = 1;


    /**
     * This function is called by the Android wish porting layer when it decides to open a relay control connection to an IP address
     *
     * Please note, that at any moment in time we have maximum one relay control connection open.
     * For that reason, we do not need to provide a connection ID. There is also no risk for confusion between normal Wish connections and the
     * relay control connection, because they have separate fi.ct.wish.connections.Tcp fi.ct.wish.connections.TcpListener instances.
     *
     * @param ipAddr relay server IP
     * @param port relay server port
     */
    public synchronized int relayControlConnect(byte ipAddr[], int port) {
        //Log.d(TAG, "Relay connect");
        int relay_id = latestRelayConnectionId++;
        relayControlTcp.connect(ipAddr, port, relay_id);   /* For connection ID, see comment above */
        return relay_id;
    }

    /**
     * Gracefully close a Rleay control connection
     *
     *
     */
    public synchronized void relayControlCloseConnection(int id) {
        relayControlTcp.closeConnection(id);
    }

    /** This constant is used to signal to Wish core that one seconds has passed the signal is delivered using relayControlSignalEvent()
     * FIXME create a cleaner solution to this. relayControlSignalEvent should be merged with the "normal" signalEvent?
     */
    public final int RELAY_CONTROL_SIG_TIMEBASE = 10;


    /**
     * Send data over a relay control connection.
     *
     * @param buffer
     *
     * @return
     */
    public int relayControlSendData(int id, byte buffer[]) {
        //Log.d(TAG, "Relay trying to send data buffer len=" + buffer.length);
        //Log.d(TAG, "Here is the data: " + new String(buffer));
        relayControlTcp.sendMessage(buffer, id);
        return buffer.length;
    }

    private TcpListener relayControlTcpListener = new TcpListener() {
        public final int RELAY_CONTROL_SIG_CONNECTED = 2;
        public final int RELAY_CONTROL_SIG_DISCONNECTED = 1;
        @Override
        public int getMaxInputDataLength(int id) {
            return 16;  /* RELAY_CLIENT_RX_RB_LEN is defined to be 64 in wish_relay_client.h */
        }

        @Override
        public boolean isAllInputDataConsumed(int id) { return true; /* FIXME */ }

        @Override
        public void connectionEstablished(int id, boolean isServer) {
            Log.v(TAG, "Relay control connection established");
            relayControlSignalEvent(id, RELAY_CONTROL_SIG_CONNECTED);
        }

        @Override
        public void dataReceived(byte[] buffer, int id) {
            //Log.d(TAG, "Relay data received");
            relayControlFeed(id, buffer);
        }

        @Override
        public void onClose(int id) {
            Log.v(TAG, "Relay control closed");
            relayControlSignalEvent(id, RELAY_CONTROL_SIG_DISCONNECTED);
        }

        @Override
        public void onConnectionError(int id) {
            Log.v(TAG, "Relay control onConnectionError");
            relayControlSignalEvent(id, RELAY_CONTROL_SIG_DISCONNECTED);
        }

        @Override
        public void onError(int id) {
            Log.v(TAG, "Relay control onError");
            relayControlSignalEvent(id, RELAY_CONTROL_SIG_DISCONNECTED);
        }

        @Override
        public void onListenError() {
            Log.v(TAG, "Relay control onListenError");
        }

        @Override
        public void onServerClose(int id) {
            Log.v(TAG, "Relay control onServerClose");
        }

        @Override
        public void onServerError(int id) {
            Log.v(TAG, "Relay control onServerError");
        }

    };

    public void cleanup() {
        Log.d(TAG, "in cleanup");
        _timer.cancel();
        udp.cleanup();
        tcp.cleanup();
        relayControlTcp.cleanup();
        ArrayList<CommunicationThread> removeList = new ArrayList<>();

        Iterator<CommunicationThread> iter = tcp.connections.getCommunicationsThreads().iterator();
        try {
            while (iter.hasNext()) {
                CommunicationThread cthread = iter.next();
                //tcp.connections.removeConnection(cthread.getId()); /* Don't call removeConnection here, it will cause ConcurrentModificationException because iterator becomes stale. Instead, add to remove list and remove after loop */
                removeList.add(cthread);
                cthread.stop();
                cthread.waitForThreadToStop();
            }
            tcp.connections.getCommunicationsThreads().removeAll(removeList);
        } catch (ConcurrentModificationException cme) {
            Log.d(TAG, "in cleanup:" + cme.getMessage());
        }


        iter = relayControlTcp.connections.getCommunicationsThreads().iterator();
        try {
            while (iter.hasNext()) {
                CommunicationThread cthread = iter.next();
                //tcp.connections.removeConnection(cthread.getId()); /* Don't call removeConnection here, it will cause ConcurrentModificationException because iterator becomes stale. Instead, add to remove list and remove after loop */
                removeList.add(cthread);
                cthread.stop();
                cthread.waitForThreadToStop();
            }
            relayControlTcp.connections.getCommunicationsThreads().removeAll(removeList);
        } catch (ConcurrentModificationException cme) {
            Log.d(TAG, "in cleanup:" + cme.getMessage());
        }

        unRegister();
    }


    /**
     * This function will return the IP address in the Wifi network - Note that this is useful only for "local discovery" purposes
     * @return The IP address (formatted as a string) of the Wifi interface
     */
    public String getWifiIP() {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();

        // Convert little-endian to big-endian if needed
        if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            ipAddress = Integer.reverseBytes(ipAddress);
        }

        byte[] ipByteArray = BigInteger.valueOf(ipAddress).toByteArray();

        String ipAddressString;
        try {
            ipAddressString = InetAddress.getByAddress(ipByteArray).getHostAddress();
        } catch (UnknownHostException ex) { /* This might happen when wifi is not configured (yet)? */
            Log.e(TAG, "Unable to get Wifi address.");
            ipAddressString = "";
        }

        return ipAddressString;
    }

    private Timer _timer;

    private void startOneSecondTimeBase() {
        _timer = new Timer("Wish core periodic", true);
        _timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                synchronized (WishOsJni.this) {
                    reportPeriodic();
                    wishCoreBridge.notifyAppToCoreWorker();
                }
            }
        }, 1000, 1000);
    }
}
