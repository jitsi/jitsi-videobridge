Introduction
============

NOTE: This document is outdated atm.

Jitsi Videobridge by default relays all RTCP traffic end-to-end, which
is causing endpoint adaptivity to adjust to the worst recipient.

This document describes how to make Jitsi Videobridge terminate most
of that signalling and generate its own. We basically do that by
filtering sender reports, receiver reports and REMB messages.

Things like FIR requests (that basically just ask for a new key frame)
and NACKs (that request specific packet retransmission) are left
untouched and re-transmitted.

Understanding how Chrome behaves (with respect to RTCP feedback)
================================================================

We profiled RTCP traffic from Chrome and, apart from the core RTCP
reports (SRs, RRs, SDES and BYEs defined in RFC 3550), Chrome
understands and reacts to a bunch of other RTCP packets, in accordance
with [I-D.ietf-rtcp-rtp-usage]:

- Picture Loss Indication                              (from RFC 4585)
- Slice Loss Indication                                (from RFC 4585)
- Negative Acknowledgments                             (from RFC 4585)
- Reference Picture Selection Indication               (from RFC 4585)
- Full Intra Request                                   (from RFC 5104)
- Temporal-Spatial Trade-Off Request                   (from RFC 5104)
- Temporary Maximum Media Stream Bit Rate Request      (from RFC 5104)
- Temporary Maximum Media Stream Bit Rate Notification (from RFC 5104)
- Extended Jitter Reports                              (from RFC 5450)
- Receiver Reference Time XR Block                     (from RFC 3611)
- DLRR XR Block                                        (from RFC 3611)
- VoIP Metrics XR Block                                (from RFC 3611)
- Receiver Estimated Maximum Bitrate (from draft-alvestrand-rmcat-remb-03) 

Based on our tests, Chrome actively sends:

- Non-compound RRs.
- Compound RTCP packets that begin with an SR and that include an
  SDES. If the compound RTCP packet refers to a video stream, then it
  also includes a REMB and it may include a NACK or a PLI, if there
  was a lost frame.

So, we want to:

- Forward NACKs/PLIs and SRs from the peers (stripped off of any
  receiver feedback)
- Mute RRs (and receiver feedback in SRs)
- Generate RRs with REMBs. The exact values of the RR and REMB would
  be defined by the active RTCP termination strategy.

Implementation
==============

We implemented that in the following way :

- Abstracted away the RTCP report generation functionality from
  `RTCPTransmitter`. We introduced a new interface called
  `RTCPReportBuilder` whose implementations are plugged into the
  `RTCPTransmitter` (in FMJ). The default implementation is called
  `DefaultRTCPReportBuilderImpl`. It lives in FMJ and implements the
  current report generation behavior of FMJ.
  
- libjitsi and/or other parts of the video bridge can override the
  default RTCP report generation behavior by parametrizing the RTP
  session manager (in FMJ) with an implementation of the
  `RTCPReportBuilder` interface. Keep in mind that in the JVB each content
  has its own RTP translator.
  
- Created an interface called `RTCPPacketTransformer` whose purpose is
  to inspect and/or modify and/or eliminate incoming RTCP packets. It
  can be thought of as a `PacketTransformer` for incoming RTCP packets.
  
- Created an interface called `RTCPTerminationStrategy` containing two
  methods 1. `getRTCPReportBuilder()` and 2. `getRTCPPacketTransformer()`.
  
  `RTCPTerminationStrategy` implementations are the "chefs d'orchestre"
  of RTCP termination. They are meant to 1. optionally generate
  arbitrary RTCP packets, and 2. inspect and/or modify and/or
  eliminate incoming RTCP packets, through the `RTCPReportBuilder` and
  the `RTCPPacketTransformer` instances returned by their respective
  methods.
  
- Created an RTCP packet parser/assembler with support for the RTCP
  packets that are of interest to us : SRs, RRs, SDES, BYEs, REMBs,
  NACKs and PLIs.
  
- Created an RTCP transformer engine that's plugged into each
  `MediaStream` transform engine chain and that feeds incoming RTCP
  packets to the `RTCPPacketTransformer` of the currently active
  `RTCPTerminationStrategy` class for inspection and/or modification
  and/or elimination.

We've also implemented 4 simple strategies (`RTCPTerminationStrategy`
implementations) :

