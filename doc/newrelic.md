# Enable New Relic Metrics

Read the New Relic documentation and download newrelic.jar at:
[https://docs.newrelic.com/docs/agents/java-agent/installation/java-agent-manual-installation]().
The expected location of the jar file is /usr/share/newrelic/newrelic.jar

newrelic.yml is the standard config file for New Relic, you can find more info at:
[https://docs.newrelic.com/docs/agents/java-agent/configuration/java-agent-config-file-template]()

Put newrelic.yml in the default location of /etc/jitsi/videobridge/newrelic.yml.
This can be changed in your video bridge config file by setting
```json
newrelic.config.file=/my/new/location/newrelic.jar
```

Uncomment the property JVB_EXTRA_JVM_PARAMS in /etc/jitsi/videobridge/config.
And put the following property in /etc/jitsi/videobridge/sip-communicator.properties:
```json
org.jitsi.videobridge.metricservice.NewRelic=org.jitsi.videobridge.eventadmin.metrics.NewRelicMetricPublisher
```

## Metrics Produced
    * Channels - total number of channels in a conference
    * Conference length - total length of conference in seconds
    * Video Streams - total number of video streams in a conference
    * Nb download packet loss - The number of RTP packets sent by the remote side, but not received by the video bridge
    * Nb upload packet loss - The number of RTP packets sent by the video bridge, but not received by the remote side.
    * Min download jitter - The minimum RTP jitter value reported by the video bridge in an RTCP report, in millisecond.
    * Max download jitter - The maximum RTP jitter value reported by the video bridge in an RTCP report, in milliseconds.
    * Avg download jitter - The average of the RTP jitter values reported to the video bridge in RTCP reports, in milliseconds
    * Min upload jitter - The minimum RTP jitter value reported to the video bridge in an RTCP report, in milliseconds.
    * Max upload jitter - The maximum RTP jitter value reported to the video bridge in an RTCP report, in milliseconds.
    * Avg upload jitter -  The average of the RTP jitter values reported to the video bridge in RTCP reports, in milliseconds.
    * Endpoints - Total number or endpoints/particpants connected to a conference 