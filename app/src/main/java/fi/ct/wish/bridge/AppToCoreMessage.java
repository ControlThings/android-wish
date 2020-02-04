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
package fi.ct.wish.bridge;

/**
 * Created by jan on 1/25/17.
 */

class AppToCoreMessage {
    private byte[] wsid;
    private byte[] data;
    AppToCoreMessage(byte[] wsid, byte[] data) {
        this.wsid = wsid;
        this.data = data;
    }
    byte[] getWsid() { return wsid; }
    byte[] getData() { return data; }
}
