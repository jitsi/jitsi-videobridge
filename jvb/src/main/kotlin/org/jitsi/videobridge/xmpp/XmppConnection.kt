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

package org.jitsi.videobridge.xmpp

import org.jitsi.nlj.stats.DelayStats
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.xmpp.config.XmppClientConnectionConfig.Companion.config
import org.jitsi.xmpp.extensions.colibri.ForcefulShutdownIQ
import org.jitsi.xmpp.extensions.colibri.GracefulShutdownIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jitsi.xmpp.mucclient.ConnectionStateListener
import org.jitsi.xmpp.mucclient.IQListener
import org.jitsi.xmpp.mucclient.MucClient
import org.jitsi.xmpp.mucclient.MucClientConfiguration
import org.jitsi.xmpp.mucclient.MucClientManager
import org.jitsi.xmpp.util.createError
import org.jivesoftware.smack.packet.ExtensionElement
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jivesoftware.smackx.iqversion.packet.Version
import org.json.simple.JSONArray
import org.json.simple.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The XMPP client connection for the videobridge
 */
class XmppConnection : IQListener {
    private val logger = createLogger()

    /**
     * The [MucClientManager] which manages the XMPP user connections and the MUCs.
     */
    val mucClientManager = MucClientManager(FEATURES)

    private val running = AtomicBoolean(false)

    var eventHandler: EventHandler? = null

    fun start() {
        if (running.compareAndSet(false, true)) {
            mucClientManager.apply {
                registerIQ(HealthCheckIQ())
                registerIQ(Version())
                registerIQ(ForcefulShutdownIQ())
                registerIQ(GracefulShutdownIQ())
                registerIQ(ConferenceModifyIQ.ELEMENT, ConferenceModifyIQ.NAMESPACE, false)
                setIQListener(this@XmppConnection)
                addConnectionStateListener(object : ConnectionStateListener {
                    override fun connected(mucClient: MucClient) {}
                    override fun closed(mucClient: MucClient) = VideobridgeMetrics.xmppDisconnects.inc()
                    override fun closedOnError(mucClient: MucClient) = VideobridgeMetrics.xmppDisconnects.inc()
                    override fun reconnecting(mucClient: MucClient) {}
                    override fun reconnectionFailed(mucClient: MucClient) {}
                    override fun pingFailed(mucClient: MucClient) {}
                })
            }

            config.clientConfigs.forEach { cfg -> mucClientManager.addMucClient(cfg) }
            org.jitsi.videobridge.metrics.Metrics.metricsUpdater.addUpdateTask { updateMetrics() }
        } else {
            logger.info("Already started")
        }
    }

    fun stop() {
        if (running.compareAndSet(true, false)) {
            mucClientManager.stop()
        }
    }

    fun updateMetrics() {
        mucClientsConfigured.set(mucClientManager.clientCount)
        mucClientsConnected.set(mucClientManager.clientConnectedCount)
        mucsConfigured.set(mucClientManager.mucCount)
        mucsJoined.set(mucClientManager.mucJoinedCount)
    }

    /**
     * Adds an [ExtensionElement] to our presence, and removes any other
     * extensions with the same element name and namespace, if any exists.
     * @param extension the extension to add.
     */
    fun setPresenceExtension(extension: ExtensionElement) {
        mucClientManager.setPresenceExtension(extension)
    }

    /**
     * Adds a new [MucClient] with configuration described in JSON.
     * @param jsonObject the JSON which describes the configuration of the
     * client.
     * <p/>
     * <pre>{@code
     * The expected JSON format is:
     * {
     *     "id": "muc-client-id",
     *     "key": "value"
     * }
     * }</pre>
     * The [key, value] pairs are interpreted as property names and values to
     * set for the client's configuration (see {@link MucClientConfiguration}).
     *
     * @return {@code true} if the request was successful (i.e. the JSON
     * is in the required format and either a new {@link MucClient} was added
     * or a client with the same ID already existed).
     */
    fun addMucClient(jsonObject: JSONObject): Boolean {
        if (jsonObject["id"] !is String) {
            return false
        }
        val config = MucClientConfiguration(jsonObject["id"] as String)
        for (key in jsonObject.keys) {
            val value = jsonObject[key]
            if (key is String && value is String && key != "id") {
                config.setProperty(key, value)
            }
        }
        if (!config.isComplete) {
            logger.info("Not adding a MucClient, configuration incomplete.")
            return false
        }

        mucClientManager.getMucClient(config.id)?.let { existingMucClient ->
            if (!existingMucClient.config.matches(config)) {
                logger.warn(
                    "Config for ${config.id} has changed, removing the old MucClient. Existing " +
                        "config=${existingMucClient.config}, new config=$config"
                )
                mucClientManager.removeMucClient(config.id)
            } else {
                logger.info(
                    "Ignoring request to add a MucClient that matches an existing one. Existing " +
                        "config=${existingMucClient.config}, new config=$config"
                )
            }
        }

        logger.info("Adding MucClient for ${config.id}")
        mucClientManager.addMucClient(config)
        return true
    }

    private fun MucClientConfiguration.matches(other: MucClientConfiguration) = hostname == other.hostname &&
        port == other.port &&
        domain == other.domain &&
        username == other.username

    /**
     * Returns ids of [MucClient] that have been added.
     * @return JSON string of the list of ids
     */
    fun getMucClientIds(): String {
        return JSONArray().apply { addAll(mucClientManager.mucClientIds) }.toJSONString()
    }

