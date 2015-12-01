# Enable newrelic

Download newrelic.jar from your account there. The default not enabled config
expects that file is in /usr/share/newrelic/newrelic.jar.

Put the config file in /etc/jitsi/videobridge/newrelic.yml (the same, this is 
the default location and can be changed).
The newrelic.yml is a standard config file for newrelic you can find
more info at:
[https://docs.newrelic.com/docs/agents/java-agent/configuration/java-agent-config-file-template]()

Uncomment the property JVB_EXTRA_JVM_PARAMS in /etc/jitsi/videobridge/config.
And put the following property in /etc/jitsi/videobridge/sip-communicator.properties:
org.jitsi.videobridge.metricservice.NewRelic=org.jitsi.videobridge.metrics.NewRelicMetricPublisher
