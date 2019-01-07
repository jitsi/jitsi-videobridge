## Introduction


The Jitsi Videobridge (JVB) is a Selective Forwarding Unit (SFU) (or a Selective
Forwarding Middlebox as per [[RFC7667]] nomenclature) and as such it
_terminates_ RTCP [[RFC3550]] traffic; the endpoints of a group call are
"tricked" to believe that that are in a one-on-one call with the JVB. This has
the notable desirable property that _bad_ down-link conditions at a particular
receiver do not typically affect the sending bitrate of the senders because,
from their perspective, there is only one receiver (the JVB) with a presumably
good down-link.

The JVB, in its turn, estimates its available up-link bandwidth [[BWE]] towards
a particular receiver (or, symmetrically, the available down-link of a
particular receiver) and it distributes it [[BWD]] among the several video
tracks that the several senders of a group-call are sharing. This results in a
bitrate allocation for each track of each sender for each receiver in a call.
Each video track is _projected_ [[RFC7667], Section-3.7] differently onto each
receiver, depending on its bitrate allocation and its adaptivity features such
as simulcast [[SIMULCAST]], scalable video coding [[SVC]] and last-N [[LASTN]].

While RTCP termination and bandwidth distribution are essential group
conferencing features they are not desirable and they may degrade the quality of
service in one-on-one calls. Unfortunately, there is no way to them at the
moment, i.e. it is not possible to make the JVB behave as a translator
[[RFC7667]], not even for one-on-one calls. Due to this limitation it is
recommended to use a TURN server [[TURN]] to relay one-to-one calls for optimal
conferencing experience.

In the next few paragraphs, we'll describe how RTCP termination works exactly
and which components are responsible for which task. For an overview of the
media transport aspects of the WebRTC framework and RTP usage
[[I-D.ietf-rtcweb-rtp-usage]] is highly recommended.

## RTCP for bandwidth estimations

RTCP Sender Reports (SRs) [[RFC3550], Section 6.4.1] are used by media senders
to compute RTT and by media receivers for A/V syncing and for computing packet
loss. In its media sender role the JVB projects SSRCs for bandwidth adaptivity,
it needs to produce its own SRs. RTCP SR generation takes place in the various
`AdaptiveTrackProjectionContext` implementations (see, for example,
`BasicAdaptiveTrackProjectionContext#rewriteRtcp`).

For estimating the available up-link bandwidth towards a particular receiver
(or, symmetrically, the available down-link bandwidth of a particular receiver)
the JVB expects from the receiver Receiver Reports (RRs) and Transport-wide
Congestion Control (TCCs) feedback
[[I-D.holmer-rmcat-transport-wide-cc-extensions]]. Receiver Estimated Maximum
Bitrate (REMBs) [[I-D.draft-alvestrand-rmcat-remb]]
can be used instead of TCCs if the delay-based controller is configured to run
on the receiving endpoint, but that's not the default nowadays.

The JVB (acting as a sender) consumes the TCC packets and feeds them to the
delay-based bandwidth estimator of our GCC implementation in order to produce a
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

## Other RTCP

Full Intra Request (FIRs) and Picture Loss Indication (PLIs) [[RFC5104]] are 
intercepted in `RTCPReceiverFeedbackTermination` and the bridge generates its 
own PLI request (which is throttled), if the receiving endpoint supports PLIs, 
or an FIR otherwise. The JVB may also decide to send a PLI/FIR when specific
events happen, such as a participant A has gone on-stage on a participant B.

Negative ACKnoledgements (NACKs) [[RFC4585]] are intercepted in 
`RTXTransformer#reverseTransform`. Once a NACK packet is handled (i.e. the 
reportedly missing packets have been re-transmitted, if they're in the egress 
packet cache), it's removed from the RTCP compound packet and the compound 
packet is let through. The bridge will generate its own NACKs when it detects a
whole in the sequence numbers. This is handled by the `RetransmissionRequester`.

[SIMULCAST]: simulcast.md
[LASTN]: last-n.md
[SVC]: svc.md
[BWE]: bandwidth-estimations.md
[BWD]: bandwidth-distribution.md
[TURN]: https://github.com/jitsi/jitsi-meet/blob/master/doc/turn.md
[RFC7667]: https://tools.ietf.org/html/rfc7667
[RFC3550]: https://tools.ietf.org/html/rfc3550
[RFC4585]: https://tools.ietf.org/html/rfc4585
[RFC5104]: https://tools.ietf.org/html/rfc5104
[I-D.ietf-rtcweb-rtp-usage]: https://tools.ietf.org/html/draft-ietf-rtcweb-rtp-usage-26
[I-D.holmer-rmcat-transport-wide-cc-extensions]: https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
[I-D.draft-alvestrand-rmcat-remb]: https://tools.ietf.org/html/draft-alvestrand-rmcat-remb-03