state.videobridge.conferences.each {
    def conference = [:];
    // conference.put("name", it.name);
    def contents = [];
    conference.put("contents", contents);
    it.contents.each {
        def content = [:];
        contents.add(content);

        // content.put("name", it.name);
        def channels = [];
        content.put("channels", channels);
        it.channels.findAll {
            it.hasProperty("stream") && it.stream.ssrcRewritingEngine.initialized
        }.each {
            def channel = [:];
            channels.add(channel);

            channel.put("endpoint", it.endpoint.id);
            def group_rewriters = [];
            channel.put("group_rewriters", group_rewriters);
            it.stream.ssrcRewritingEngine.origin2rewriter.values().each {
                def group_rewriter = [:];
                group_rewriters.add(group_rewriter);

                group_rewriter.put("target", it.ssrcTarget);

                def rewriters = [];
                group_rewriter.put("rewriters", rewriters);
                it.rewriters.each {
                    def rewriter = [:];
                    rewriters.add(rewriter);
                    rewriter.put("sourceSSRC", it.sourceSSRC);

                    def intervals = [];
                    rewriter.put("intervals", intervals);
                    it.intervals.values().each {
                        def interval = [:];
                        intervals.add(interval);
                        interval.put("min", it.extendedMinOrig);
                        interval.put("max", it.extendedMaxOrig);
                    }
                }
            }
        }
    }

    import groovy.json.JsonOutput as JsonOutput
    println JsonOutput.prettyPrint(JsonOutput.toJson(conference)).stripIndent();
}

