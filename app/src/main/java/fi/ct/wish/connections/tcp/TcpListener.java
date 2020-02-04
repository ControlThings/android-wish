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

public interface TcpListener {

    public void connectionEstablished(int id, boolean isServer);
    /** Returns the maximum number of bytes that the listener can accept through dataReceived callback */
    public int getMaxInputDataLength(int id);
    /** Returns true, if all the data supplied through dataReceived has been consumed */
    public boolean isAllInputDataConsumed(int id);
    public void dataReceived(byte[] buffer, int id);
    /** Callback to be called when there is some TCP error during an established connection (in client role) */
    public void onError(int id);
    /** Callback which is called when there is some TCP error during an established connection (in server role) */
    public void onServerError(int id);
    /** Callback to be called when there is some TCP error during connection setup */
    public void onConnectionError(int id);
    /** Callback to be called when there is some TCP error during server listening setup */
    public void onListenError();
    /** Callback to be called when gracefully closing (server role) */
    public void onServerClose(int id);
    /** Callback to be called when gracefully closing (client role) */
    public void onClose(int id);

}
