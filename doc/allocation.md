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

For example, if the receiver wants to always receive an endpoint that is screensharing, regardless of who is speaking
in the conference, it can "select" this endpoint. 

#### On-stage endpoints
This is a list of endpoints for which allocation should be prioritized up to a higher resolution (since they are
displayed "on stage"). On-stage endpoints are prioritized higher than selected endpoints, and in addition:
1. Allocation for them is greedy up to the preferred resolution
([360p by default](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L40))
2. Above the preferred resolution, only frame rates of
[at least 30 fps](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L41)
are considered.

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
`LastN` value comes from the number signaled by the client, potentially also limited by [static](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/kotlin/org/jitsi/videobridge/JvbLastN.kt)
and [dynamic](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/kotlin/org/jitsi/videobridge/load_management/LastNReducer.kt)
configuration of the bridge. This is implemented by setting the `maxHeight` constraint to 0.

The resulting constraints are the "effective" constraints used by the rest of the algorithm. Once calculated, they are
announced via an event, so that the sender-side constraints can be applied. Doing this step here, early in the process,
allows us to do "aggressive layer suspension" (i.e. set sender-side constraints based on LastN).

### 3. Allocation
The final phase is the actual allocation.

#### 3.1 Initialize potential layers
The first step is to initialize a list of layers to consider for each endpoint. It starts with the list of all layers
for the endpoint, and prunes ones which should not be considered:

A) The ones with resolution and frame rate higher than the constraints

B) The ones which are inactive (the sending endpoint is currently not transmitting them)

C) Layers with high resolution but insufficient frame rate, that is at least the [preferred resolution](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L40),
and frame rate less than the [preferred frame rate](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L41).
For example, with the defaults of preferred resolution 360p and preferred frame rate 30 fps, the following layers will
not be considered: 360p/7.5fps, 360p/15fps, 720p/7.5fps, 720p/15fps.

#### 3.2 Allocation loop
It starts with no layers selected for any endpoint, and remaining bandwidth equal to the total available bandwidth.
Until there is remaining bandwidth, it loops over the endpoints in the order obtained in [phase 1](#1.-Prioritize),
and tries to `improve()` the layer of each.

The normal `improve()` step selects the next higher layer if there is sufficient bandwidth. For on-stage endpoints
the `improve()` step works eagerly up to the "preferred" resolution.
The preferred resolution [can be configured](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L40).

# Signaling
This section describes the signaling between the client and the bridge that affects bandwidth allocation.

## Legacy message format
This is the signaling currently used in jitsi-meet, but the intention is to replace it with the new format and
eventually deprecate this format.

This format is not expressive enough to enable all features supported by the bridge, so the following assumption is
made: The client is in TileView if it has selected more than one endpoint, otherwise it is in StageView.

In StageView, we set the single endpoint as "on-stage". In TileView, the signaled selected endpoints are ignored (for
backward compatibility with jitsi-meet).

Constraints are constructed solely based on `maxFrameHeight` and the signaled selected endpoints.
### LastN
LastN is set with a `LastNChangedEvent` message:
```json
{
  "colibriClass": "LastNChangedEvent",
  "lastN": 3
}
```

### MaxFrameHeight
A global maximum resolution is set with a `ReceiverVideoConstraint` message:
```json
{
  "colibriClass": "ReceiverVideoConstraint",
  "maxFrameHeight": 360
}
```

### Selected endpoints
Selected endpoints are set with a `SelectedEndpointsChangedEvent` message:
```json
{
  "colibriClass": "SelectedEndpointsChangedEvent",
  "selectedEndpoints": ["A", "B"]
}
```

## New message format
The new format uses a single message with a set of fields:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "lastN": 2,
  "selectedEndpoints": ["A", "B"],
  "onStageEndpoints": ["C", "D"],
  "defaultConstraints": { "maxHeight":  180 },
  "constraints": {
    "A": { "maxHeight": 720 },
    "B": { "maxHeight": 360 }
  }
}
```

All fields are optional. The ones which are included will be updated, and the ones which are not included are not
changed.

The `defaultConstraints` are used for endpoints not explicitly included in `constraints` (including new endpoints).

The initial values are `lastN: -1` (unlimited), `strategy: StaveView`, `defaultConstraints: {maxHeight: 180}`
([configurable](https://github.com/jitsi/jitsi-videobridge/blob/master/jvb/src/main/resources/reference.conf#L38)),
and the rest empty.

### Examples

#### Stage view (1)
Stage view with endpoint `A` in high definition and all other endpoints in 180p:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "onStageEndpoints": ["A"],
  "defaultConstraints": { "maxHeight":  180 },
  "constraints": {
    "A": { "maxHeight": 720 }
  }
}
```

#### Stage view (2)
Stage view with endpoint `A` in high definition, `B`, `C`, `D` in 180p and all others disabled:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "onStageEndpoints": ["A"],
  "defaultConstraints": { "maxHeight":  0 },
  "constraints": {
    "A": { "maxHeight": 720 },
    "B": { "maxHeight": 180 },
    "C": { "maxHeight": 180 },
    "D": { "maxHeight": 180 }
  }
}
```

#### Stage view (3)
Stage view with endpoint `A` in high definition, `B`, `C`, `D` disabled and all others in 180p:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "onStageEndpoints": ["A"],
  "defaultConstraints": { "maxHeight":  180 },
  "constraints": {
    "A": { "maxHeight": 720 },
    "B": { "maxHeight": 0 },
    "C": { "maxHeight": 0 },
    "D": { "maxHeight": 0 }
  }
}
```

#### Stage view (4)
Stage view with endpoint `A` in high definition and all other endpoints in 180p, with "D" prioritized higher than 
the dominant speaker:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "onStageEndpoints": ["A"],
  "selectedEndpoints": ["D"],
  "defaultConstraints": { "maxHeight":  180 },
  "constraints": {
    "A": { "maxHeight": 720 }
  }
}
```

#### Tile view (1)
Tile view with all endpoints in 180p/15fps:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "defaultConstraints": { "maxHeight":  180, "maxFrameRate": 15 }
}
```

#### Tile view (2)
Tile view with all endpoints in 360p:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "defaultConstraints": { "maxHeight":  360 }
}
```

#### Tile view (3)
Tile view with 180p, endpoints `A` and `B` prioritized, and endpoints `C` and `D` disabled:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "selectedEndpoints": ["A", "B"],
  "defaultConstraints": { "maxHeight":  180 },
  "constraints": {
    "C": { "maxHeight":  0 },
    "D": { "maxHeight":  0 }
  }
}
```

#### Tile view (4)
Tile view with all endpoints disabled except `A`, `B`, `C`:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "defaultConstraints": { "maxHeight":  0 },
  "constraints": {
    "A": { "maxHeight":  180 },
    "B": { "maxHeight":  180 },
    "C": { "maxHeight":  180 }
  }
}
```
#### Multi-stage view (1)
With two on-stage endpoints, and up-to 4 other endpoints at 180p:
```json
{
  "colibriClass": "ReceiverVideoConstraints",
  "onStageEndpoints": ["A", "B"],
  "lastN": 6,
  "defaultConstraints": { "maxHeight":  180 },
  "constraints": {
    "A": { "maxHeight":  720 },
    "B": { "maxHeight":  720 }
  }
}
```
