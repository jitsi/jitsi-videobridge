# SSRC rewriting principles for VP8 simulcast/SVC

WebRTC has no notion of [simulcast][simulcast] when receiving a video stream,
and it is therefore critical that any switch made by the SFU must be completely
transparent to the receiver [1].

1) In the case of sending an RTP stream without using picture ids the SFU has
to rewrite the RTP sequence numbers so that the stream looks continuous, and
rewrite the RTP timestamps so that there is no significant jump compared to the
last frame. The timestamp may not jump backward.

2) If picture ids are used, besides following the steps outlined in 1), the SFU
also has to rewrite the picture ids to make them continuous. Note that it is
not possible to suddenly drop picture ids from the stream, as the receiver only
keeps state relevant to picture ids, if they are used and therefore won't fall
back to RTP sequence numbers.

3) If picture ids, tl0 picture indexes and temporal indexes are used, besides
following the steps outlined in 2), the SFU also has to rewrite the tl0 picture
indexes. Note that it is possible to drop the tl0 picture indexes and the
temporal indexes from the stream, but if they are used again the tl0 picture
index has to continue from where it left off. Also note that the state of the
tl0 picture indexes is only updated by the receiver when both the tl0 picture
index and temporal index is received.

# Analysis

With simple simulcast, the SFU simply needs to apply a fixed delta (i.e. linear
translation) to the RTP sequence numbers/timestamps [4] and VP8 
picture IDs/tl0picidx [3] that it forwards. The specific deltas are determined 
when the stream switching occurs. This straightforward approach doesn't work 
well if the SFU wants to leverage VP8 temporal scalability. We start our 
analysis by exploring the implications of the first guideline and, as we will 
see, 2 and 3 follow naturally.

With temporal scalability in VP8 (and other codecs)[2], where the SFU can drop frames to achieve
lower frame rates, applying a fixed delta would leave gaps in the sequence
numbers (because of the packets that the SFU drops). So the SFU needs to keep
track of what it's sent and manage the sequence number space of the
SFU-receiver leg (i.e. generate sequence numbers for every egress packet of a
frame). This mode of operation has several implications.

First, it means that if the SFU decides to skip a frame for whatever reason,
it cannot go back and change that decision because there would be no space left
in the sequence numbers.  Furthermore, if packet re-ordering or loss has occurred,
a decision must be made immediately as to how much of a gap to leave in the
sequence numbers so delayed packets have a place to be transmitted.

When a frame arrives and it is to be forwarded, the SFU needs to determine how to
assign its projected sequence numbers.  Decisions on how to assign sequence numbers
are done by comparing the _new_ frame with an _existing_ (previously-projected) frame.
(In most cases this existing frame will be the previous projected frame, in sequence number
ordering to the new frame, though in some corner cases involving very old frames
a different existing frame is chosen.)

If there is a gap in receive sequence numbers between the existing and the new frame,
the SFU can determine the worst-case gap size of projected sequence numbers to
leave such that any not-yet-received packets can be forwarded correctly.
However, the SFU can't (in general) know the temporal layer of frames it has
not yet received. If it turns out that the not-yet-received frames were in fact
not ones that the SFU wanted to forward, this will result in sequence number
gaps in the projected packet sequence, which the receiver may interpret as packet
loss.  (Care must be taken in bandwidth estimation algorithms not to incorrectly
reduce bandwidth based on this "false" packet loss; in particular, this means
that senders operating on receiver-side REMB-based bandwidth estimation mustn't
adjust the estimates based on received loss.)

# Examples of projection

## Scenario A: Happy path (target TL0)

Sender sends: TL0 (1, 2, 3), TL2 (4, 5), TL1 (6, 7), TL2 (8), TL0 (9, 10, 11)

Bridge receives: what the sender sends

Bridge sends:  101, 102, 103, (9 -> 104), (10 -> 105), (11 -> 106)

When packet 9 arrives, the bridge can determine that sequence numbers 4 - 8
belong to non-routed packets and can be suppressed

## Scenario B: packet loss (target TL2)

Sender sends: TL0 (1, 2, 3), TL2 (4, 5), TL1 (6, 7), TL2 (8), TL0 (9, 10, 11)

Bridge receives: 1, 2, 3, 4, _(X)_, 6, 7, 8, 9, 10, 11, 5

Bridge sends:  101, 102, 103, 104, 106, 107, 108, 109, 110, 111, 105

When packet 6 arrives, the bridge computes the gap from the previously projected
frame.  Because it wants to route both the TL2 and TL1 frames, it leaves
a gap in the outgoing sequence numbers, into which packet 5 can later be slotted.

## Scenario C: packet loss 1 (target TL0)

Sender sends: TL0 (1, 2, 3), TL2 (4, 5), TL1 (6, 7), TL2 (8), TL0 (9, 10, 11)

Bridge receives: 1, 2, 3, 4, _(X)_, 6, 7, 8, 9, 10, 11, 5

Bridge sends:  101, 102, 103, (9 -> 104), (10 -> 105), (11 -> 106)

Similarly to Scenario A, when packet 9 arrives, the bridge can determine that
sequence numbers 4-8 belong to non-routed packets.  The status of the
unreceived packet 5 can be determined because packet 4 will not have its
"end of frame" flag set.

## Scenario C: packet loss 2 (target TL1)

Sender sends: TL0 (1, 2, 3), TL2 (4, 5), TL1 (6, 7), TL2 (8), TL0 (9, 10, 11)

Bridge receives: 1, 2, 3, 4, 5, 6, 7, _(X)_, 9, 10, 11, 8

Bridge sends:  101, 102, 103, (6 -> 104), (7 -> 105), (9 -> 107), (10 -> 108), (11 -> 109)

In this case, when packet 9 arrives, the bridge cannot determine what type of
frame packet 8 was -- it could have been a TL1.  Therefore it must leave a gap in
the outgoing sequence numbers.


[simulcast]: https://ieeexplore.ieee.org/abstract/document/7992929
[1]: https://groups.google.com/d/topic/discuss-webrtc/gik2VH4hUjk/discussion
[2]: https://webrtchacks.com/sfu-simulcast/
[3]: https://tools.ietf.org/html/rfc7741
[4]: https://tools.ietf.org/html/rfc3550

