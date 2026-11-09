# CLAUDE.md (jitsi-media-transform)

Notes for the `jitsi-media-transform` module. See the root `CLAUDE.md` for the build and the repository layout.

## Audio drops and RTP sequence numbers

- On ingress, a node sets `PacketInfo.shouldDiscard` to mark a packet that must not be forwarded. `DiscardableDiscarder` then removes these packets. It closes the gap in the sequence numbers with one `ResumableStreamRewriter` for each SSRC, so receivers see no gap.
- On the audio path, `AudioLevelReader` sets `shouldDiscard` in three cases:
  - Force-mute.
  - Silence. This drop applies only when `jmt.audio.level.discard-silence` is `true`, and only after more than `jmt.audio.level.forwarded-silence-packets-limit` silent packets in a row were forwarded.
  - A packet that the audio level listener ranks out. The ranking is in `jvb`: `Conference.levelChanged()` decides it when `videobridge.loudest.route-loudest-only` is set.
- `PaddingTermination` also marks padding-only packets with `shouldDiscard`, before the media type demux.
- To drop audio for all receivers on ingress, set `shouldDiscard`. Do not return `null` from an earlier node, because then nothing closes the gap.
- `SrtpTransformer` does not authenticate or decrypt a packet that already has `shouldDiscard` set. A node that marks a packet before the SRTP decrypt step must not assume that the payload is decrypted after it. `RtxHandler` has a special case for this.

## SSRC checks on the packet path

See also the section on SSRC checks in `jvb/CLAUDE.md`.

- Keep per-SSRC state in the receive pipeline bounded, for example with an `LRUCache`. `DiscardableDiscarder` and `SrtpTransformer` use a plain `LRUCache`. `RetransmissionRequester` and `AudioRedHandler` use an anonymous subclass (see the spotbugs note in the root `CLAUDE.md`).
- Video: `VideoQualityLayerLookup` drops a video packet when `findRtpEncodingId` finds no encoding in the signaled sources of the sending endpoint. The bridge accepts the video of an endpoint on ingress only on the signaled SSRCs of that endpoint. At this point `RtxHandler` has already mapped the RTX SSRCs to the primary SSRCs, so the signaled secondary SSRCs also count.
