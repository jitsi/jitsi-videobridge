# Bandwidth Allocation Algorithm

Bandwidth allocation is the process of selecting the set of layers to forward to a specific endpoint (the "receiver"),
or "allocating" the available bandwidth among the available layers. When conditions change, the algorithm is re-run,
and the set of forwarded layers is updated according to the new result.

The overall goal is to provide the most relevant and suitable set of streams to the receiver, given a limited bandwidth.

## Input
### Available bandwidth
The available bandwidth (or the Bandwidth Estimation, BWE) is the estimated bandwidth between the bridge and the
receiver. It is calculated elsewhere, and is only used as input for bandwidth allocation.

### Available sources
This is the list of streams being sent from the other endpoints in the conference. Streams have multiple layers, and
the algorithm selects one layer (or no layers) for each source.

For example, a simulcast sender can encode its video in 3 different encodings, with 3 different frame rates each. This
gives the allocator 9 layers to choose from.

The list of available sources changes when endpoints join or leave the conference, or when they signal a change in their
streams (such as when an endpoint switches from a video camera to screensharing or vice-versa).

### Receiver-specified settings
The following settings are controlled by the receiver, with messages over the "bridge channel".

#### LastN
LastN is the maximum number of video streams that the receiver wants to receive. To effectively stop receiving video
(for example to conserve bandwidth), the receiver can set LastN=0.

#### Selected endpoints
This is a list of endpoints to be prioritized first, overriding the natural speech activity order of the endpoints.

For example, if the receiver wants to always receive an endpoint that is screenshaing, regardless of who is speaking
in the conference, it can "select" this endpoint. 

#### Allocation Strategy
The allocation strategy tweaks the order in which bandwidth is allocated. Note that this does NOT affect the order of 
the endpoints themselves. Currently, there are two supported strategies: StageView and TileView.
 
In broad words, StageView is optimized for the case where the receiver renders one endpoint in a large viewport 
("on stage"), and the rest in small viewports ("thumbnails"). It offers bandwidth to the "on stage" endpoint first,
before moving on to the other endpoints. See [Implementation](#Implementation) for details.

TileView is optimized for the case where the receiver renders all endpoints in similarly sized viewports. It allocates
a low resolution layer for every endpoint, before trying to allocate higher resolutions. See
[Implementation](#Implementation) for details.

#### Video Constraints
Video constraints are resolution (`maxHeight`) and frame rate (`maxFrameRate`) constraints for each endpoint. These are
"soft" constraints in the sense that the bridge may exceed them in some circumstances (see below).
 
When set to a negative number, they indicate no constraints.

When set to 0, they signal that no video should be forwarded for the associated endpoint, and this is never exceeded.

When set to a positive number, the algorithm will attempt to select a layer which satisfies the constraints. If no
layers satisfy the constraints, and there is sufficient bandwidth, the algorithm will exceed the constraints and 
select the lowest layer. In practice this is relevant only in the case where a sender does not use simulcast (or SVC),
and encodes a single high-resolution stream. Given enough bandwidth, the stream will be forwarded even when the receiver
signaled low constraints.

## Implementation
The bandwidth allocation algorithm is implemented in [BandwidthAllocator](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/java/org/jitsi/videobridge/cc/allocation/BandwidthAllocator.java).

It consists of 3 phases:
### 1. Prioritize
This phase orders the available endpoints in the desired way. It starts with the endpoints ordered by speech activity
(dominant speaker, followed by the previous dominant speaker, etc). Then, it moves the endpoints which are NOT sending
video to the bottom of the list (this is actually implemented in [ConferenceSpeechActivity](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/java/org/jitsi/videobridge/ConferenceSpeechActivity.java).
Finally, the selected endpoints are moved to the TOP of the list.

TODO: Update the algorithm, to only move selected endpoint when they are sending video.

### 2. Apply LastN
This phase disables video for endpoints in the list that are not among the first `LastN`. Note that the effective 
`LastN` value comes from the number signaled by the client, potentially also limited by [configuration](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/kotlin/org/jitsi/videobridge/JvbLastN.kt).
This is implemented by setting the `maxHeight` constraint to 0.

The resulting constraints are the "effective" constraints used by the rest of the algorithm. Once calculated, they are
announced via an event, so that the sender-side constraints can be applied. Doing this step here, early in the process,
allows us to do "aggressive layer suspension" (i.e. set sender-side constraints based on LastN).

### 3. Allocation
The final phase is the actual allocation.

#### 3.1 Initialize potential layers
The first step is to initialize a list of layers to consider for each endpoint. It starts with the list of all layers
for the endpoint, and prunes which should not be considered:

A) The ones with resolution and frame rate higher than the constraints

B) The ones which are inactive (the sending endpoint is currently not transmitting them)

C) In the case of `StageView`, layers with high resolution but insufficient frame rate. The minimum frame rate [can
be configured](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L41).

#### 3.2 Allocation loop
It starts with no layers selected for any endpoint, and remaining bandwidth equal to the total available bandwidth.
Until there is remaining bandwidth, it loops over the endpoints in the order obtained in [phase 1](#1.-Prioritize),
and tries to `improve()` the layer of each.

The normal `improve()` step selects the next higher layer if there is sufficient bandwidth. For the case of the 
`StageView` strategy and the on-stage endpoint, the `improve()` step works eagerly up to the "preferred" resolution.
The preferred resolution [can be configured](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L40).

# Signaling
TODO
## Old message format
TODO
## New message format
TODO
