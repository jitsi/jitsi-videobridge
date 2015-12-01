state.videobridge.getConferences().each() { conference ->
    // video content
    conference.getContents()[1].getChannels().each() { channel ->
        def (endpoint, selected, pinned) = [ channel.getEndpoint(),
            channel.getEndpoint().getSelectedEndpoint(),
            channel.getEndpoint().getPinnedEndpoint() ];

        println "${endpoint} sees ${selected ?: pinned}";
    }
}
