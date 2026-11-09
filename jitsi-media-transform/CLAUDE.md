# CLAUDE.md (jitsi-media-transform)

Notes for the `jitsi-media-transform` module. See the root `CLAUDE.md` for the build and the repository layout.

## Audio drops and RTP sequence numbers

- On ingress, `AudioLevelReader` sets `PacketInfo.shouldDiscard` for silence, for force-mute, and for packets that the audio level listener ranks out (`route-loudest-only`). `DiscardableDiscarder` then removes these packets. It closes the gap in the sequence numbers with one `ResumableStreamRewriter` per SSRC, so receivers see no gap.
- To drop audio for all receivers on ingress, set `shouldDiscard`. Do not return `null` from an earlier node, because then nothing closes the gap.
