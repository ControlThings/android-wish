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


import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class Connections {

    private int id = 0;
    private Map<Integer, CommunicationThread> threads = new HashMap<Integer, CommunicationThread>();

    /** Associate the communication thread with a an id number */
    public synchronized int setConnection(CommunicationThread thread, int connectionId) {
        threads.put(connectionId, thread);
        return connectionId;
    }

    public synchronized CommunicationThread getConnection(int id) {
        if (threads.containsKey(id)) {
            return threads.get(id);
        }
        return null;
    }

    public synchronized void removeConnection(int id) {
        if(threads.containsKey(id)){
            threads.remove(id);
        }
    }

    public synchronized Collection<CommunicationThread> getCommunicationsThreads() {
        return threads.values();
    }
}