    /**
     * Removes a {@link MucClient} with an ID described in JSON.
     * @param jsonObject the JSON which contains the ID of the client to remove.
     * </p>
     * <pre>{@code
     * The expected JSON format is:
     * {
     *     "id": "muc-client-id",
     * }
     * }</pre>
     *
     * @return {@code true} if the MUC client with the specified ID was removed.
     * Otherwise (if instance has not been initialized, if the JSON is not in
     * the expected format, or if no MUC client with the specified ID exists),
     * returns {@code false}.
     */
    fun removeMucClient(jsonObject: JSONObject): Boolean {
        val id = jsonObject["id"]
        if (id !is String) {
            logger.info("Invalid ID: $id")
            return false
        }
        logger.info("Removing muc client $id")
        return mucClientManager.removeMucClient(id)
    }

    /**
     * Process an incoming IQ
     */
    override fun handleIq(iq: IQ?, mucClient: MucClient): IQ? {
        if (iq == null) {
            return null
        }
        // colibri2 requests are logged at the conference level.
        if (iq !is ConferenceModifyIQ) {
            logger.cdebug { "RECV: ${iq.toXML()}" }
        }

        return when (iq.type) {
            IQ.Type.get, IQ.Type.set -> handleIqRequest(iq, mucClient)?.also {
                logger.cdebug { "SENT: ${it.toXML()}" }
            }
            else -> null
        }
    }

    private fun handleIqRequest(iq: IQ, mucClient: MucClient): IQ? {
        val handler = eventHandler ?: return createError(
            iq,
            StanzaError.Condition.service_unavailable,
            "Service unavailable"
        )
        val response = when (iq) {
            is Version -> measureDelay(versionDelayStats, { iq.toXML() }) {
                handler.versionIqReceived(iq)
            }
            is ConferenceModifyIQ -> {
                // Colibri IQs are handled async.
                handler.colibriRequestReceived(
                    ColibriRequest(iq, colibriDelayStats, colibriProcessingDelayStats) { response ->
                        response.setResponseTo(iq)
                        mucClient.sendStanza(response)
                    }
                )
                null
            }
            is HealthCheckIQ -> measureDelay(healthDelayStats, { iq.toXML() }) {
                handler.healthCheckIqReceived(iq)
            }
            else -> createError(
                iq,
                StanzaError.Condition.service_unavailable,
                "Unsupported IQ request ${iq.childElementName}"
            )
        }

        return response?.setResponseTo(iq)
    }

    private fun IQ.setResponseTo(request: IQ) = apply {
        from = request.to
        stanzaId = request.stanzaId
        to = request.from
    }

    private fun <T> measureDelay(delayStats: DelayStats, context: () -> CharSequence, block: () -> T): T {
        val start = System.currentTimeMillis()
        val result = block()
        val delayMs = System.currentTimeMillis() - start
        delayStats.addDelay(delayMs)
        if (delayMs > 100) {
            logger.warn("Took $delayMs ms to handle IQ: ${context()}")
        }
        return result
    }

    interface EventHandler {
        fun colibriRequestReceived(request: ColibriRequest)
        fun versionIqReceived(iq: Version): IQ
        fun healthCheckIqReceived(iq: HealthCheckIQ): IQ
    }

    data class ColibriRequest(
        /**
         * The IQ which was received.
         */
        val request: ConferenceModifyIQ,
        /**
         * The [DelayStats] instance which is to be updated with the total time it took to handle the request
         * (including queueing delay).
         */
        val totalDelayStats: DelayStats,
        /**
         * The [DelayStats] instance which is to be updated with the time it took to process the request once it was
         * picked from the queue.
         */
        val processingDelayStats: DelayStats,
        val receiveTime: Long = System.currentTimeMillis(),
        /**
         * The callback to use to send the response.
         */
        val callback: (IQ) -> Unit
    )

    companion object {
        private val FEATURES = arrayOf(
            HealthCheckIQ.NAMESPACE,
            "urn:xmpp:jingle:apps:dtls:0",
            "urn:xmpp:jingle:transports:ice-udp:1",
            Version.NAMESPACE
        )

        private val delayThresholds = listOf(0, 5, 50, 100, 1000, Long.MAX_VALUE)

        private val colibriProcessingDelayStats = DelayStats(delayThresholds)
        private val colibriDelayStats = DelayStats(delayThresholds)
        private val healthDelayStats = DelayStats(delayThresholds)
        private val versionDelayStats = DelayStats(delayThresholds)

        val mucClientsConfigured = VideobridgeMetricsContainer.instance.registerLongGauge(
            "muc_clients_configured",
            "Number of MUC clients that are configured."
        )
        val mucClientsConnected = VideobridgeMetricsContainer.instance.registerLongGauge(
            "muc_clients_connected",
            "Number of MUC clients that are connected."
        )
        val mucsConfigured = VideobridgeMetricsContainer.instance.registerLongGauge(
            "mucs_connected",
            "Number of MUCs that are configured."
        )
        val mucsJoined = VideobridgeMetricsContainer.instance.registerLongGauge(
            "mucs_joined",
            "Number of MUCs that are joined."
        )

        @JvmStatic
        fun getStatsJson(): OrderedJsonObject = OrderedJsonObject().apply {
            put("colibri", colibriDelayStats.toJson())
            put("colibri_processing", colibriProcessingDelayStats.toJson())
            put("health", healthDelayStats.toJson())
            put("version", versionDelayStats.toJson())
        }
    }
}
