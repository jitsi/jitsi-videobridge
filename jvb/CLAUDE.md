# CLAUDE.md (jvb)

Notes for the `jvb` module. See the root `CLAUDE.md` for the build and the repository layout.

## Tests

- An endpoint from `conference.createLocalEndpoint(...)` has no connected transport, so `Endpoint.wants()` rejects every packet. Also, `acceptAudio` is `false` by default. To test the logic after the transport check (for example audio subscriptions):
  1. Set `acceptAudio = true`. Do this before you make the spy, because the spy copies the fields.
  2. Make a spy: `spyk(ep, recordPrivateCalls = true)`.
  3. Stub the private check: `every { spy["isTransportConnected"]() } returns true`.
- To send a packet through the send pre-processor of an endpoint, call `ep.transceiver.rtpSender.preProcesor!!(packetInfo)`. The property name has this spelling. `EndpointTest` shows an example.

## SSRC checks on the packet path

- Keep per-SSRC state in the receive pipeline bounded, for example with an `LRUCache` (see `DiscardableDiscarder`).
- Video: `VideoQualityLayerLookup` (in `jitsi-media-transform`) drops a video packet when `findRtpEncodingId` finds no encoding in the signaled sources of the sending endpoint. Thus each endpoint forwards video only on its own signaled SSRCs.
- Audio to endpoints: `Endpoint.wants()` calls `Conference.isEndpointAudioWanted()`. `AudioSubscriptionManager` routes audio only from an SSRC of a currently known source, and a synthetic source only to an endpoint that subscribed to it explicitly. `AudioRedHandler` is in the send pipeline (`RtpSenderImpl`), so it sees only audio that passed this check.
- Relay ingress: `Relay.handleMediaPacket()` finds the `RelayedEndpoint` with `getEndpointBySsrc()`. It drops RTP with an unknown SSRC and logs "RTP Packet received for unknown endpoint SSRC". Each `RelayedEndpoint` has its own `RtpReceiverImpl`, so the receive state is kept separately for each remote endpoint.
