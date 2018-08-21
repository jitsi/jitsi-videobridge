///*
// * Copyright @ 2018 Atlassian Pty Ltd
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package org.jitsi.rtp.rtcp_og;
//
//
//
//        import java.io.DataOutputStream;
//        import java.io.IOException;
//        import net.sf.fmj.media.rtp.util.Packet;
//        import net.sf.fmj.utility.ByteBufferOutputStream;
//
//public class RTCPCompoundPacket extends RTCPPacket {
//    public RTCPPacket[] packets;
//
//    public RTCPCompoundPacket(Packet base) {
//        super(base);
//        super.type = -1;
//    }
//
//    public RTCPCompoundPacket(RTCPPacket[] packets) {
//        this.packets = packets;
//        super.type = -1;
//        super.received = false;
//    }
//
//    public void assemble(DataOutputStream out) throws IOException {
//        throw new IllegalArgumentException("Recursive Compound Packet");
//    }
//
//    public void assemble(int len, boolean encrypted) {
//        this.length = len;
//        this.offset = 0;
//        byte[] d = new byte[len];
//        ByteBufferOutputStream bbos = new ByteBufferOutputStream(d, 0, len);
//        DataOutputStream dos = new DataOutputStream(bbos);
//
//        int laststart;
//        int prelen;
//        try {
//            if (encrypted) {
//                this.offset += 4;
//            }
//
//            laststart = this.offset;
//
//            for(prelen = 0; prelen < this.packets.length; ++prelen) {
//                laststart = bbos.size();
//                this.packets[prelen].assemble(dos);
//            }
//        } catch (IOException var9) {
//            throw new NullPointerException("Impossible IO Exception");
//        }
//
//        prelen = bbos.size();
//        super.data = d;
//        if (prelen > len) {
//            throw new NullPointerException("RTCP Packet overflow");
//        } else {
//            if (prelen < len) {
//                if (this.data.length < len) {
//                    System.arraycopy(this.data, 0, this.data = new byte[len], 0, prelen);
//                }
//
//                this.data[laststart] = (byte)(this.data[laststart] | 32);
//                this.data[len - 1] = (byte)(len - prelen);
//                int temp = (this.data[laststart + 3] & 255) + (len - prelen >> 2);
//                if (temp >= 256) {
//                    this.data[laststart + 2] = (byte)(this.data[laststart + 2] + (len - prelen >> 10));
//                }
//
//                this.data[laststart + 3] = (byte)temp;
//            }
//
//        }
//    }
//
//    public int calcLength() {
//        if (this.packets != null && this.packets.length >= 1) {
//            int len = 0;
//
//            for(int i = 0; i < this.packets.length; ++i) {
//                len += this.packets[i].calcLength();
//            }
//
//            return len;
//        } else {
//            throw new IllegalArgumentException("Bad RTCP Compound Packet");
//        }
//    }
//
//    public String toString() {
//        return "RTCP Packet with the following subpackets:\n" + this.toString(this.packets);
//    }
//
//    public String toString(RTCPPacket[] packets) {
//        String s = "";
//
//        for(int i = 0; i < packets.length; ++i) {
//            s = s + packets[i];
//        }
//
//        return s;
//    }
//}
//
