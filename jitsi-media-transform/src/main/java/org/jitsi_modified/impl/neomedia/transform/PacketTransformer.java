/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.transform;

import org.jitsi.rtp.*;

/**
 * Encapsulate the concept of packet transformation. Given an array of packets,
 * <tt>PacketTransformer</tt> can either "transform" each one of them, or
 * "reverse transform" (e.g. restore) each one of them.
 *
 * @author Bing SU (nova.su@gmail.com)
 * @author Boris Grozev
 */
public interface PacketTransformer
{
    /**
     * Closes this <tt>PacketTransformer</tt> i.e. releases the resources
     * allocated by it and prepares it for garbage collection.
     */
    public void close();

    /**
     * Reverse-transforms each packet in an array of packets. Null values
     * must be ignored.
     *
     * @param pkts the transformed packets to be restored.
     * @return the restored packets.
     */
    public Packet[] reverseTransform(Packet[] pkts);

    /**
     * Transforms each packet in an array of packets. Null values must be
     * ignored.
     *
     * @param pkts the packets to be transformed
     * @return the transformed packets
     */
    public Packet[] transform(Packet[] pkts);
}
