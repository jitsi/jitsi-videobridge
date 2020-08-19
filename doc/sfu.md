# Selective Forwarding Unit implementation of the Jitsi Videobridge

## Introduction

The Jitsi Videobridge (JVB) is a Selective Forwarding Unit (SFU) (or a Selective
Forwarding Middlebox as per [[RFC7667]] nomenclature) and, as such, it has
several advanced features such as RTCP termination [[RTCPT]], bandwidth
estimations [[BWE]] and bandwidth distributions [[BWD]].

With RTCP termination the endpoints of a group call are "tricked" to believe
that they are in a one-on-one call with the JVB. This has the desirable
property that _bad_ down-link conditions at a particular receiver do not
typically affect the sending bitrate of the senders because, from their
perspective, there is only one receiver (the JVB) with a presumably good
down-link. The JVB, in its turn, _estimates_ the available down-link of a
particular receiver (or, symmetrically, its available up-link bandwidth towards 
a particular receiver) and it _distributes_ it among the several video _sources_
[[MCS], section 4.3] that the several senders of a group-call are sharing.

Due to different video sources having different bitrate allocations depending on
the video source receiver, each video source is _projected_ [[RFC7667],
Section-3.7] differently onto each receiver. In the simplest case possible where
a video source is offered in a single _encoding_ [[WEBRTC], section 5.2] the JVB
makes use of last-n [[LASTN]] where it either forwards the video source, if there
is enough bandwidth, or otherwise drops it. When a source is offered in multiple
encodings, senders are sending multiple _bitstreams_ (multistream) of the same
video source (using either with simulcast [[SIMULCAST]] or scalable video coding
[[SVC]]) and the JVB decides which bitstream to send to which receiver. An
encoding in webrtc terms is the same as a bitstream so we will use these two
terms interchangeably throughout this document.

The specific algorithm that the JVB uses to distribute the available (estimated)
bandwidth among the different video sources merits its own section and is
described bellow [[BWE]], but, for sake of clarity, we give a couple of
examples in the next few paragraphs.

Consider, for example, a group call where the endpoints share a video source in
two encodings, a "high quality" and a "low quality." The JVB will chose to
forward at most one encoding at a time to a specific destination endpoint
depending on the available (estimated) bandwidth and the viewport of the
projected source. Multistreaming is achieved in different ways, depending on the
codec used to encode a source. For instance, VP8 supports temporal
scalability but not spatial scalability. In the previous example, to achieve a high 
and low quality, simulcast must be used. With VP9, the engine could activate 
spatial scalability.

With scalable video coding (SVC) a bitstream can be broken down into multiple
sub-bitstreams of lower frame rate (that we call temporal layers or TL)
and/or lower resolution (that we call spatial layers or SL). The exact
dependencies between the different layers of an SVC bitstream are codec
specific but, as a general rule of thumb higher temporal/spatial layers
depend on lower temporal/spatial layers creating a dependency graph.

For example, a scalable 720p@30fps VP8 bitstream is broken down into 3
sub-bitstreams: one 720p@30fps layer (the highest temporal layer), that
depends on a 720p@15fps layer that depends on a 720p7.5fps layer (the lowest
temporal layer). For the decoder to decode the highest
temporal layer, it needs to get all the packets of its direct and transitive
dependencies, so of both the other two layers. In this simple case, we have a
linked list of dependencies with the lowest temporal layer as root and
the highest temporal layer as a leaf.

In the next few paragraphs, we'll describe how RTCP termination works exactly
and which components are responsible for which task. For an overview of the
media transport aspects of the WebRTC framework and RTP usage
[[I-D.ietf-rtcweb-rtp-usage]] is highly recommended.

## RTCP termination

RTCP Sender Reports (SRs) [[RFC3550], Section 6.4.1] are used by media senders
to compute RTT and by media receivers for A/V syncing and for computing packet
loss. In its media sender role, the JVB projects SSRCs for bandwidth adaptivity,
it needs to produce its own SRs. RTCP SR generation takes place in the various
`AdaptiveSourceProjectionContext` implementations (see, for example,
`BasicAdaptiveSourceProjectionContext#rewriteRtcp`).

For estimating the available down-link bandwidth of a particular receiver (or,
symmetrically,  the available up-link bandwidth towards a particular receiver)
the JVB expects from the receiver Receiver Reports (RRs) and Transport-wide
Congestion Control (TCCs) feedback
[[I-D.holmer-rmcat-transport-wide-cc-extensions]]. Receiver Estimated Maximum
Bitrate (REMBs) [[I-D.draft-alvestrand-rmcat-remb]]
can be used instead of TCCs if the delay-based controller is configured to run
on the receiving endpoint, but that's not the default nowadays.

The JVB (acting as a sender) consumes the TCC packets and feeds them to the
delay-based bandwidth estimator of our GCC implementation to produce a
bandwidth estimation [[BWE]]. The TCC packets (along with RRs and REMBs) are
intercepted in `MediaStreamStatsImpl` where listeners can register and receive
notifications about incoming packets. They are removed from incoming RTCP
compound packets by the `RTCPReceiverFeedbackTermination#reverseTransform`.

As a media receiver the JVB reports packet loss information back to the sending
endpoints. RTCP SR consumption for packet loss computation happens in FMJ, which
is the reason why we always let RTCP SRs to go through the translator core of
the JVB (see `VideoChannel#rtpTranslatorWillWrite()`). The JVB produces RRs and 
REMBs (if the delay-based controller runs at the receiver) at a regular pace in
`RTCPReceiverFeedbackTermination`.

