## Video Constraints

Video constraints are bi-directional messages that are exchanged between a given
endpoint and the bridge to control the exchanged video quality. Note that the
intention is to augment the format in the future with frame-rate and potentially
a max resolution/frame-rate.

###### Message format to the bridge

An endpoint can request different qualities for different endpoints, so it sends
a map of endpoint ids -> video constraints back to the bridge. In JSON notation
the message can be represented like this:

    {
       "colibriClass": "ReceiverVideoConstraintsChangedEvent",
       "videoConstraints": [{
           "id": "endpoint-1",
           "idealHeight": 180,
           "preferredHeight": 0,
           "preferredFps": 15.0
         }, {
           "id": "endpoint-2",
           "idealHeight": 360,
           "preferredHeight": 360,
           "preferredFps": 30.0
         },
         ...
       ]
     }

The preferredHeight and preferredFps properties are additional parameters that
control the bitrate allocation algorithm when it distributes the estimated
available bandwidth.

###### Message format from the bridge

The message from the bridge to a sending endpoint takes the following form in
JSON notation:

    {
      "colibriClass": "SenderVideoConstraints",
      "videoConstraints": {
        "idealHeight": 180
      }
    }

The bridge computes the ideal resolution of an endpoint ```S``` by taking the
max of the effective constraints (see below) of ```S``` across all other
endpoints in the call. 

## Legacy messages to the bridge

Prior to video constraints we had the selected, pinned and max resolution
messages. The bridge maintains full backwards compatibility with these legacy
messages, and they're still in use by Jitsi Meet at the time of this writing.

Internally, the bridge translates these legacy messages that it receives from
a given receiving endpoint ```R``` into video constraints messages that it later
on feeds to the bitrate allocation algorithm attached to endpoint ```R```.

Selected endpoints are those that the receiver ```R``` wants to see in HD
because they're on his/her stage-view (provided that there's enough bandwidth,
but that's up to the bitrate controller to decide).

The translation logic converts selected endpoints into video constraints by
setting the "idealHeight" to 720 reflecting the receiver's "desire" to watch the
track in high resolution. The translation logic also sets the "preferred"
resolution as well as the "preferred" frame rate. Under the hood this instructs
the bitrate controller to prioritize the endpoint during the bandwidth
allocation step and eagerly allocate bandwidth up to the preferred resolution
and preferred frame-rate.

Pinned endpoints are those that the receiver ```R``` "always" wants to have in
its last-n set even if they're not on-stage (again, provided that there's
enough bandwidth, but that's up to the bitrate controller to decide).

For pinned endpoints the translation logic sets the "ideal" height to 180,
reflecting the receiver's ```R``` "desire" always watch them.

The max height constrained was added for tile-view with the idea being that we
mark everything as selected (so endpoints aren't limited to 180p) and
set the max to 360p (so endpoints are limited to 360p, instead of 720p
which is normally used for selected endpoints.

For more information on how exactly the translation logic works you can take a
look at the ```VideoConstraintsCompatibility``` class in the bridge.

## Effective Video Constraints

Before the bridge can distribute the estimated available upload bandwidth
towards a specific receiver ```R```, it must first compute the _effective_
constraints of the endpoints to forward. The bridge derives the effective video
constraints by taking the video constraints signaled by ```R``` and then:

1. Insert ```{'idealHeight': 180}``` for the endpoints that are not present
   in the video constraints from ```R```.
2. Replace the video constraints for the endpoints that fall outside of the
   last-n set with ```{'idealHeight': 0}```.
   
Effective video constraints is an internal only structure that is used to
compute the `SenderVideoConstraints` above.