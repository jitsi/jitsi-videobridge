To enable callsstats on JVB, the following properties needs to be set 
in /etc/jitsi/videobridge/sip-communicator.properties

    # the callstats credentials
    io.callstats.sdk.CallStats.appId=
    io.callstats.sdk.CallStats.appSecret=

    # the id of the videobridge
    io.callstats.sdk.CallStats.bridgeId=

    # enable statistics and callstats statistics and the report interval
    org.jitsi.videobridge.ENABLE_STATISTICS=true
    org.jitsi.videobridge.STATISTICS_INTERVAL.callstats.io=30000
    org.jitsi.videobridge.STATISTICS_TRANSPORT=callstats.io