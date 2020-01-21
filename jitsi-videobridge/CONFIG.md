# Configuration

##### 10/14/19
We've begun the migration to a new configuration framework.  The 'old' method (the `sip-communicator.properties` file and program arguments) will be supported throughout the transition and the bridge will be fully backwards-compatible with the old config.  No changes are necessary until we pull the plug on the old config completely, and we'll give ample warning before that time.  It's also worth noting that any new properties will only be supported in the new config files.

Moving forward, we'll be using lightbend's [config library]([https://github.com/lightbend/config](https://github.com/lightbend/config)).  Their GitHub page gives lots of details, but below are the changes most relevant to configuring the JVB.

### Configuration sources
During the transition, we'll continue to read from the original locations (`sip-communicator.properties` and program arguments) for all configuration properties, falling back to the new `reference.conf` file for defaults.  The `reference.conf` file holds defaults for all configuration properties, and shouldn't be modified in a deployment--though it may be interesting for developers who want to see the defaults.

To override values, users can either put a file named `application.conf` in the classpath or pass a path to a file as a system property via `-Dconfig.file=/path/to/config.conf`.  Values in the file given will override ones in `reference.conf`.  The lightbend docs do a good job of describing [how configuration is loaded]([https://github.com/lightbend/config#standard-behavior](https://github.com/lightbend/config#standard-behavior)), but here's an example of overriding a configuration value for JVB:

### Overriding default values
Say we want to override some of the default values in `reference.conf`, namely: the XMPP API server values, the health check interval, and whether or not onstage video suspension is enabled.  We'll create an `application.conf` files with the values we want to change:

```
videobridge {
    health {
        // Override the health check interval
        interval=60 seconds
    }
    cc {
        // Override the onstage video suspension setting
        onstage-video-suspension-enabled=true
    }
}
```
You can override just the values you want to change, all others will fallback to the defaults set in `reference.conf`

### Migrating from old config
Below are the mappings from old property values to new ones.  Note: for new property names, the 'flat paths' are given, i.e. a value of `videobridge.health.interval` corresponds to the setting:
```
videobridge {
  health {
    interval
  }
}
```
This list will be updated as properties are migrated:

| Old property name | New property name | Notes |
| -------- | ------- | ------- |
| org.jitsi.videobridge.health.INTERVAL | videobridge.health.interval | The new config models this as a duration, rather than an amount of milliseconds |
| org.jitsi.videobridge.health.TIMEOUT | videobridge.health.timeout | The new config models this as a duration, rather than an amount of milliseconds |
| org.jitsi.videobridge.health.STICKY_FAILURES | videobridge.health.sticky-failures | |
| org.jitsi.videobridge.EndpointConnectionStatus.FIRST_TRANSFER_TIMEOUT | videobridge.ep-connection-status.first-transfer-timeout | The new config models this as a duration, rather than an amount of milliseconds |
| org.jitsi.videobridge.EndpointConnectionStatus.MAX_INACTIVITY_LIMIT | videobridge.ep-connection-status.max-inactivity-limit | The new config models this as a duration, rather than an amount of milliseconds |
| org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT | videobridge.cc.bwe-change-threshold-pct | |
| org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT | videobridge.cc.thumbnail-max-height-px | |
| org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT | videobridge.cc.onstage-preferred-height-px | |
| org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE | videobridge.cc.onstage-preferred-framerate | |
| org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND | videobridge.cc.enable-onstage-video-suspend | |
| org.jitsi.videobridge.TRUST_BWE | videobridge.cc.trust-bwe | |
| org.jitsi.videobridge.PADDING_PERIOD_MS | videobridge.cc.padding-period | The new config models this as a duration, rather than an amount of milliseconds |
| org.jitsi.videobridge.DISABLE_RTX_PROBING | n/a | This property has been deprecated |
| org.jitsi.videobridge.rest.COLIBRI_WS_DISABLE | videobridge.websockets.enabled | The semantics of this property have been inverted (disable -> enable) |
| org.jitsi.videobridge.rest.COLIBRI_WS_DOMAIN | videobridge.websockets.domain | |
| org.jitsi.videobridge.rest.COLIBRI_WS_TLS | videobridge.websockets.tls | |
| org.jitsi.videobridge.rest.COLIBRI_WS_SERVER_ID | videobridge.websockets.server-id | |
| org.jitsi.videobridge.DISABLE_TCP_HARVESTER | videobridge.ice.tcp.enabled | The semantics of this property have been inverted (disable -> enable) |
| org.jitsi.videobridge.TCP_HARVESTER_SSLTCP | videobridge.ice.tcp.ssltcp | |
| org.jitsi.videobridge.TCP_HARVESTER_PORT | videobridge.ice.tcp.port | |
| org.jitsi.videobridge.TCP_HARVESTER_MAPPED_PORT | videobridge.ice.tcp.mapped-port | |
| org.jitsi.videobridge.SINGLE_PORT_HARVESTER_PORT | videobridge.ice.udp.port | |
| org.jitsi.videobridge.ICE_UFRAG_PREFIX | videobridge.ice.ufrag-prefix | |
| org.jitsi.videobridge.KEEP_ALIVE_STRATEGY | videobridge.ice.keep-alive-strategy | |

