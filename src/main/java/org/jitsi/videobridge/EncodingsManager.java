package org.jitsi.videobridge;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.PayloadTypePacketExtension;
import org.eclipse.jetty.util.ConcurrentHashSet;

import java.util.*;

/**
 * Process information signaled about encodings (payload types, ssrcs, ssrc associations, etc.)
 * and gather it in a single place where it can be published out to all interested parties.
 *
 * The idea is to alleviate the following problem:
 * When a new endpoint joins, it can use the notification of new local encoding information to trigger
 * updating all other endpoints with that new information, but how does this new endpoint learn of
 * this information from all existing endpoints?  Existing endpoints don't currently have a good trigger
 * to notify new endpoints about their encoding information, so this was devised as a single location to
 * handle dispersing this information to all interested parties.
 */
public class EncodingsManager {
    private Map<String, List<SsrcAssociation>> ssrcAssociations = new HashMap<>();
    private Set<EncodingsUpdateListener> listeners = new ConcurrentHashSet<>();

    public void addSsrcAssociation(String epId, long primarySsrc, long secondarySsrc, String semantics) {
        List<SsrcAssociation> epSsrcAssociations = ssrcAssociations.computeIfAbsent(epId, k -> new ArrayList<>());
        epSsrcAssociations.add(new SsrcAssociation(primarySsrc, secondarySsrc, semantics));

        listeners.forEach(listener -> {
            listener.onNewSsrcAssociation(epId, primarySsrc, secondarySsrc, semantics);
        });
    }

    /**
     * Subscribe to future updates and be notified of any existing ssrc associations.
     * @param listener
     */
    public void subscribe(EncodingsUpdateListener listener) {
        listeners.add(listener);

        ssrcAssociations.forEach((epId, ssrcAssociations) -> {
            ssrcAssociations.forEach(ssrcAssociation -> {
                listener.onNewSsrcAssociation(epId, ssrcAssociation.primarySsrc, ssrcAssociation.secondarySsrc, ssrcAssociation.semantics);
            });
        });
    }

    interface EncodingsUpdateListener {
        void onNewSsrcAssociation(String epId, long primarySsrc, long secondarySsrc, String semantics);
    }

    private class SsrcAssociation {
        private long primarySsrc;
        private long secondarySsrc;
        private String semantics;

        SsrcAssociation(long primarySsrc, long secondarySsrc, String semantics) {
            this.primarySsrc = primarySsrc;
            this.secondarySsrc = secondarySsrc;
            this.semantics = semantics;
        }
    }


}
