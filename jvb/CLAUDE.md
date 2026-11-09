# CLAUDE.md (jvb)

Notes for the `jvb` module. See the root `CLAUDE.md` for the build and the repository layout.

## Tests

- An endpoint from `conference.createLocalEndpoint(...)` has no connected transport, so `Endpoint.wants()` rejects every packet. Also, `acceptAudio` is `false` by default. To test the logic after the transport check (for example audio subscriptions), make a spy and stub the private check. No existing test uses this method:
  1. Set `acceptAudio = true` on the spy, or on the endpoint before you make the spy. The spy copies the field values when it is made, so a later write to the endpoint does not reach the spy.
  2. Make a spy: `spyk(ep, recordPrivateCalls = true)`.
  3. Stub the private check: `every { spy["isTransportConnected"]() } returns true`.
  4. Call `spy.wants(packetInfo)` directly. The conference does not know the spy, so `Conference.sendOut()` still calls `wants()` on the original endpoint.
- To send a packet through the send pre-processor of an endpoint, call `ep.transceiver.rtpSender.preProcesor!!(packetInfo)`. The property name has this spelling. This path does not call `wants()`. `EndpointTest` has an example, and it does not use the spy.

## SSRC checks on the packet path

See also the section on SSRC checks in `jitsi-media-transform/CLAUDE.md`.

- Audio to endpoints: `Endpoint.wants()` calls `Conference.isEndpointAudioWanted()`. `AudioSubscriptionManager` routes audio only from an SSRC of a currently known source, and a synthetic source only to an endpoint that subscribed to it explicitly. `AudioRedHandler` is in the send pipeline (`RtpSenderImpl`), so it sees only audio that passed this check.
- Relay ingress: `Relay.handleMediaPacket()` finds the `RelayedEndpoint` with `getEndpointBySsrc()`. It drops RTP with an unknown SSRC and logs "RTP Packet received for unknown endpoint SSRC". RTCP from an unknown sender SSRC is not dropped. It goes to the generic transceiver of the relay. Each `RelayedEndpoint` has its own `RtpReceiverImpl`, so the receive state is kept separately for each remote endpoint.
