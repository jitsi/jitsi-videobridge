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

import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.nlj.RtpReceiverImpl
import org.jitsi.nlj.RtpSenderImpl
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.queue.CountingErrorHandler
import org.jitsi.utils.queue.QueueStatistics.Companion.getStatistics
import org.jitsi.videobridge.AbstractEndpointMessageTransport
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.relay.RelayEndpointSender

object QueueStats {
    /** Gets statistics for the different `PacketQueue`s that this bridge uses. */
    @JvmStatic
    fun getQueueStats(): ObjectNode = OrderedJsonObject().apply {
        getJsonFromQueueStatisticsAndErrorHandler(
            Endpoint.queueErrorCounter,
            "Endpoint-outgoing-packet-queue"
        )?.let { set<ObjectNode>("srtp_send_queue", it) }
        getJsonFromQueueStatisticsAndErrorHandler(
            Relay.queueErrorCounter,
            "Relay-outgoing-packet-queue"
        )?.let { set<ObjectNode>("relay_srtp_send_queue", it) }
        getJsonFromQueueStatisticsAndErrorHandler(
            RelayEndpointSender.queueErrorCounter,
            "RelayEndpointSender-outgoing-packet-queue"
        )?.let { set<ObjectNode>("relay_endpoint_sender_srtp_send_queue", it) }
        getJsonFromQueueStatisticsAndErrorHandler(
            RtpReceiverImpl.queueErrorCounter,
            "rtp-receiver-incoming-packet-queue"
        )?.let { set<ObjectNode>("rtp_receiver_queue", it) }
        getJsonFromQueueStatisticsAndErrorHandler(
            RtpSenderImpl.queueErrorCounter,
            "rtp-sender-incoming-packet-queue"
        )?.let { set<ObjectNode>("rtp_sender_queue", it) }
        getStatistics().get("colibri-queue")?.let { set<com.fasterxml.jackson.databind.JsonNode>("colibri_queue", it) }
        getJsonFromQueueStatisticsAndErrorHandler(
            null,
            AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID
        )?.let { set<ObjectNode>(AbstractEndpointMessageTransport.INCOMING_MESSAGE_QUEUE_ID, it) }
    }

    private fun getJsonFromQueueStatisticsAndErrorHandler(
        countingErrorHandler: CountingErrorHandler?,
        queueName: String
    ): ObjectNode? {
        var json = getStatistics().get(queueName) as? ObjectNode
        if (countingErrorHandler != null) {
            if (json == null) {
                json = OrderedJsonObject()
                json.put("dropped_packets", countingErrorHandler.numPacketsDropped)
            }
            json.put("exceptions", countingErrorHandler.numExceptions)
        }

        return json
    }
}
