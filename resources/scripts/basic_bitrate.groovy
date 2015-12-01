state.videobridge.getConferences().each() { conference ->
    // video content
    conference.getContents()[1].getChannels().each() { channel ->
        def strategy = new org.jitsi.impl.neomedia.rtcp.termination.strategies.BasicRTCPTerminationStrategy();
        channel.getStream().setRTCPTerminationStrategy(strategy)
    }
}
