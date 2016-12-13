/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.ratecontrol;

import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.transform.rewriting.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.lang.ref.*;
import java.util.*;

/**
 * This is an improvement of the {@link LastNController} that adds adaptivity
 * for the streams that the extended {@link LastNController} decides to forward.
 * It works by incrementally degrading the bitrate of the forwarded streams (by
 * using simulcast and SVC) to the point of completely suspending streams, if
 * there is not enough receive bandwidth at the receiving endpoint.
 *
 * @author George Politis
 */
public class AdaptiveBitrateController
    extends LastNController
    implements BandwidthEstimator.Listener,
               AutoCloseable
{
    /**
     * The {@link Logger} used by the {@link AdaptiveBitrateController} class to
     * print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(AdaptiveBitrateController.class);

    /**
     * FIXME adaptivity is disabled for now until we have a way to account
     * for sender-side packet loses in the receiver reports.
     */
    private final boolean trustBandwidthEstimations = false;

    /**
     * The currently forwarded {@link RTPEncodingImpl}, by
     * {@link MediaStreamTrackImpl} ID, by {@link RtpChannel} ID.
     */
    private final Map<String, Map<String, WeakReference<RTPEncodingImpl>>>
        current = new TreeMap<>();

    /**
     * The current target {@link RTPEncodingImpl}, by
     * {@link MediaStreamTrackImpl} ID, by {@link RtpChannel} ID.
     */
    private Map<String, Map<String, WeakReference<RTPEncodingImpl>>> target;

    /**
     * The collection of IDs of the {@link Endpoint}s that have been explicitly
     * marked as 'selected' and whose video streams should be optimized.
     */
    private List<String> selectedEndpoints = INITIAL_EMPTY_LIST;

    /**
     * The last bandwidth estimation that this instance is aware of.
     */
    private long lastBweBps = Long.MAX_VALUE;

    /**
     * To be used by the bitrate prober.
     */
    private long optimalBitrateBps;

    /**
     * Indicates whether or not we listen for bandwidth estimations.
     */
    private boolean bweHooked;

    /**
     * Indicates whether or not this instance has been closed.
     */
    private boolean closed = false;

    /**
     * Ctor.
     *
     * @param channel the {@link VideoChannel} whose bitrate this instance is
     * adapting.
     */
    public AdaptiveBitrateController(VideoChannel channel)
    {
        super(channel);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bandwidthEstimationChanged(long newValueBps)
    {
        if (trustBandwidthEstimations)
        {
            lastBweBps = newValueBps;
        }

        askForKeyframes(update());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized boolean rtpTranslatorWillWrite(
        boolean data, byte[] buf, int off, int len, RtpChannel source)
    {
        if (closed)
        {
            return false;
        }

        if (!bweHooked)
        {
            // We do this here because we know that the stream is ready at this
            // point.
            bweHooked = true;
            BandwidthEstimator bandwidthEstimator
                = channel.getStream().getBandwidthEstimator();

            if (bandwidthEstimator != null)
            {
                // Listen for bandwidth estimations.
                bandwidthEstimator.addListener(this);
            }
        }

        // We don't have to do anything else, if we drop all the things.
        if (target != null && target.isEmpty())
        {
            return false;
        }

        // First, let LastN do its job; if an endpoint is not forwarded, do
        // not bother with anything else.
        boolean accept
            = super.rtpTranslatorWillWrite(data, buf, off, len, source);

        if (!accept || !data)
        {
            // If the endpoint is not forwarded (it's outside of the LastN set)
            // or if this is RTCP (which RTCP termination deals with), then just
            // return.
            return accept;
        }

        // asserts: accept && data
        final MediaStreamTrackReceiver
            receiver = source.getMediaStreamTrackReceiver();
        if (receiver == null)
        {
            // By design, not all RTP channels have a track receiver, in which
            // case adaptivity is disabled and we just forward everything from
            // these non-adaptive channels.
            return accept;
        }

        final RTPEncodingImpl rtpEncoding = receiver.resolve(buf, off, len);
        if (rtpEncoding == null)
        {
            // Maybe signaling hasn't propagated yet or maybe this RTP encoding
            // has been teared down and this is a late packet. In any case, we
            // heavily rely on signaling and we can't forward anything unless
            // we have a description of what it is and where it belongs.
            logger.warn("encoding_not_found" +
                ",src_hash=" + source.getStream().hashCode() +
                ",dst_hash=" + channel.getStream().hashCode());

            return false;
        }

        RTPEncodingImpl curEncoding
            = getCurrentEncoding(buf, off, len, rtpEncoding);

        return curEncoding != null && curEncoding.requires(rtpEncoding);
    }

    /**
     * Determines the current encoding from the source and the target.
     *
     * @param buf
     * @param off
     * @param len
     * @param sourceEncoding
     * @return
     */
    private synchronized RTPEncodingImpl getCurrentEncoding(
        byte[] buf, int off, int len,
        RTPEncodingImpl sourceEncoding)
    {
        final RtpChannel source = sourceEncoding
            .getMediaStreamTrack().getMediaStreamTrackReceiver().getChannel();

        final MediaStreamTrackImpl sourceTrack
            = sourceEncoding.getMediaStreamTrack();
        final String sourceTrackId = sourceTrack.getMediaStreamTrackId();

        // Find target encoding for the source track.
        RTPEncodingImpl targetEncoding = null;
        if (target != null)
        {
            final Map<String, WeakReference<RTPEncodingImpl>> targetTracks
                = target.get(source.getID());

            if (targetTracks != null && !targetTracks.isEmpty())
            {
                final WeakReference<RTPEncodingImpl> weakTargetEncoding
                    = targetTracks.get(sourceTrackId);
                if (weakTargetEncoding != null)
                {
                    targetEncoding = weakTargetEncoding.get();
                    if (targetEncoding == null && logger.isDebugEnabled())
                    {
                        logger.debug("target_encoding_expired" +
                            ",stream_hash=" + channel.getStream().hashCode() +
                            ",track_id=" + sourceTrackId);
                    }
                }
            }
        }
        else
        {
            // No reason to call update() here. Update is called whenever is
            // appropriate (some channel's RTP encodings have changed, bandwidth
            // estimation has changed, selected endpoints have changed, last N
            // has changed).
        }

        if (targetEncoding == null)
        {
            // The RTP encoding has been garbage collected in the meantime.
            if (logger.isDebugEnabled())
            {
                logger.debug("source_channel_suspended" +
                    ",src_hash=" + source.getStream().hashCode() +
                    ",dst_hash=" + channel.getStream().hashCode());
            }

            return null;
        }

        Map<String, WeakReference<RTPEncodingImpl>>
            currentTracks = current.get(source.getID());

        RTPEncodingImpl currentEncoding = null;
        if (currentTracks == null)
        {
            // Nothing forwarded yet. Initialize.
            currentTracks = new TreeMap<>();
            current.put(source.getID(), currentTracks);
        }
        else
        {
            WeakReference<RTPEncodingImpl>
                weakCurrentEncoding = currentTracks.get(sourceTrackId);

            if (weakCurrentEncoding != null)
            {
                currentEncoding = weakCurrentEncoding.get();
                if (currentEncoding == null && logger.isDebugEnabled())
                {
                    logger.debug("current_encoding_expired" +
                        ",stream_hash=" + channel.getStream().hashCode() +
                        ",track_id=" + sourceTrackId);
                }
            }
        }

        // State checking and input gathering done. Decide whether to forward or
        // not.
        if (currentEncoding == null)
        {
            // We can only go up from here.
            if (source.getStream().isKeyFrame(buf, off, len))
            {
                if (sourceTrack.compare(sourceEncoding, targetEncoding) <= 0)
                {
                    RTPEncodingImpl base = sourceTrack.getRTPEncodings()[0];

                    // Do NOT scale up if we don't have clocks.
                    SsrcRewritingEngine ssrcRewritingEngine
                        = channel.getStream().getSsrcRewritingEngine();
                    if (ssrcRewritingEngine == null
                        || ssrcRewritingEngine.canRewrite(sourceEncoding))
                    {
                        // alright, let's start streaming.
                        currentTracks.put(
                            sourceTrackId, new WeakReference<>(sourceEncoding));
                        sourceEncoding.increment();
                        currentEncoding = sourceEncoding;
                    }
                    else
                    {
                        // curEncoding == null, so let's start from base.
                        currentTracks.put(sourceTrackId, new WeakReference<>(base));
                        base.increment();
                        currentEncoding = base;
                    }

                    return currentEncoding;
                }
                else
                {
                    // We do NOT want to exceed the target.
                    return null;
                }
            }
            else
            {
                // Without a key frame, it doesn't make sense to start
                // forwarding a new RTP stream.
                return null;
            }
        }
        else
        {
            // We already forward an encoding.
            if (targetEncoding != currentEncoding)
            {
                // The target is different from what we are currently forwarding.
                if (targetEncoding.requires(currentEncoding)
                    || currentEncoding.requires(targetEncoding))
                {
                    currentEncoding.decrement();
                    sourceEncoding.increment();

                    // same codec scale up/down.
                    currentTracks.put(sourceTrackId, new WeakReference<>(targetEncoding));
                    currentEncoding = targetEncoding;
                }
                else if (source.getStream().isKeyFrame(buf, off, len))
                {
                    boolean switchCurrent = false;

                    // A key frame is required for intra-codec scale up/down.
                    if (sourceTrack.compare(targetEncoding, sourceEncoding) <= 0
                        && sourceTrack.compare(sourceEncoding, currentEncoding) < 0)
                    {
                        switchCurrent = true;

                        if (logger.isTraceEnabled())
                        {
                            logger.trace("scale_down,stream_hash="
                                + channel.getStream().hashCode());
                        }
                    }

                    if (sourceTrack.compare(currentEncoding, sourceEncoding) < 0
                        && sourceTrack.compare(sourceEncoding, targetEncoding) <= 0)
                    {
                        switchCurrent = true;

                        logger.trace("scale_up,stream_hash="
                            + channel.getStream().hashCode());
                    }

                    if (!currentEncoding.isActive()
                        && sourceTrack.compare(sourceEncoding, currentEncoding) < 0)
                    {
                        switchCurrent = true;

                        logger.trace("stream_drop,stream_hash="
                            + channel.getStream().hashCode());
                    }

                    if (switchCurrent)
                    {
                        // Do NOT scale up/down if we don't have clocks.
                        SsrcRewritingEngine ssrcRewritingEngine
                            = channel.getStream().getSsrcRewritingEngine();
                        if (ssrcRewritingEngine == null
                            || ssrcRewritingEngine.canRewrite(sourceEncoding))
                        {
                            currentEncoding.decrement();
                            sourceEncoding.increment();
                            currentTracks.put(
                                sourceTrackId, new WeakReference<>(sourceEncoding));
                            currentEncoding = sourceEncoding;
                        }
                        else
                        {
                            if (logger.isDebugEnabled())
                            {
                                logger.debug("clock_not_found" +
                                    ",stream_hash=" + channel.getStream().hashCode()
                                    + " ssrc=" + sourceEncoding.getPrimarySSRC());
                            }
                        }
                    }
                }
            }

            return currentEncoding;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized List<String> update(List<String> newConferenceEndpoints)
    {
        if (closed)
        {
            return null;
        }

        List<String> enteringEndpoints = super.update(newConferenceEndpoints);

        final ConferenceAllocation allocation = new ConferenceAllocation(
            channel, getForwardedEndpoints(), selectedEndpoints, lastBweBps);

        // Optimize the allocation.
        allocation.compute();

        this.target = allocation.getTarget();
        this.optimalBitrateBps = allocation.getOptimalBitrateBps();

        // Determine who do we need to send an FIR to (always a superset of
        // enteringEndpoints).
        final Set<String> changedEndpoints = new HashSet<>();

        for (Map<String, WeakReference<RTPEncodingImpl>>
            targetTracks : target.values())
        {
            for (WeakReference<RTPEncodingImpl> weakTargetEncoding : targetTracks.values())
            {
                RTPEncodingImpl targetEncoding = weakTargetEncoding.get();
                if (targetEncoding == null)
                {
                    continue;
                }

                String trackId = targetEncoding
                    .getMediaStreamTrack().getMediaStreamTrackId();

                // Determine whether or not to send an FIR.
                RtpChannel srcChannel = targetEncoding.getMediaStreamTrack()
                    .getMediaStreamTrackReceiver().getChannel();
                Map<String, WeakReference<RTPEncodingImpl>>
                    currentTracks = current.get(srcChannel.getID());

                RTPEncodingImpl currentEncoding = null;
                if (currentTracks != null)
                {
                    WeakReference<RTPEncodingImpl>
                        weakCurrentEncoding = currentTracks.get(trackId);

                    if (weakCurrentEncoding != null)
                    {
                        currentEncoding = weakCurrentEncoding.get();
                    }
                }


                if (targetEncoding.getMediaStreamTrack().hasStarted()
                    // Always ask for a key frame if we're not forwarding anything.
                    && (currentEncoding == null ||

                    // Do not ask for a key frame for SVC layers.
                    (!currentEncoding.requires(targetEncoding)
                        && !targetEncoding.requires(currentEncoding)

                        // Do not ask for a key frame from suspended streams.
                        && targetEncoding.isActive())))
                {
                    if (!changedEndpoints.add(srcChannel.getEndpoint().getID()))
                    {
                        logger.debug("XXX");
                    }
                }
                else if (!targetEncoding.getMediaStreamTrack().hasStarted() && enteringEndpoints != null)
                {
                    enteringEndpoints.remove(srcChannel.getEndpoint().getID());
                }
            }
        }

        if (enteringEndpoints != null)
        {
            enteringEndpoints.addAll(changedEndpoints);
        }
        else
        {
            enteringEndpoints = new ArrayList(changedEndpoints);
        }

        if (logger.isDebugEnabled())
        {
            logger.debug("adaptivity_update" +
                ",stream_hash=" + channel.getStream().hashCode() +
                ",changed_endpoints=" + Arrays.toString(enteringEndpoints.toArray()));
        }

        return enteringEndpoints;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void close()
        throws Exception
    {
        for (Map<String, WeakReference<RTPEncodingImpl>> curTracks : current.values())
        {
            for (WeakReference<RTPEncodingImpl> wr: curTracks.values())
            {
                RTPEncodingImpl curEncoding = wr.get();
                if (curEncoding != null)
                {
                    curEncoding.decrement();
                }
            }
        }

        BandwidthEstimator bandwidthEstimator
            = channel.getStream().getBandwidthEstimator();

        if (bandwidthEstimator != null)
        {
            bandwidthEstimator.removeListener(this);
        }
    }


    /**
     * {@inheritDoc}
     */
    @Override
    public void setSelectedEndpointIds(List<String> newSelectedEndpointIds)
    {
        List<String> endpointsToAskForKeyframe = null;

        synchronized (this)
        {
            if (!equalAsSets(newSelectedEndpointIds, selectedEndpoints))
            {
                selectedEndpoints
                    = Collections.unmodifiableList(newSelectedEndpointIds);

                endpointsToAskForKeyframe = update();
            }
        }

        askForKeyframes(endpointsToAskForKeyframe);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void rtpEncodingParametersChanged(RtpChannel rtpChannel)
    {
        askForKeyframes(update());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getOptimalBitrateBps()
    {
        return optimalBitrateBps;
    }
}
