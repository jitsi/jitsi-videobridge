state.videobridge.getConferences().each() { conference ->
    // video content
    conference.getContents()[1].getChannels().each() { channel ->
        println "Endpoint: $channel.endpoint.id"
        channel.transformEngine.simulcastEngine.simulcastSenderManager.senders.each() { receiver, sender ->
            println "Receiving from the bridge: $sender"
            println "The bridge receives : $receiver"
        }

        print "Rewriting: $channel.stream.ssrcRewritingEngine"
    }
}
