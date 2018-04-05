# Introduction

In this context, simulcast is defined as the act of having a sender (S) send
simultaneously multiple different encoded streams of the same media source, in
particular the same video source encoded with different image resolutions (S1,
S2, S3), with the bridge (B) selectively forwarding only the appropriate stream
to a receiver (R1, R2, R3), chosen based on a set of predefined rules.

                                                        +-------+
                                                        |       |
                                   +---S1 V S2 V S3---> |  R1   |
                                   |                    |       |
                                   |                    +-------+
                                   |
        +-------+--S1--> +-------+ |                    +-------+
        |       |        |       | |                    |       |
        |   S   +--S2--> |   B   +-+---S1 V S2 V S3---> |  R2   |
        |       |        |       | |                    |       |
        +-------+--S3--> +-------+ |                    +-------+
                                   |
                                   |                    +-------+
                                   |                    |       |
                                   +--S1 V S2 V S3----> |  R3   |
                                                        |       |
                                                        +-------+

                        Figure 1: Simulcast overview


In the rest of this document we're going to describe the specifics of the
simulcast sender, the simulcast receiver and the simulcast router.

# The simulcast sender

Sending simulcast is enabled through SDP signaling. If you're using the
webrtc.org [native API](http://www.webrtc.org/native-code/native-apis), then
your client is simulcast capable as the simulcast implementation is open
source.

If you're using the [browser API](http://www.w3.org/TR/webrtc/) then it depends
on the browser because there is currently no standard way to activate simulcast
in the browser. either Chrome or a Chromium based browser. Mozilla is working
on the Firefox implementation which is based on the RID draft.

If you're writing a JavaScript application,
[sdp-simulcast](https://github.com/jitsi/sdp-simulcast) can take care of the
sender signaling and also munge the SDP for the simulcast receiver accordingly.

One unrealistic solution (there are many) for cases where native simulcast
support is not available in the sender is to do manual simulcast, i.e. grab N
media streams with different characteristics (for example low, medium and high
quality), add them to the peer connection and stream them simultaneously. Then
you would also have to implement a congestion control protocol that would
start/stop the streams based on the network conditions. This solution is
unrealistic because it's complex and it adds a lot of load on the sender.

# The simulcast receiver

The simulcast receiver can operate in three modes: _rewriting_, _switching_ and
_native_.

A few words about the three receiver modes. In the rewriting mode the bridge is
rewriting the simulcast streams, so they appear as a single stream at the
receiver. This is done by rewriting the SSRCs and the sequence numbers of both
the RTP and the RTCP packets in both directions: outgoing and incoming. This is
the default receiver mode and simulcast is completely transparent to the
receiver.

In the switching mode the bridge doesn't do any rewriting, it simply
starts/stops the streams based on its routing logic. In the native mode, the
browser stack (or the native stack) of the receiver is simulcast aware and it's
signaled through SDP.

In the rewriting mode there is not much to do. As it has already been mentioned
in the introduction of this document, the bridge is rewriting the simulcast
streams, so they appear as a single stream at the receiver.

In the switching mode, the bridge is sending the following data channel
messages to the endpoint. Also keep that the _switching_ mode is in maintenance
mode and that we have no further improvements planned for it.

1. SimulcastLayersChangedEvent: Sent by the bridge to a receiving endpoint to
   indicate that there's been a change in the simulcast layers it receives.

	{
		'colibriClass': 'SimulcastLayersChangedEvent',
		'endpointSimulcastLayers': [
			{
				'endpoint': 'zez2rE4zzA',
				'simulcastLayer': {'primarySSRC': 'SSRC'}
			},
			...
		]
	}


2. SelectedEndpointChangedEvent: Sent by a receiving endpoint to the bridge to
   indicate its currently selected endpoint. The `selectedEndpoint` can be
   empty, if there's no endpoint currently being displayed at the receiver.

	{
		'colibriClass': 'SelectedEndpointChangedEvent',
		'selectedEndpoint': 'ENDPOINT_RESOURCE_ID'
	}

    It is also possible to select multiple endpoints by sending an ID array.
	{
		'colibriClass': 'SelectedEndpointChangedEvent',
		'selectedEndpoint': ['ENDPOINT_RESOURCE_ID1', 'ENDPOINT_RESOURCE_ID2']
	}


3. StartSimulcastLayerEvent: Sent by the bridge to a sending endpoint to
   indicate that it should resume the simulcast layer indicated by the
   `simulcastLayer` property value.

	{
		'colibriClass': 'StartSimulcastLayerEvent',
		'simulcastLayer': 'SSRC'
	}


4. StopSimulcastLayerEvent: Sent by the bridge to a sending endpoint to
   indicate that it should pause the simulcast layer indicated by the
   `simulcastLayer` property value.

	{
		'colibriClass': 'StopSimulcastLayerEvent',
		'simulcastLayer': 'SSRC'
	}

# The simulcast router

## Signaling

Simulcast routing is enabled through COLIBRI signaling. We signal simulcast to
the bridge through COLIBRI like this:

	<content name='video'>
	  <channel id='c9726594ccb4ede7'>
		<payload-type id='100' name='VP8' clockrate='90000' channels='1'/>
		...
		<ssrc-group semantics='SIM'>
		  <source ssrc='1'>
		  <source ssrc='2'>
		</ssrc-group>
		<ssrc-group semantics='FID'>
		  <source ssrc='1'>
		  <source ssrc='3'>
		</ssrc-group>
		<ssrc-group semantics='FID'>
		  <source ssrc='2'>
		  <source ssrc='4'>
		</ssrc-group>
	  </channel>
	  ...
	</content>

The order of the sources in the simulcast ssrc-group is important and
must be from lowest to highest quality. 

You can specify the default layer with `receive-simulcast-layer` channel 
attribute. It accepts an integer and it determines index of layer. The 
default is the first layer which is 0.

For simulcast to work you need to use the `BasicBridgeRTCPTerminationStrategy`
RTCP termination strategy. You can configure it like this in you
$HOME/.sip-communicator/sip-communicator.properties file.

    org.jitsi.videobridge.rtcp.strategy=org.jitsi.impl.neomedia.rtcp.termination.strategies.BasicRTCPTerminationStrategy

## Routing

The bridge has a feature where when a layer switch is about to happen, it can
stream both the `current` and the `next` layer for a short period of time `T`.
The purpose of this feature is to smooth out the transition from the `current`
layer to the `next` layer. When the switch starts, the bridge fires the
`SimulcastLayersChanging` event.

After time `T`, the layers are rotated: the `next` layer becomes the `current`
layer and the bridge fires the `SimulcastLayersChanged` event. The only layer
that's streamed is the `current` layer.

If, for some reason, the `next` layer has been stopped before the
`SimulcastLayersChanged` event has been fired, i.e. before the layer rotation,
then the bridge fires the `NextSimulcastLayerStopped` event.

`T` is not fixed and it corresponds to the time required for the bridge to see
`MIN_NEXT_SEEN` packets of the `next` stream. You can assign
its value in the sip-communicator.properties file like this:

    org.jitsi.videobridge.simulcast.SimulcastReceiver.MAX_NEXT_SEEN=N

The `MIN_NEXT_SEEN` constant can be used to enable or disable the aforementioned
functionality. `{N; N < 1}` are valid values that force the bridge to
immediately switch layers, i.e. disables layer rotation.

The `NextSimulcastLayerStopped` event and the `SimulcastLayersChanging` event
are only fired if `MIN_NEXT_SEEN >= 1`.

## Adaptive simulcast

In this section we describe how the first version of adaptive
simulcast in the bridge works.

When the user clicks on a thumbnail or otherwise changes the selected
participant, the client should send a `SelectedEndpointChangedEvent`
message to the bridge, so the bridge can keep track of the selected
(sending) endpoint on every (receiving) endpoint.

If it detects that a sender is not being viewed by any receiver, then
it sends a `StopSimulcastLayerEvent` message to that sender to stop
its high quality stream. If it detects that a sender is viewed by some
receiver, then it sends a `StartSimulcastLayerEvent` command to that
sender to start its high quality stream.

[rfc5576]: http://tools.ietf.org/html/rfc5576 "Source-Specific Media Attributes in the Session Description Protocol (SDP)"
[xep-jingle-sources]: http://www.xmpp.org/extensions/inbox/jingle-sources.html "XEP-xxxx: Source-Specific Media Attributes in Jingle"
[xep0167]: http://xmpp.org/extensions/xep-0167.html
[xep0166]: http://xmpp.org/extensions/xep-0166.html
[colibri]: http://xmpp.org/extensions/inbox/colibri.html
