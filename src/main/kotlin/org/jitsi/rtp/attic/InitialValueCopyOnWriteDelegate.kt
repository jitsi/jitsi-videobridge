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
package org.jitsi.rtp.attic

import org.jitsi.rtp.util.BitBuffer
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpHeaderExtension
import org.jitsi.rtp.RtpHeaderExtensions
import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import java.nio.ByteBuffer
import kotlin.properties.Delegates
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible


// The idea of these delegates was to be able to have packet objects contain
// a read-only buffer (which could therefore be shared across all instances)
// and maintain any changes they had to those fields in separate variables
// (i.e. they didn't touch the buffer).  then, when they needed to finally
// be serialized to be sent out, they could serialize the combination of
// changed and unchanged fields.  The goal being to minimize copies to
// the actual buffer.  the first of these delegates below doesn't add
// much to just initializing a member variable to a value in the buffer.
// The second one doesn't store anything if the value hasn't changed
// (reads read from the buffer each time) and then assigns it to a
// variable once a change is written.  When cloning, it checks each
// variable to see if it's been overridden.  However, from my testing,
// it seems like the reflection to check the delegate for an overridden
// value is far less performant than doing a copy.  Maybe there's a
// cheaper way to achieve the same idea, but, it's not clear at this point
// if that's even necessary...we should wait until we find packet copies are
// an issue before worrying about it.

/**
 * Delegate which has a variable initialized to a certain value but allows
 * it to be overridden.  I realized this doesn't actually provide much
 * value over a normal variable :/
 */
