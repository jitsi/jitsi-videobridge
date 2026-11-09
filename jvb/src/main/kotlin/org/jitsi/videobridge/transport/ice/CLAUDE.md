# CLAUDE.md (transport/ice)

Notes for the ICE transport code.

## ice4j behavior that affects the bridge

- When the ice4j `Agent` changes to `TERMINATED`, `Agent.terminate()` stops the connectivity check client. After that, the agent ignores checks on candidate pairs that it has not seen before. This includes a new peer-reflexive address. In this case `Agent.triggerCheck()` returns `false` and does not queue the pair. ice4j logs the warning "Received check for unknown candidate pair ... when client is stopped."
- Thus, when a client starts to send from a new local address after ICE at the bridge has ended (for example a cellular address after wifi fails), the bridge does not answer. It answers only pairs that it knew before termination.
- After termination, the bridge normally answers only the selected pair. With `videobridge.ice.keep-alive-strategy = "all_succeeded"` (the default is `"selected_only"`), a pair that reached `SUCCEEDED` before termination keeps getting answers, with no time limit. The client (libwebrtc) can then switch to that pair without signaling.
- For a make-before-break failover design, the backup pair must pass its connectivity check during the initial ICE exchange, before the `Agent` terminates (a few seconds). A client interface with no checked candidate in this window can be used later only after a full ICE restart. A full ICE restart creates a new `Agent`, with a new ufrag and password.
- An established `Agent` (`COMPLETED` or `TERMINATED`) never changes to `FAILED`. ice4j sets `FAILED` only during establishment: when it cannot build the check lists, or when all check lists end without success.
- Lost consent fails only single candidate pairs. `IceTransport.hasFailed()` and the `EventHandler.failed()` callback report only an ICE connection that never came up. A peer that went away after the connection is visible only as missing consent. To expire such a transport, use the activity timestamp, not ICE failure.
- ice4j refreshes consent only when it gets the response to its own consent check. A check that the peer sends does not refresh it. The `consentUpdated` callback sets `packetIOActivity.lastIceActivityInstant`, which is part of `lastIncomingActivityInstant`. `Endpoint.shouldExpire()` and `Relay.shouldExpire()` use that value.