- `MaxThroughputRTCPTerminationStrategy` which maximizes endpoint
  throughput. It does that by sending REMB messages with the largest
  possible exp and mantissa values. This strategy is only meant to be
  used in tests.

- `MinThroughputRTCPTerminationStrategy` which minimizes endpoint
  throughput. It does that by sending REMB messages with the smallest
  possible exp and mantissa values. This strategy is only meant to be
  used in tests.

- `SilentBridgeRTCPTerminationStrategy` which forwards whatever it
  receives from the network but it doesn't generate anything. This
  strategy will be useful for conferences for up to 2 participants.

- `PassthroughRTCPTerminationStrategy` which forwards whatever it
  receives from the network and it also generates RTCP receiver
  reports using the FMJ built-in algorithm. This is the default
  behavior, at least for now.

Highest quality RTCP termination strategy
-----------------------------------------

We've also a strategy called `HighestQualityRTCPTerminationStrategy`,
that is to be used in production environments and that works in the
following way :

For each media sender we can calculate a reverse map of all its
receivers and the feedback they report. From this the bridge can
calculate a reverse map map (not a typo) like this:

    <media sender, <media receiver, feedback>>

For example, suppose we have a conference of 4 endpoints like in the
figure bellow :

       +---+      +-------------+      +---+
       | A |<---->|             |<---->| B |
       +---+      |    Jitsi    |      +---+
                  | Videobridge |
       +---+      |             |      +---+
       | C |<---->|             |<---->| D |
       +---+      +-------------+      +---+
       
    Figure 1: Sample video conference

The reverse map map would have the following form :

    <A, <B, feedback of B>>
    <A, <C, feedback of C>>
    ...
    <B, <A, feedback of A>>
    <B, <C, feedback of C>>
    ...

In other words, for each endpoint that sends video, the bridge
calculates the following picture :

                   +-------------+-data->+---+
                   |             |       | B | RANK#3
                   |             |<-feed-+---+
                   |             |
       +---+-data->|    Jitsi    |-data->+---+
       | A |       | Videobridge |       | D | RANK#1
       +---+<-feed-|             |<-feed-+---+
                   |             |
                   |             |-data->+---+
                   |             |       | C | RANK#2
                   +-------------+<-feed-+---+

    Figure 2: Partial view of the conference. A sends media and receives 
              feedback. B, D, C receive media and send feedback.

This calculation is not instantaneous, so it takes place ONLY when we
the bridge decides to send RTCP feedback, and not, for example, when
we inspect/modify incoming RTCP packets.

We do that by keeping a feedback cache that holds the most recent
`RTCPReportBlock[]` and `RTCPREMBPackets` grouped by media receiver
SSRC. So, at any given moment we have the last reported feedback for
all the media receivers in the conference, and from that we can
calculate the above reverse map map.

What's most interesting, maybe, is the score logic :


    double score = feedback.remb.mantissa * Math.pow(2, feedback.remb.exp);
    if (feedback.rr != null) {
      score = ((100 - feedback.rr.lost) / 100) * score;
    }


The score is basically the available bandwidth estimation after taking
into consideration the packet losses.

For each media sender and after having calculated the score for each
media receiver, we consider only the Nth percentile to find the best
score and we then report that one.

Configuration
-------------

The current active RTCP termination strategy can be defined in the
Jitsi Videobridge configuration file by adding a configuration
property that can be used to specify the RTCP termination strategy to
be used by the bridge:

       org.jitsi.videobridge.rtcp.strategy=STRATEGY_FULLY_QUALIFIED_NAME

Strategies can be swapped-in/out dynamically through COLIBRI which can
be useful both for testing (i.e. we change on the fly the active
strategy to observe the impact of the change). To enable, for example,
the highest quality RTCP termination strategy, the focus of a colibri
conference can execute:

    focus.setRTCPTerminationStrategy('org.jitsi.impl.neomedia.rtcp.' +
	      'termination.strategies.HighestQualityRTCPTerminationStrategy')

using the JavaScript Console, for example. The other 4 strategies
mentioned in the previous update can be enabled/disabled similarly :

    focus.setRTCPTerminationStrategy('org.jitsi.impl.neomedia.rtcp.' +
	      'termination.strategies.STRATEGY_NAME')
