package org.jitsi.videobridge.transport;

import org.ice4j.ice.*;

public class TransportUtils
{
    /**
     * Determines whether at least one <tt>LocalCandidate</tt> of a specific ICE
     * <tt>Component</tt> can reach (in the terms of the ice4j library) a
     * specific <tt>RemoteCandidate</tt>
     *
     * @param component the ICE <tt>Component</tt> which contains the
     * <tt>LocalCandidate</tt>s to check whether at least one of them can reach
     * the specified <tt>remoteCandidate</tt>
     * @param remoteCandidate the <tt>RemoteCandidate</tt> to check whether at
     * least one of the <tt>LocalCandidate</tt>s of the specified
     * <tt>component</tt> can reach it
     * @return <tt>true</tt> if at least one <tt>LocalCandidate</tt> of the
     * specified <tt>component</tt> can reach the specified
     * <tt>remoteCandidate</tt>
     */
    public static boolean canReach(
            Component component,
            RemoteCandidate remoteCandidate)
    {
        return component.getLocalCandidates().stream().
                anyMatch(
                        localCandidate -> localCandidate.canReach(remoteCandidate));
    }

    /**
     * @return the highest local port used by any of the local candidates of
     * {@code iceStream}, which falls in the range [{@code min}, {@code max}].
     */
    public static int getMaxAllocatedPort(IceMediaStream iceStream, int min, int max)
    {
        return
                Math.max(
                        getMaxAllocatedPort(
                                iceStream.getComponent(Component.RTP),
                                min, max),
                        getMaxAllocatedPort(
                                iceStream.getComponent(Component.RTCP),
                                min, max));
    }

    /**
     * @return the highest local port used by any of the local candidates of
     * {@code component}, which falls in the range [{@code min}, {@code max}].
     */
    private static int getMaxAllocatedPort(Component component, int min, int max)
    {
        int maxAllocatedPort = -1;

        if (component != null)
        {
            for (LocalCandidate candidate : component.getLocalCandidates())
            {
                int candidatePort = candidate.getTransportAddress().getPort();

                if (min <= candidatePort
                        && candidatePort <= max
                        && maxAllocatedPort < candidatePort)
                {
                    maxAllocatedPort = candidatePort;
                }
            }
        }

        return maxAllocatedPort;
    }

}
