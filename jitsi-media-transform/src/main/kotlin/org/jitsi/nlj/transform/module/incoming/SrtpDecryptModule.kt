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
package org.jitsi.nlj.transform.module.incoming

import org.jitsi.nlj.srtp.SRTPContextFactory
import org.jitsi.nlj.srtp.SRTPCryptoContext
import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.forEachAs
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.SrtpPacket
import unsigned.toUint

class SrtpDecryptModule : Module("SRTP Decrypt") {
    private var decryptFactory: SRTPContextFactory? = null
    private var srtpContexts = mutableMapOf<Int, SRTPCryptoContext>()
    private var cachedPackets = mutableListOf<SrtpPacket>()
    override fun doProcessPackets(p: List<Packet>) {
        val outPackets = mutableListOf<RtpPacket>()
        // First try and process any cached packets
        val tryCached = cachedPackets.toList()
        cachedPackets.clear()
        tryCached.forEach {
            val context = getContext(it.ssrc, it.seqNum)
            if (context == null) {
                cachedPackets.add(it)
                return@forEach
            }
            println("BRIAN: now have context for ${it.ssrc.toUint()} ${it.seqNum}, decrypting")
            val decryptedPacket = context?.reverseTransformPacket(it)
            if (decryptedPacket != null) {
                outPackets.add(decryptedPacket)
            }
        }

        p.forEachAs<SrtpPacket> {
            println("BRIAN: srtp decrypt $this decrypting packet ${it.ssrc.toUint()} ${it.seqNum}")
            val context = getContext(it.ssrc, it.seqNum)
            if (context == null) {
                println("BRIAN: srtp decrypt ${this.hashCode()} has no context for ${it.ssrc.toUint()} ${it.seqNum} yet, add to cache")
                cachedPackets.add(it)
                return@forEachAs
            } else {
                println("$this got srtp context $context")
                val decryptedPacket = context?.reverseTransformPacket(it)
                if (decryptedPacket != null) {
                    outPackets.add(decryptedPacket)
                } else {
                    println("BRIAN: decryption failed")
                }
            }
        }

        next(outPackets)
    }

    fun setDecryptFactory(df: SRTPContextFactory) {
        println("${this.hashCode()} setting decrypt factory")
        this.decryptFactory = df
    }

    private fun getContext(ssrc: Int, deriveSrtpKeysIndex: Int): SRTPCryptoContext? {
        val context = srtpContexts[ssrc]
        return if (context == null) {
            val newContext = decryptFactory?.defaultContext?.deriveContext(ssrc, 0, 0)
            println("BRIAN: factory ${decryptFactory?.hashCode()} gave new context $newContext")
            newContext?.deriveSrtpKeys(deriveSrtpKeysIndex.toLong())
            println("BRIAN: new context after derive: $newContext")
            if (newContext != null) {
                srtpContexts[ssrc] = newContext
            }
            newContext
        } else {
            context
        }
    }

}

