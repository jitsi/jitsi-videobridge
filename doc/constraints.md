# Video Constraints

Video constraints are bi-directional messages exchanged between the endpoints
and the bridge. Endpoints can send video constraints messages to the bridge to
signal the video format they wish to receive from the bridge. In the opposite
direction, the bridge sends video constraints messages to a given endpoint so 
that it can adjust the video format that it sends. In their present form, video
constraints are just a glorified integer representing the ideal resolution of a
video source. In JSON notation they take the following form:

    {
      'idealHeight': resolution,
      'preferredHeight': resolution,
      'preferredFps': frame-rate
    }

The preferredHeight and preferredFps properties are additional parameters that
control the bitrate allocation algorithm when it distributes the estimated
available bandwidth, and they are therefore only relevant in the messages that
are emitted by endpoints.

# Legacy messages emitted by the endpoints

Prior to video constraints we had the selected, pinned and max resolution
messages. The bridge maintains full backwards compatibility with these legacy
messages, and they're still in use by Jitsi Meet at the time of this writing.
Internally, the bridge translates the legacy messages that it receives from the
client into video constraints messages that it later on feeds into the bitrate
allocation algorithm.

Selected endpoints are those that the receiver wants to see in HD because
they're on his/her stage-view (provided that there's enough bandwidth,
but that's up to the bitrate controller to decide).

We translate selected endpoints into video constraints by setting the
"idealHeight" to 720 reflecting the receiver's "desire" to watch the track in
high resolution. We also set the "preferred" resolution and the "preferred"
frame rate. Under the hood this instructs the bitrate controller to prioritize 
the endpoint during the bandwidth allocation step and eagerly allocate bandwidth 
up to the preferred resolution and preferred frame-rate.

Pinned endpoints are those that the receiver "always" wants to have in
its last-n set even if they're not on-stage (again, provided that there's
enough bandwidth, but that's up to the bitrate controller to decide).

For pinned endpoints we set the "ideal" height to 180, reflecting the
receiver's "desire" always watch them.

Note that semantically, selected is not equal to pinned. Pinned endpoints
that become selected are treated as selected. Selected endpoints that
become pinned are treated as selected. When a selected & pinned endpoint
goes off-stage, it maintains its status as "pinned".

The max height constrained was added for tile-view back when everything
was expressed as "selected" and "pinned" endpoints, the idea being we
mark everything as selected (so endpoints aren't limited to 180p) and
set the max to 360p (so endpoints are limited to 360p, instead of 720p
which is normally used for selected endpoints. This was the quickest, not
the nicest way to implement the tile-view constraints signaling and it
was subsequently used to implement low-bandwidth mode.

One negative side effect of this solution, other than being a hack, was
that the eager bandwidth allocation that we do for selected endpoints
doesn't work well in tile-view because we end-up with a lot of ninjas.

By simply setting an ideal height X as a global constraint, without
setting a preferred resolution/frame-rate, we signal to the bitrate
controller that it needs to (evenly) distribute bandwidth across all
participants, up to X.

# Effective Video Constraints

The bridge derives the effective video constraints for all the endpoints in a
call. The effective  from the video constraints
signaled by the client, augmented with

1. video constraints for the endpoints that fall outside of the last-n set
2. video constraints for the endpoints that are not present in the video
   constraints from the client.

Before the bitrate allocation algorithm runs, we need video constraints for all
endpoints, i.e. contraints for the thumbnails (video constraints of 180p) and
for the people that are outside of last-n (video constraints of 0p). These are
the effective video constraints.

# Layer suspension

TODO