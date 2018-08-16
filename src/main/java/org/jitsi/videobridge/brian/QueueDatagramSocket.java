/*
 * Copyright @ 2018 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.brian;

import org.jitsi.rtp.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * @author bb
 * Implementation of DatagramSocket which pulls data from an underlying queue
 */
public class QueueDatagramSocket extends DatagramSocket
{
    protected LinkedBlockingQueue<Packet> incomingQueue = new LinkedBlockingQueue<>();

    public QueueDatagramSocket() throws SocketException {

    }

    public void enqueueData(List<? extends Packet> packets) {
        incomingQueue.addAll(packets);
    }

    @Override
    public synchronized void receive(DatagramPacket p) throws IOException
    {
        try
        {
            Packet pkt = incomingQueue.take();
            p.setData(pkt.getBuffer().array(), 0, pkt.getBuffer().limit());
        } catch (InterruptedException e)
        {
            e.printStackTrace();
        }
    }
}
