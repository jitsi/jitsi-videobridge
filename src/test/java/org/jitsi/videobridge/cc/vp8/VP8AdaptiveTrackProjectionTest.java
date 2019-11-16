package org.jitsi.videobridge.cc.vp8;

import org.jitsi.nlj.format.*;
import org.jitsi.utils.logging.DiagnosticContext;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.cc.*;
import org.junit.*;

import java.util.concurrent.*;

public class VP8AdaptiveTrackProjectionTest
{
    private final DiagnosticContext diagnosticContext = new DiagnosticContext();
    private final Logger logger = new LoggerImpl(getClass().getName());
    private final PayloadType payloadType = new Vp8PayloadType((byte)96,
        new ConcurrentHashMap<>(), new CopyOnWriteArraySet<>());

    @Test
    public void simpleProjectionTest()
    {
        RtpState initialState =
            new RtpState(1, 10000, 1000000);

        VP8AdaptiveTrackProjectionContext context =
            new VP8AdaptiveTrackProjectionContext(diagnosticContext, payloadType,
                initialState, logger);
    }
}
