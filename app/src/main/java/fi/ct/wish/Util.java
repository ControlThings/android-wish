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
package fi.ct.wish;


import android.app.ActivityManager;
import android.content.Context;

/**
 * Created by jeppe on 11/16/16.
 */

public class Util {

    final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return "(byte[" + bytes.length + "] = (hex) " + new String(hexChars);
    }

    public static String bytesToASCII(byte[] bytes) {
        String wsidAsString = new String(bytes).replaceAll("\\p{C}", " ").trim();
        return "(byte["+ bytes.length + "] = [" + wsidAsString + "...])";
    }

    public static String prettyPrintBytes(byte[] bytes) {
        return bytesToASCII(bytes);
        //return bytesToHex(bytes);
    }
}
