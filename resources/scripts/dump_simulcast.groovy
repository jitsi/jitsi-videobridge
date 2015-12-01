state.videobridge.getConferences().each() { conference ->
    // video content
    conference.getContents()[1].getChannels().each() { channel ->
        println "Endpoint: $channel.endpoint.id"
        println "Sending: $channel.transformEngine.simulcastEngine.simulcastReceiver.simulcastLayers"
        channel.transformEngine.simulcastEngine.simulcastSenderManager.senders.each() { receiver, sender ->
            def layer = sender.sendMode.weakCurrent.get();
            println "Receiving: $layer"
        }
    }
}
