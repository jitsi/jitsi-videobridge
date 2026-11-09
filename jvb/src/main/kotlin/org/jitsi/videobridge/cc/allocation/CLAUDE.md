# CLAUDE.md (cc/allocation)

Notes for the bandwidth allocation code.

## Stage view and `preferredIndex`

- `BandwidthAllocator.allocate()` goes through the sources in priority order and calls `SingleSourceAllocation.improve()` for each source. It repeats the passes until the remaining bandwidth does not change. On-stage sources are first, because `prioritize()` gets them as the first selected sources.
- After an on-stage source, the loop stops the pass when `hasReachedPreferred()` is false (`targetIdx >= layers.preferredIndex`). The lower-priority sources (thumbnails) then get nothing in that pass. If the on-stage source cannot improve, `remainingBandwidth` does not change and the outer loop ends. Then the thumbnails get nothing in the whole allocation, and the remaining bandwidth stays unspent. The purpose is to not enable thumbnails and then disable them again when the estimate increases a little.
- The break condition is `isOnStage() && !hasReachedPreferred()`. It has no lower limit on bandwidth. If the on-stage source cannot get even its lowest layer, `targetIdx` stays `-1`, and the break still occurs. The receiver then gets no video at all: not the on-stage source, and no thumbnails. Only screenshare on stage can have an oversend layer (see below), so a camera has no oversend layer to avoid this.
- The break has these exceptions:
  - When `preferredIndex` is `-1`, `hasReachedPreferred()` is true (`-1 >= -1`), and the break does not occur even though the source has no layer. This is the case for `Layers.noLayers` (`maxHeight` is 0, the source has no RTP layers, or the video type is not camera or screenshare). It is also the case for a camera whose `ratesList` has no layer at or below `max(preferredHeight, minHeight)`.
  - A disabled on-stage source is skipped with `continue` before the check.
- `preferredIndex` controls only two things:
  1. In `improve()`, an on-stage source with no layer jumps directly to the highest affordable layer up to `preferredIndex`. This is essentially an optimization. Without it, the allocator gets nearly the same result one layer at a time. The two paths differ in one case: the jump accepts a layer with `maxBps >= bitrate`, and the step path needs `bitrate < maxBps`.
  2. The stage-view break above. This is the real meaning of "preferred".
- Camera on stage: when the receiver allows more than 180p, `getPreferred()` gives `onstage-preferred-height-px` (default 360) and `onstage-preferred-framerate` (default 30). When the maximum height is 180 or less, it falls back to `UNLIMITED`.
  - The height limit is `max(preferredHeight, minHeight)`. `preferredIndex` is the last layer at or below this height.
  - The effective frame rate is `min(maxFps of the layers, preferredFps)`. A layer with an unknown frame rate counts as sufficient. A layer at the minimum height is always included. Layers at the preferred height or higher need at least the effective frame rate.
  - `selectLayersForCamera` does not take the on-stage state. It computes the index for every camera, and the index matters only on stage.
  - When the source has layers above 360p, `preferredIndex` is a middle layer, and the break holds back the thumbnails only until the on-stage camera has a reasonable quality.
- Screenshare on stage: `preferredIndex` is the cheapest layer at full resolution. Here "full resolution" means the highest resolution among the layers within the `maxHeight` of the receiver, not the native resolution of the sender. It is the top layer only for high-frame-rate screenshare (`DESKTOP_HIGH_FPS`) and when any layer at the maximum height has an unknown frame rate (`frameRate < 0`, as in the VP9 structure with several encodings at one resolution). For a single-layer screenshare, `preferredIndex` is 0, so `hasReachedPreferred()` is true as soon as the allocator selects a layer.
- Oversend: only screenshare on stage gets an oversend layer, when `allow-oversend-onstage` is `true` (the default). It is the same cheapest full-resolution layer, limited by `max-oversend-bitrate`. Only the first such source can oversend. A camera always has `oversendIndex = -1`.