//class InitialValueCopyOnWriteDelegate<T>(private val bufferValue: T) {
//    private var overriddenValue: T? = null
//    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
//        return overriddenValue ?: bufferValue
//    }
//
//    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
//        overriddenValue = value
//    }
//    fun getOverrideValue(): T? = overriddenValue
//}
//
///**
// * A delegate which will read from the given getter until it is
// * assigned.  This allows a class to use a getter to read from
// * its buffer and only use an override variable if the value
// * is changed.  That way, when we want to clone the class,
// * it just give a new reference to its underlying read-only
// * buffer and then copy over any values which have been overridden.
// */
//class CopyOnWriteDelegate<T>(private val getter: () -> T) {
//    private var overriddenValue: T? = null
//    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
//        return overriddenValue ?: getter()
//    }
//    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
//        overriddenValue = value
//    }
//    fun getOverrideValue(): T? = overriddenValue
//}
//
///**
// * Extension function to make it more convenient to get
// * a [CopyOnWriteDelegate] variable's overriden value,
// * if there is one.
// */
//inline fun <reified R> KProperty0<R>.getOverride(): R? {
//    isAccessible = true
//    return (getDelegate() as? CopyOnWriteDelegate<R>)?.getOverrideValue()
//}
//
//
//// A class with a field using CopyOnWriteDelegate would've looked like this:
////class BitBufferRtpHeader(private val buf: ByteBuffer) : RtpHeader() {
////    private val bitBuffer = BitBuffer(buf)
////    override var version: Int by CopyOnWriteDelegate(bitBuffer.getBits(2).toInt())
////    override var hasPadding: Boolean by CopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
////    override var hasExtension: Boolean by CopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
////    override var csrcCount: Int by CopyOnWriteDelegate(bitBuffer.getBits(4).toInt())
////    override var marker: Boolean by CopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
////    override var payloadType: Int by CopyOnWriteDelegate(bitBuffer.getBits(7).toInt())
////    override var sequenceNumber: Int by CopyOnWriteDelegate(buf.getShort().toInt())
////    override var timestamp: Long by CopyOnWriteDelegate(buf.getInt().toLong())
////    override var ssrc: Long by CopyOnWriteDelegate(buf.getInt().toLong())
////    override var csrcs: List<Long> by CopyOnWriteDelegate(listOf())
////    override var extensions: Map<Int, RtpHeaderExtension> by CopyOnWriteDelegate(mapOf())
//
////    init {
////        csrcs = (0 until csrcCount).map {
////            buf.getInt().toLong()
////        }
////        extensions = if (hasExtension) RtpHeaderExtensions.fromBuffer(buf) else mapOf()
////    }
//
////    override fun clone(): RtpHeader {
////        val clone = BitBufferRtpHeader(buf.duplicate().rewind() as ByteBuffer)
////        //getOverriddenValue(this::version)?.let {
////        //    clone.version = it
////        //}
////        //getOverriddenValue(this::hasPadding)?.let {
////        //    clone.hasPadding = it
////        //}
////        //getOverriddenValue(this::hasExtension)?.let {
////        //    clone.hasExtension = it
////        //}
////        //getOverriddenValue(this::csrcCount)?.let {
////        //    clone.csrcCount = it
////        //}
////        //getOverriddenValue(this::marker)?.let {
////        //    clone.marker = it
////        //}
////        //getOverriddenValue(this::payloadType)?.let {
////        //    clone.payloadType = it
////        //}
////        //getOverriddenValue(this::sequenceNumber)?.let {
////        //    clone.sequenceNumber = it
////        //}
////        //getOverriddenValue(this::timestamp)?.let {
////        //    clone.timestamp = it
////        //}
////        //getOverriddenValue(this::ssrc)?.let {
////        //    clone.ssrc = it
////        //}
////        //getOverriddenValue(this::csrcs)?.let {
////        //    clone.csrcs = it
////        //}
////        clone.tempPayload = tempPayload.duplicate()
////        return clone
////    }
////}
//
//fun ByteBuffer.clone(): ByteBuffer {
//    val clone = ByteBuffer.allocate(this.capacity())
//    this.rewind()
//    clone.put(this)
//    this.rewind()
//    clone.flip()
//    return clone
//}
//
//class BitBufferRtpHeader(private val buf: ByteBuffer) : RtpHeader() {
//    private val bitBuffer = BitBuffer(buf)
//    override var version: Int by InitialValueCopyOnWriteDelegate(bitBuffer.getBits(2).toInt())
//    override var hasPadding: Boolean by InitialValueCopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
//    override var hasExtension: Boolean by InitialValueCopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
//    override var csrcCount: Int by InitialValueCopyOnWriteDelegate(bitBuffer.getBits(4).toInt())
//    override var marker: Boolean by InitialValueCopyOnWriteDelegate(bitBuffer.getBitAsBoolean())
//    override var payloadType: Int by InitialValueCopyOnWriteDelegate(bitBuffer.getBits(7).toInt())
//    override var sequenceNumber: Int by InitialValueCopyOnWriteDelegate(buf.getShort().toInt())
//    override var timestamp: Long by InitialValueCopyOnWriteDelegate(buf.getInt().toLong())
//    override var ssrc: Long by InitialValueCopyOnWriteDelegate(buf.getInt().toLong())
//    override var csrcs: List<Long> by InitialValueCopyOnWriteDelegate(listOf())
//    override var extensions: Map<Int, RtpHeaderExtension> by InitialValueCopyOnWriteDelegate(mapOf())
//
//    init {
//        csrcs = (0 until csrcCount).map {
//            buf.getInt().toLong()
//        }
//        extensions = if (hasExtension) RtpHeaderExtensions.parse(buf) else mapOf()
//    }
//
//    private fun<T> getOverriddenValue(prop: KProperty0<T>): T? {
//        return ((prop.apply { isAccessible = true}).getDelegate() as InitialValueCopyOnWriteDelegate<T>).getOverrideValue()
//    }
//
//    override fun clone(): RtpHeader {
//        val clone = BitBufferRtpHeader(buf.duplicate().rewind() as ByteBuffer)
//        //getOverriddenValue(this::version)?.let {
//        //    clone.version = it
//        //}
//        //getOverriddenValue(this::hasPadding)?.let {
//        //    clone.hasPadding = it
//        //}
//        //getOverriddenValue(this::hasExtension)?.let {
//        //    clone.hasExtension = it
//        //}
//        //getOverriddenValue(this::csrcCount)?.let {
//        //    clone.csrcCount = it
//        //}
//        //getOverriddenValue(this::marker)?.let {
//        //    clone.marker = it
//        //}
//        //getOverriddenValue(this::payloadType)?.let {
//        //    clone.payloadType = it
//        //}
//        //getOverriddenValue(this::sequenceNumber)?.let {
//        //    clone.sequenceNumber = it
//        //}
//        //getOverriddenValue(this::timestamp)?.let {
//        //    clone.timestamp = it
//        //}
//        //getOverriddenValue(this::ssrc)?.let {
//        //    clone.ssrc = it
//        //}
//        //getOverriddenValue(this::csrcs)?.let {
//        //    clone.csrcs = it
//        //}
//        return clone
//    }
//}
//
///**
// * Function which takes in a [ByteBuffer], then returns a function
// * which takes no arguments and returns the version value in the
// * buffer as an [Int]
// */
//val VersionGetter: (ByteBuffer) -> () -> Int = { buf ->
//    {
//        buf.get(0).getBits(0, 2).toInt()
//    }
//}
///**
// * This used the proper CopyOnWriteDelegate (as opposed to the one before this
// * which used the InitivalValueCopyOnWriteDelegate
// */
//class BBRH(private val buf: ByteBuffer) : RtpHeader() {
//    //override var version: Int by CopyOnWriteDelegate(VersionGetter(buf))
//    override var version: Int by CopyOnWriteDelegate { buf.get(0).getBits(0, 2).toInt() }
//    override var hasPadding: Boolean by CopyOnWriteDelegate { buf.get(0).getBitAsBool(2) }
//    override var hasExtension: Boolean by CopyOnWriteDelegate { buf.get(0).getBitAsBool(3) }
//    override var csrcCount: Int by CopyOnWriteDelegate { buf.get(0).getBits(4, 4).toInt() }
//    override var marker: Boolean by CopyOnWriteDelegate { buf.get(1).getBitAsBool(0) }
//    override var payloadType: Int by CopyOnWriteDelegate { buf.get(1).getBits(1, 7).toInt() }
//    override var sequenceNumber: Int by CopyOnWriteDelegate { buf.getShort(2).toInt() }
//    override var timestamp: Long by CopyOnWriteDelegate { buf.getInt(4).toLong() }
//    override var ssrc: Long by CopyOnWriteDelegate { buf.getInt(8).toLong() }
//    override var csrcs: List<Long> by CopyOnWriteDelegate {
//        val csrcStartByteIndex = 12
//        val numBytesInInt = 4
//        (0 until csrcCount).map { csrcIdx ->
//            val currCsrcByteIndex = csrcStartByteIndex + (csrcIdx * numBytesInInt)
//            buf.getInt(currCsrcByteIndex).toLong()
//        }
//    }
//
//    override var extensions: Map<Int, RtpHeaderExtension> by CopyOnWriteDelegate {
//        if (hasExtension) {
//            val extPosition = 8 + (csrcCount * 4) + 4
//            val extBuf = buf.duplicate().position(extPosition) as ByteBuffer
//            RtpHeaderExtensions.parse(extBuf)
//        } else {
//            mapOf()
//        }
//    }
//
//    override fun clone(): RtpHeader {
//        val clone = BBRH(buf.duplicate())
//        //TODO: i think we could do this in a more dynamic fashion (i.e. iterate over all member properties
//        // and assign any overrides generically) which would make all this a lot shorter.   it will also be less
//        // explicit though, so we may or may not want to do it.
//        // Note: the reflection here is 'relatively' expensive compared to not doing it, but does not appear to
//        // be very costly in the grand scheme of things (for example, a call to clone took an average of .028
//        // milliseconds, but without doing the reflection work it would've finished a couple orders of magnitude
//        // faster).  I don't think we'll be cloning enough for it to be an issue.
//        //this::version.getOverride()?.let {
//        //    clone.version = it
//        //}
//        //this::hasPadding.getOverride()?.let {
//        //    clone.hasPadding = it
//        //}
//        //this::hasExtension.getOverride()?.let {
//        //    clone.hasExtension = it
//        //}
//        //this::csrcCount.getOverride()?.let {
//        //    clone.csrcCount = it
//        //}
//        //this::marker.getOverride()?.let {
//        //    clone.marker = it
//        //}
//        //this::payloadType.getOverride()?.let {
//        //    clone.payloadType = it
//        //}
//        //this::sequenceNumber.getOverride()?.let {
//        //    clone.sequenceNumber = it
//        //}
//        //this::timestamp.getOverride()?.let {
//        //    clone.timestamp = it
//        //}
//        //this::ssrc.getOverride()?.let {
//        //    clone.ssrc = it
//        //}
//        //this::csrcs.getOverride()?.let {
//        //    clone.csrcs = it
//        //}
//        //getOverriddenValue(this::version)?.let {
//        //    clone.version = it
//        //}
//        //getOverriddenValue(this::hasPadding)?.let {
//        //    clone.hasPadding = it
//        //}
//        //getOverriddenValue(this::hasExtension)?.let {
//        //    clone.hasExtension = it
//        //}
//        //getOverriddenValue(this::csrcCount)?.let {
//        //    clone.csrcCount = it
//        //}
//        //getOverriddenValue(this::marker)?.let {
//        //    clone.marker = it
//        //}
//        //getOverriddenValue(this::payloadType)?.let {
//        //    clone.payloadType = it
//        //}
//        //getOverriddenValue(this::sequenceNumber)?.let {
//        //    clone.sequenceNumber = it
//        //}
//        //getOverriddenValue(this::timestamp)?.let {
//        //    clone.timestamp = it
//        //}
//        //getOverriddenValue(this::ssrc)?.let {
//        //    clone.ssrc = it
//        //}
//        //getOverriddenValue(this::csrcs)?.let {
//        //    clone.csrcs = it
//        //}
//
//        return clone
//    }
//}
