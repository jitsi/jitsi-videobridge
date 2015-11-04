# General
In the context of Jitsi-Videobridge and COLIBRI, "bundle" refers to sharing of
a transport by multiple "channel" and/or "sctpconnection" elements. It can be
used with the WebRTC "bundle" -- "m=" lines from the same bundle group go to
COLIBRI channels in the same 'channel-bundle'.

Received packets are demultiplexed between the channels in a channel-bundle
based on the rules defined for WebRTC. Namely for RTP packets the Payload Type is used, while for RTCP packets the Packet Sender SSRC is used.

This list of SSRCs for a given channel, used to decide whether to accept an RTCP packet or not, is populated in two ways:
* Through COLIBRI (with "source" elements)
* When RTP packets with a new SSRC are received

This means that the SSRCs used by receive-only endpoints in RTCP packets need to be signalled to videobridge using COLIBRI. If they are not, RTCP coming from these endpoints will be dropped.

See 
[http://tools.ietf.org/html/draft-ietf-rtcweb-rtp-usage]() and 
[https://tools.ietf.org/html/draft-holmberg-mmusic-sdp-bundle-negotiation]()

# Limitations

## Single 'sctpconnection'
A channel-bundle can contain no more than one "sctpconnection".

## RTP Payload Types
The current implementation requires that
jitsi-videobridge knows all RTP Payload Type numbers which will be used by
channels in a channel-bundle, so that it can perform the demultiplexing. See
[the COLIBRI specification](http://xmpp.org/extensions/xep-0340.html#usecases-update-payload)
on how to set the PTs via COLIBRI.

## rtcp-mux
When bundle is used, jitsi-videobridge assumes the use of rtcp-mux
(multiplexing RTP and RTCP on the same port) and generates candidates for a
single ICE Component.

# COLIBRI
The following examples illustrate how to setup a channel-bundle in COLIBRI.

## Request to create a conference with two channel-bundles (one for each endpoint):
The channel-bundle to which a channel or sctpconnection should belong is
specified by a channel-bundle-id attribute of "channel" or "sctpconnection". Here we use the same ID as the ID of the endpoint.
```
<conference xmlns=" http://jitsi.org/protocol/colibri">
   <content name="audio">
     <channel channel-bundle-id="52865510" initiator="true" expire="15" endpoint="52865510"/>
     <channel channel-bundle-id="4aca6ed6" initiator="true" expire="15" endpoint="4aca6ed6"/>
   </content>
   <content name="video">
     <channel channel-bundle-id="52865510" initiator="true" expire="15" endpoint="52865510"/>
     <channel channel-bundle-id="4aca6ed6" initiator="true" expire="15" endpoint="4aca6ed6"/>
   </content>
   <content name="data">
     <sctpconnection endpoint="52865510" port="5000" initiator="true" expire="15" channel-bundle-id="52865510"/>
     <sctpconnection endpoint="4aca6ed6" port="5000" initiator="true" expire="15" channel-bundle-id="4aca6ed6"/>
   </content>
 </conference>
```

## Response from jitsi-videobridge:
Transport elements are not included for bundled channels, but are instead placed in "channel-bundle" child elements of "conference".

```
<conference xmlns=" http://jitsi.org/protocol/colibri" id="16c43f4c4d3b658">
  <content name="audio">
    <channel endpoint="52865510" expire="15" initiator="true" channel-bundle-id="52865510" id="574e37c6204921e4" rtp-level-relay-type="translator">
      <source xmlns="urn:xmpp:jingle:apps:rtp:ssma:0" ssrc="3021043225"></source>
    </channel>
    <channel endpoint="4aca6ed6" expire="15" initiator="true" channel-bundle-id="4aca6ed6" id="25210df10ca312be" rtp-level-relay-type="translator">
      <source xmlns="urn:xmpp:jingle:apps:rtp:ssma:0" ssrc="3021043225"></source>
    </channel>
  </content>
  <content name="video">
    <channel endpoint="52865510" expire="15" initiator="true" channel-bundle-id="52865510" id="d2e2a4cd1b5000f9" rtp-level-relay-type="translator">
      <source xmlns="urn:xmpp:jingle:apps:rtp:ssma:0" ssrc="2805196134"></source>
    </channel>
    <channel endpoint="4aca6ed6" expire="15" initiator="true" channel-bundle-id="4aca6ed6" id="40eb4e4dc1e3dded" rtp-level-relay-type="translator">
      <source xmlns="urn:xmpp:jingle:apps:rtp:ssma:0" ssrc="2805196134"></source>
    </channel>
  </content>
  <content name="data">
    <sctpconnection endpoint="52865510" expire="15" initiator="true" channel-bundle-id="52865510" port="5000"/>
    <sctpconnection endpoint="4aca6ed6" expire="15" initiator="true" channel-bundle-id="4aca6ed6" port="5000"/>
  </content>
  <channel-bundle id="4aca6ed6">
    <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1" pwd="74q04gln6f8lr6ir4c0b0m492l" ufrag="7b2ev">
      <fingerprint xmlns="urn:xmpp:jingle:apps:dtls:0" hash="sha-1">88:16:51:C8:AA:D2:14:BD:6E:BB:C7:AC:5E:4B:2F:30:1D:8A:EB:0B</fingerprint>
      <candidate component="1" foundation="2" generation="0" id="16c43f4c4d3b65820739903542a49a02d309ce9" network="0" priority="2130706431" protocol="udp" type="host" ip="2001:824a::13" port="10002"/>
      <candidate component="1" foundation="3" generation="0" id="16c43f4c4d3b65820739903542a49a0528d9c2c" network="0" priority="2113932031" protocol="udp" type="host" ip="192.168.40.82" port="10002"/>
    </transport>
  </channel-bundle>
  <channel-bundle id="52865510">
    <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1" pwd="6vpv0e1a15kmaetfum63lc3hdo" ufrag="8jv0a">
      <fingerprint xmlns="urn:xmpp:jingle:apps:dtls:0" hash="sha-1">6B:0C:D6:A8:F5:E5:08:DF:AE:DF:6E:C5:4F:C7:35:16:67:28:05:06</fingerprint>
      <candidate component="1" foundation="2" generation="0" id="16c43f4c4d3b65837869e1366f40ddf04cda661a" network="0" priority="2130706431" protocol="udp" type="host" ip="2001:824a::13" port="10000"/>
      <candidate component="1" foundation="3" generation="0" id="16c43f4c4d3b65837869e1366f40ddf016655c61" network="0" priority="2113932031" protocol="udp" type="host" ip="192.168.40.82" port="10000"/>
    </transport>
  </channel-bundle>
</conference>
```

## Adding remote candidates for a single channel-bundle:
Transport elements carrying the candidates are placed in "channel-bundle" child elements of "conference".
```
<conference xmlns=" http://jitsi.org/protocol/colibri" id="16c43f4c4d3b658">
  <content name="audio">
    <channel channel-bundle-id="52865510" id="574e37c6204921e4" expire="15" endpoint="52865510"></channel>
  </content>
  <content name="video">
    <channel channel-bundle-id="52865510" id="d2e2a4cd1b5000f9" expire="15" endpoint="52865510"></channel>
  </content>
  <content name="data">
    <sctpconnection channel-bundle-id="52865510" endpoint="52865510" port="5000" expire="15"></sctpconnection>
  </content>
  <channel-bundle id="52865510">
    <transport xmlns="urn:xmpp:jingle:transports:ice-udp:1">
      <rtcp-mux/>
      <candidate type="host" protocol="udp" id="id97de092a" ip="10.0.0.250" component="1" port="49312" foundation="3338013213" generation="0" priority="2122260223" network="1"/>
    </transport>
  </channel-bundle>
</conference>
```