Full Intra Request (FIRs) and Picture Loss Indication (PLIs) [[RFC5104]] are 
intercepted in `RTCPReceiverFeedbackTermination` and the bridge generates its 
own PLI request (which is throttled), if the receiving endpoint supports PLIs, 
or an FIR otherwise. The JVB may also decide to send a PLI/FIR when specific
events happen, such as a participant A has gone on-stage on a participant B.

Negative ACKnowledgements (NACKs) [[RFC4585]] are intercepted in 
`RTXTransformer#reverseTransform`. Once a NACK packet is handled (i.e. the 
reportedly missing packets have been re-transmitted, if they're in the egress 
packet cache), it's removed from the RTCP compound packet and the compound 
packet is let through. The bridge will generate its own NACKs when it detects a
whole in the sequence numbers. This is handled by the `RetransmissionRequester`.

## Bandwidth Estimations

The JVB implements the Google Congestion Control (GCC)
[[I-D.draft-ietf-rmcat-gcc]] algorithm to estimate the available bandwidth.

GCC is comprised of a delay-controller and a loss-controller. The
loss-controller reacts to losses and the delay-controller reacts to delay
variations. Since its original conception in 201X, GCC has had several
iterations. The basic architecture has remained the same, but the individual
components have evolved over time, in particular the delay-controller.

Originally, it (the delay-controller) was running at the receiver and it
employed a Kalman filter to filter out the noise in the PDV. Its measurements
were based on RTP timestamps but different RTP streams have different RTP
timestamp bases, which make it difficult to run CC on all of them.
 
- A next iteration was with the introduction of the abs-send-time.
- A next iteration was with an adaptive threshold gamma to avoid starvation when
  running against a TCP flow. 
- A next iteration implemented burst detection to improve performance over the
  WiFi.
- A next iteration was with transport-wide-cc and moving the delay-based
  controller at the sender.
- A next iteration replaced the Kalman filter is replaced by an exponential
  backoff filter and a simple linear regression.
- A next iteration has improved the probing mechanism and introduced probing
  while in ALR

The JVB implements send-side bandwidth estimations with a Kalman filter in the
delay-based controller.

## Bandwidth Distributions

The `BitrateController` is attached to a _destination_ `VideoChannel` and it
orchestrates the bandwidth adaptivity towards the endpoint of the attached video 
channel. The `BitrateController` "reacts" (see `BitrateController#update`) to 
different events such as endpoints joining or leaving the call, the available 
bandwidth estimation changing, the active speaker changing, the viewport of 
a projected source changing, etc. Reacting to events is a multistep process 
with the end-goal of delivering the best overall video quality to its attached 
endpoint within limits set by the bandwidth estimation and the options set by 
the endpoint.

The first step is to produce a bitrate allocation for each sender's video source 
for each receiver in a call (see `SourceBitrateAllocation`). This step
determines which bitstream (encoding) to forward to each projected source.

The next step is to update the list of video source projections that it maintains
(see `AdaptiveSourceProjection`) with the newly computed quality targets. It is
then the responsibility of the specific adaptive source projection instances to
strive to achieve the target quality. The particular way of achieving the target
quality depends on the video source codec and on the encodings that the video
source is offering. The offered encodings and the codec used are orthogonal
concepts. An endpoint can be configured to send two encodings, and it has the
option to change the codec used on the fly and pass from VP8 to H264.
The specific handling of the different codec to achieve the different
qualities is implemented in the different specializations of the
`AdaptiveSourceProjectionContext` interface.

## Bugs

While RTCP termination and bandwidth distribution are essential to group
conferencing, they are not desirable and may degrade service quality in one-on-one 
calls. Unfortunately, there is no way to disable these advanced features at the 
moment, i.e. it is not possible to make the JVB behave as a translator [[RFC7667]], 
not even for one-on-one calls. Due to this limitation, a TURN server [[TURN]] 
should relay one-to-one requests for the optimal conferencing experience.

[MCS]: https://www.w3.org/TR/mediacapture-streams
[WEBRTC]: https://www.w3.org/TR/webrtc/
[SIMULCAST]: simulcast.md
[LASTN]: last-n.md
[SVC]: svc.md
[RTCPT]: #rtcp-termination
[BWE]: #bandwidth-estimations
[BWD]: #bandwidth-distributions
[TURN]: https://github.com/jitsi/jitsi-meet/blob/master/doc/turn.md
[RFC7667]: https://tools.ietf.org/html/rfc7667
[RFC3550]: https://tools.ietf.org/html/rfc3550
[RFC4585]: https://tools.ietf.org/html/rfc4585
[RFC5104]: https://tools.ietf.org/html/rfc5104
[I-D.ietf-rtcweb-rtp-usage]: https://tools.ietf.org/html/draft-ietf-rtcweb-rtp-usage-26
[I-D.holmer-rmcat-transport-wide-cc-extensions]: https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
[I-D.draft-alvestrand-rmcat-remb]: https://tools.ietf.org/html/draft-alvestrand-rmcat-remb-03
[I-D.draft-ietf-rmcat-gcc]: https://tools.ietf.org/html/draft-ietf-rmcat-gcc-02
