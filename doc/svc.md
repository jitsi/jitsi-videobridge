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
are done based on _new_ and the _top_ frames respectively, where the top frame is the
already-received frame that has the highest sequence number and new frames are frames
subsequent to the top in sequence number space.

If there is a gap in receive sequence numbers between the top and a new frame,
the SFU can determine the worst-case gap size of projected sequence numbers to
leave such that any not-yet-received packets can be forwarded correctly.
However, the SFU can't (in general) know the temporal layer of frames it has
not yet received. If it turns out that the not-yet-received frames were in fact
not ones that the SFU wanted to forward, this will result in sequence number
gaps in the projected bitstream, which the receiver may interpret as packet
loss.  (Care must be taken in bandwidth estimation algorithms not to incorrectly
reduce bandwidth based on this "false" packet loss.)

Order of operations for sequence number projection:
* For every encoding (received SSRC), remember the newest received sequence number,
  newest projected sequence number, the newest received frame, and whether
  the newest received frame was projected.
    + ("Newest" is always in sequence number order.)
* When a packet arrives, if it's newer than the previous newest packet on the encoding:
    + Compute the sequence delta from the previous newest packet.  Call this the "gap".
    + Set the newest received to this packet's sequence.
    + Check whether this packet is part of the previous newest received frame.
    + If this packet is part of the previous newest received frame:
        + Update the previous frame with information from this packet.
        + If that frame was not projected, return.
    + Otherwise, determine whether this new packet's frame should be projected.
       + If neither the previous frame and the new frame should be projected,
         and the two frames' picture IDs are consecutive, set the gap to 0.  Otherwise:
           + If the previous newest frame was not projected, and it had not received
        its final packet, subtract one from the gap (min 1).
           + If the new packet's frame should not be projected, and this is not its initial
        packet, subtract one from the gap (min 1).
           + If this frame should not be projected, subtract one from the gap (min 0).
       + Remember this frame as the newest frame, and also store it in the frame
         projection map.
    + Increment newest projected sequence number by the gap.  If this packet's frame
      should be projected, project this packet with this projected sequence number.
* Otherwise, if the packet is older than the previous newest packet:
    + Look up the packet's frame in the frame projection map.
    + If it's present, update the frame with this packet.
        + If that frame was projected, project it using that frame's sequence number mapping.
    + Otherwise, determine whether this frame should be projected.  Store this frame
      in the frame projection map.
    + Find the next frame in the frame projection map.  Use that frame's
      sequence number mapping for this new frame.
  
When starting a stream from scratch:
* Wait for a packet of a keyframe, requesting one if necessary.  When one arrives,
  initialize the projection information based on that packet, with an arbitrary
  sequence number mapping.
  
When switching to an encoding that is already being received:
* Wait for a packet of a keyframe.  When one arrives, compute the sequence number
  gap on that encoding from its previous newest received sequence number.
    + If that frame's previous newest frame hadn't received its final packet,
      add one to the gap size.
* Apply that gap to the previous encoding's highest projected sequence number.
* Set up the frame projection map appropriately.

When switching to an encoding which previously had not been received:
* Wait for either the first packet of a keyframe, or any packet of a subsequent keyframe.
* If the first packet of a keyframe is received, set a gap of 2 (if the previous
  encoding's last projected frame had sent its last packet) or 1 (if not) and compute
  a new sequence number mapping.
* If a packet of a subsequent keyframe is received, treat as above.
* Otherwise, when a non-first packet of a keyframe is received, put the track into
  a mode where it will cache all packets of TL0 frames (including keyframes).
  *TODO*: might defer this part.

-----

The SFU 

So decisions about if and how to forward a frame must be made
when a packet of a frame is first seen by the SFU.

apply
only on _new_ and the _top_ frames respectively, where the top frame is the
frame that is currently being forwarded and new frames are frames subsequent to
the top.

When the SFU sees a new frame, it can decide whether to forward it, drop it or
not process it (which effectively postpones the decision at a later time upon
reception of a subsequent packet of that or another frame). If the SFU decides to forward
it, then it becomes the top frame and the previous top frame (if there was one)
gets _finalized_ and its transformations are stored for re-application on
re-transmissions.

Another complication arises because of packet re-ordering and loss. In order to
better illustrate the problem we go through a series of examples that will help
explain a set of rules that are implemented in our SFU. For context, the TL
pattern that Google Chrome uses is TL0, TL2, TL1, TL2 [2]. These frames all have
the same TL0PICIDX [3] value.
  
EX1: Suppose that the SFU is in the middle of forwarding a TL0 (reference)
frame and that it (the SFU) either doesn't know or it cannot guess its ending
sequence number (due to packet loss or re-ordering). And suppose that the SFU
starts receiving the next TL2 frame (non-reference) that it wants to forward
because it (the SFU) is configured to forward high frame rate.
  
The problem in this case is that the SFU needs to leave enough space in the
sequence numbers for the TL0 frame to be forwarded after its fully received or
after its boundaries become known, otherwise there may either be gaps in the
sequence numbers or not enough space to squeeze in the TL0 frame (which would
render any subsequent frame undecodable at the receiver).
  
The correct thing to do in this case is to not forward the TL2, at least not
until the TL0 frame boundaries become known. More generally, no new frames can
be forwarded until the TL0 frame boundaries become known, unless a keyframe is
received (which can fully refresh the jitter buffer of receiving endpoint). 

EX2: Now suppose that the SFU has discovered the boundaries of the current
TL0 and that it (the SFU) has started sending the TL2 but its ending sequence
number is unknown (due to packet loss or re-ordering). Also suppose that the
next TL1 frame (reference) is received by the SFU. In this particular case we
have two options: either to not forward the TL1 until the TL2 frame boundaries
become known (delay the TL1) or "corrupt" the TL2 and immediately start sending
out the TL1. Corrupt in this context means stop forwarding an incomplete frame,
rendering it undecodable at the receiving endpoint. In our SFU implementation
we have implemented the second approach in order to minimize the delay. 

EX3: Going back to EX1, now suppose that the SFU starts receiving the next TL0
while the SFU is still trying to deduce the current TL0 frame boundaries. TL0
frames are important so the SFU cannot "corrupt" the current TL0 that is
forwarding and immediately start sending the next TL0 frame because that would
make the stream undecodable at the receiving endpoint. In order to simplify we
ask for a keyframe in this case.

The above 3 examples can be summarized in the following 3 rules:

1) TL0 reference frames MUST NOT be skipped nor corrupt, unless the new frame
is a keyframe. If the SFU starts receiving the next TL0 and if the current TL0
is incomplete, the SFU SHOULD ask for a keyframe.

2) non-TL0 reference frames MUST NOT be skipped nor corrupt, unless the new 
frame is the next TL0.

3) non-reference frames MAY be skipped and/or become corrupt (in order to
minimize delay) at any time.

NOTES: there may be something to write here about implementing a protection
mode that minimizes the aforementioned problems.

[simulcast]: https://ieeexplore.ieee.org/abstract/document/7992929
[1]: https://groups.google.com/d/topic/discuss-webrtc/gik2VH4hUjk/discussion
[2]: https://webrtchacks.com/sfu-simulcast/
[3]: https://tools.ietf.org/html/rfc7741
[4]: https://tools.ietf.org/html/rfc3550

