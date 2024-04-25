/*
 * Copyright @ 2024 - present 8x8, Inc.
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
package org.jitsi.videobridge.stats

import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.queue.QueueStatistics.Companion.getStatistics
import org.jitsi.videobridge.AbstractEndpointMessageTransport
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.relay.RelayEndpointSender
import org.json.simple.JSONObject

object QueueStats {
    /** Gets statistics for the different `PacketQueue`s that this bridge uses. */
    @JvmStatic
    fun getQueueStats() = JSONObject().apply {
        this["srtp_send_queue"] = getJsonFromQueueStatisticsAndErrorHandler(
            Endpoint.queueErrorCounter,
            "Endpoint-outgoing-packet-queue"
        )
        this["relay_srtp_send_queue"] = getJsonFromQueueStatisticsAndErrorHandler(
            Relay.queueErrorCounter,
            "Relay-outgoing-packet-queue"
        )
        this["relay_endpoint_sender_srtp_send_queue"] = getJsonFromQueueStatisticsAndErrorHandler(
            RelayEndpointSender.queueErrorCounter,
            "RelayEndpointSender-outgoing-packet-queue"
        )
        this["rtp_receiver_queue"] = getJsonFromQueueStatisticsAndErrorHandler(
            RtpReceiverImpl.queueErrorCounter,
            "rtp-receiver-incoming-packet-queue"
        )
        this["rtp_sender_queue"] = getJsonFromQueueStatisticsAndErrorHandler(
            RtpSenderImpl.queueErrorCounter,
            "rtp-sender-incoming-packet-queue"
        )
        this["colibri_queue"] = getStatistics()["colibri-queue"]
        this[AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID] =
            getJsonFromQueueStatisticsAndErrorHandler(
                null,
                AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID
            )
    }

    private fun getJsonFromQueueStatisticsAndErrorHandler(
        countingErrorHandler: CountingErrorHandler?,
        queueName: String
    ): OrderedJsonObject? {
        var json = getStatistics()[queueName] as OrderedJsonObject?
        if (countingErrorHandler != null) {
            if (json == null) {
                json = OrderedJsonObject()
                json["dropped_packets"] = countingErrorHandler.numPacketsDropped
            }
            json["exceptions"] = countingErrorHandler.numExceptions
        }

        return json
    }
}
