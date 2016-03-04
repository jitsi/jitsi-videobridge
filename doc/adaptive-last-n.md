# General
Jitsi Videobridge supports a *lastN* feature for video channels. This is an
integer, which can be set on a specific video channel. If set to *N* for a
channel, only the video from the first *N* other endpoints in the conference
will be sent to the channel. The endpoints are ordered by the last time they 
were the "dominant speaker".

*Adaptive lastN* is a feature of Jitsi Videobridge which dynamically adjusts
the value of *lastN* for a video channel based on the current network
conditions.

# Enabling adaptive lastN
In Jitsi Videobridge, *Adaptive lastN* can be enabled for a channel via COLIBRI
by setting the 'adaptive-last-n' channel attribute to *true*. Jitsi Meet supports
setting this attribute for all video channels through the 'adaptiveLastN'
configuration option in *config.js*.

Enabling *adaptive lastN* for a channel in Jitsi Videobridge requires that
the whole conference uses *BasicBridgeRTCPTerminationStrategy* as its RTCP
termination strategy. **This is set automatically, if adaptive lastN is enabled
through COLIBRI in any of the video channels**.

# Implementation details
We make a decision of whether to change the value of "lastN" for a given channel 
every time we receive an RTCP Receiver Estimated Maximum Bandwidth (REMB) packet.
Such packets contain an estimation of the available bandwidth from the
bridge to the endpoint (and the estimation is made by the receiver -- the endpoint).

Unless specifically configured to not do so, we replace the
timestamps contained in the abs-send-time RTP header extensions of all video RTP
packets with local timestamps generated at the time the packet leaves the bridge.
This helps to make the endpoints' <!--'--> estimation of the available bandwidth more
accurate (since otherwise the network jitter used would include the path the
RTP packet took from the sender to the bridge). See
[http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time](). This can be
disabled by setting the following property:

org.jitsi.videobridge.DISABLE\_ABS\_SEND\_TIME=true

We have an initial interval of INITIAL\_INTERVAL\_MS milliseconds, during which we do not
change lastN. The purpose of this is to allow the REMB to "ramp-up". If we start adapting
with the current algorithm from the start, we will always decrease lastN to 1
and then gradually increase it, even if the bandwidth allows a high value of lastN from
the start.

For the adaptation algorithm we have a simple FSM with three states: DECREASE, EQUAL and
INCREASE. When we receive a REMB, first we update the state, and then decide if we need
to change the value of *lastN*.

First we calculate the assumed available bandwidth as A = REMB\_MULT\_CONSTANT * AVG,
where AVG is the average of the REMB values received in the last
REMB\_AVERAGE\_INTERVAL\_MS milliseconds.

Then we calculate the number K as the number of endpoints which "fit" in the
available bandwidth A. That is, the maximum K not exceeding the total number of
other endpoints, such that the first K endpoints' <!-- '--> "current"
cumulative sending bitrate does not exceed A.

We calculate the "current" sending bitrate of an endpoint based on total number of
bytes received over the last 5 seconds, but we always assume at least
MIN\_ASSUMED\_ENDPOINT\_BITRATE\_BPS bits per second. We use such a long period
in order to reduce the fluctuations in the bitrates -- we do not want to change
*lastN* as a result of very short periods of increased or decreased bitrate
from an endpoint.

<!---
TODO describe how this works with simulcast or transform this into a more 
general document that will describe bitrate control
-->


After we have K, we update the state:

If K < lastN, we enter DECREASE

If K = lastN, we enter EQUAL

If K > lastN, we enter INCREASE


Finally, we update lastN:

If we have always been in state DECREASE in the last DECREASE\_LAG\_MS milliseconds, we set
lastN = max(1, lastN/2)

If we have always been in state INCREASE in the last INCREASE\_LAG\_MS milliseconds, we set
lastN = lastN + 1

Otherwise, we don't <!--'--> change lastN.

We try to decrease aggressively, and increase conservatively.


When lastN falls to 0, the endpoint will not receive any video. Consequently, it will still emit
REMB packets, however their bitrate will not increase. For this reason if lastN stay 0 for
MAX\_STAY\_AT\_ZERO\_MS milliseconds, we will increase it to 1, in order to "probe" for available
bandwidth.


# Configuration
The parameters from the above section can be configured with the following
properties (with their default values given):

org.jitsi.videobridge.ratecontrol.LastNBitrateController.INCREASE\_LAG\_MS=20000
org.jitsi.videobridge.ratecontrol.LastNBitrateController.DECREASE\_LAG\_MS=10000
org.jitsi.videobridge.ratecontrol.LastNBitrateController.INITIAL\_INTERVAL\_MS=70000
org.jitsi.videobridge.ratecontrol.LastNBitrateController.REMB\_MULT\_CONSTANT=1.0
org.jitsi.videobridge.ratecontrol.LastNBitrateController.MIN\_ASSUMED\_ENDPOINT\_BITRATE\_BPS=400000
org.jitsi.videobridge.ratecontrol.LastNBitrateController.REMB\_AVERAGE\_INTERVAL\_MS=5000
org.jitsi.videobridge.ratecontrol.VideoChannelLastNAdaptor.MAX\_STAY\_AT\_ZERO\_MS=60000
