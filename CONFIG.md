# New Config
JVB has transitioned to using a new library for Configuration and there are some changes that result from that.

TODO: information about reference.conf and application.conf
TODO: script for translation old config to new


###  Mappings from old properties to new properties
* `org.jitsi.videobridge.REGION` -> `videobridge.octo.region`
* `org.jitsi.videobridge.health.INTERVAL` -> `videobridge.health.interval`
* `org.jitsi.videobridge.health.TIMEOUT` -> `videobridge.health.timeout`
* `org.jitsi.videobridge.health.STICKY_FAILURES` -> `videobridge.health.sticky-failures`
* `org.jitsi.videobridge.EXPIRE_CHECK_SLEEP_SEC` -> `videobridge.expire-thread-interval`
* `org.jitsi.videobridge.DISABLE_RTX_PROBING` -> removed (it wasn't being used)
* `org.jitsi.videobridge.PADDING_PERIOD_MS` -> `videobridge.cc.padding-period`
* `org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT` -> `videobridge.cc.bwe-change-threshold-percent`
* `org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT` -> `videobridge.cc.thumbnail-max-height-px`
* `org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT` -> `videobridge.cc.onstage-preferred-height-px`
* `org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE` -> `videobridge.cc.onstage-preferred-framerate-fps`
* `org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND` -> `videobridge.cc.onstage-video-suspension-enabled`
* `org.jitsi.videobridge.TRUST_BWE` -> `videobridge.cc.trust-bwe`
* `org.jitsi.videobridge.ICE_UFRAG_PREFIX` -> `videobridge.transport.ice.ufrag-prefix`
* `org.jitsi.videobridge.USE_COMPONENT_SOCKET` -> `videobridge.transport.ice.use-component-socket`
* `org.jitsi.videobridge.KEEP_ALIVE_STRATEGY` -> `videobridge.transport.ice.keep-alive-strategy`
* `org.jitsi.videobridge.DISABLE_TCP_HARVESTER` -> `videobridge.transport.ice.disable-tcp-harvester`
* `org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT` -> `videobridge.transport.ice.single-port-harvester-port`
* `org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT` -> `videobridge.transport.ice.tcp-harvester-mapped-port`
* `org.jitsi.videobridge.TCP_HARVESTER_PORT` -> See [1] below
* `org.jitsi.videobridge.TCP_HARVESTER_SSLTCP` ->  `videobridge.transport.ice.use-ssltcp`
* `org.jitsi.videobridge.defaultOptions` -> `videobridge.xmpp.processing-options`
* `org.jitsi.videobridge.shutdown.ALLOWED_SOURCE_REGEXP` -> `videobridge.xmpp.shutdown-allowed-source-regex`
* `org.jitsi.videobridge.AUTHORIZED_SOURCE_REGEXP` -> `videobridge.xmpp.authorized-source-regex`
* `org.jitsi.videobridge.xmpp` -> `videobridge.enabled-apis`  See [2] below
* Legacy ICE4J properties -> see [3] below
* `org.jitsi.videobridge.ENABLE_STATISTICS` -> `videobridge.stats.enabled`
* `org.jitsi.videobridge.STATISTICS_INTERVAL` -> `videobridge.stats.interval`
* `org.jitsi.videobridge.STATISTICS_TRANSPORT`, `org.jitsi.videobridge.PUBSUB_NODE` and `org.jitsi.videobridge.PUBSUB_SERVICE` -> See [4] below
* `org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN` -> `videobridge.websockets.domain`
* `org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID` -> `videobridge.websockets.server-id`
* `org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE` -> `videobridge.websockets.enabled` **NOTE** it has been inverted from `disable` to `enable`
* `org.jitsi.videobridge.rest.COLIBRI_WS_TLS` -> `videobridge.websockets.tls`
* `org.jitsi.videobridge.octo.BIND_ADDRESS` -> `videobridge.octo.bind-address`
* `org.jitsi.videobridge.octo.PUBLIC_ADDRESS` -> `videobridge.octo.public-address`
* `org.jitsi.videobridge.octo.BIND_PORT` -> `videobridge.octo.port`


##### [1] Setting TCP harvester ports
The old behavior around TCP harvester ports was the following:

>If TCP harvesting was enabled, we'd check for the presence of a `org.jitsi.videobridge.TCP_HARVESTER_PORT` setting.  If it was set, we'd try to use that port for TCP and fail if it failed. If no `org.jitsi.videobridge.TCP_HARVESTER_PORT` was set, we'd use the `TCP_DEFAULT_PORT` (set to 443, not configurable) and fallback to `TCP_FALLBACK_PORT`  (set to 4443, not configurable) if it failed.

The new code operates this way:

>If TCP harvesting is enabled, we read the `videobridge.transport.ice.tcp-harvester-ports` property, which is a list of prots that will be tried for TCP harvesting (in the order they are given). By default the list contains `443` and `4443`, but it can be overridden with any values (and any amount of values) a user wants.

##### [2] Enabling APIs
Previously, APIs were enabled via setting individual fields: `org.jitsi.videobridge.xmpp`, for example.  Now we have a single `enabled-apis` field where the enabled APIs are listed.

##### [3] Legacy ICE4J properties
Previously, we [read from a set of legacy videobridge properties](https://github.com/jitsi/jitsi-videobridge/blob/6afe06c99f2e4046bff409e6bb80631330b26a32/src/main/java/org/jitsi/videobridge/Videobridge.java#L979-L1018) (which had since been moved to ICE4J itself).  This functionality has been removed (because the change happened long ago and it should no longer be needed) but therer is support for reading from an `ice4j` section of the config and inserting every property there into the system properties.

##### [4] Stats transports
Previously, stats transports were set via a comma-separated string in the `org.jitsi.videobridge.STATISTICS_TRANSPORT` property.  The `pubsub` stats transport had extra settings which were set by the `org.jitsi.videobridge.PUBSUB_SERVICE` and `org.jitsi.videobridge.PUBSUB_NODE` properties.  The new config defines a `videobridge.stats.transports` property which is a list of stats transport config objects, correlating to the `org.jitsi.videobridge.stats.config.StatsTransportConfig` object.  There is a subclass `org.jitsi.videobridge.stats.config.PubSubStatsTransportConfig` for pubsub which holds the extra data relevant to the pubsub config.  The data in the config value is parsed directly into this config file instances and used in the code to instantiate the stats transports.

## Legacy config
In some places, JVB may use other Jitsi libraries which rely on the old `ConfigurationService` interface.  Other libraries, which still use the old `ConfigurationService` still rely on these libraries, so we don't want to migrate them to the new config.  For these, JVB defines its own `ConfigurationService` implementation: `LegacyConfigurationServiceShim` which provides the existing interface but reads from the new config.  Known properties that rely on this path are:

##### AbstractJettyBundleActivator
* `org.jitsi.videobridge.rest.private.jetty.sslContextFactory.keyStorePath`
* `org.jitsi.videobridge.rest.private.jetty.port`
* `org.jitsi.videobridge.rest`
* `org.jitsi.videobridge.rest.jetty.sslContextFactory.keyStorePath`
* `org.jitsi.videobridge.rest.jetty.port`

##### PublicClearPortRedirectBundleAcivator
* `org.jitsi.videobridge.rest.jetty.tls.port`

##### BandwidthEstimatoImpl (This will go away when JMT is updated to use new config)
* `org.jitsi.impl.neomedia.rtp.sendsidebandwidthestimation.BandwidthEstimatorImpl.START_BITRATE_BPS`
* `org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation.lossExperimentProbability`
* `org.jitsi_modified.impl.neomedia.rtp.sendsidebandwidthestimation.SendSideBandwidthEstimation.timeoutExperimentProbability`
*

##### MucClientConfiguration (the usage here can be changed to use `loadFromMap` instead of `loadFromConfigurationService`)
* All props with perfix `org.jitsi.videobridge.xmpp.user.`

##### ComponentMain
* `ConfigurationService#logConfigurationProperties`

##### EndpointConnectionStatus (this will change to use new config)
* `org.jitsi.videobridge.EndpointConnectionStatus.FIRST_TRANSFER_TIMEOUT`
* `org.jitsi.videobridge.EndpointConnectionStatus.MAX_INACTIVITY_LIMIT`
