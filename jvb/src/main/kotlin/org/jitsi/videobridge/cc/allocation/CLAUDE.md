# CLAUDE.md (cc/allocation)

Notes for the bandwidth allocation code.

## Stage view and `preferredIndex`

- `BandwidthAllocator.allocate()` goes through the sources in priority order and calls `SingleSourceAllocation.improve()` for each source. It repeats the passes until the remaining bandwidth does not change. On-stage sources are first, because `prioritize()` gets them as the first selected sources.
- After an on-stage source, the loop stops the pass when `hasReachedPreferred()` is false (`targetIdx >= layers.preferredIndex`). The lower-priority sources (thumbnails) then get nothing in that pass, and the remaining bandwidth stays unspent. The purpose is to not enable thumbnails and then disable them again when the estimate increases a little.
- The break condition is `isOnStage() && !hasReachedPreferred()`, with no lower limit. If the on-stage source cannot get even its lowest layer (no layer and no oversend layer fits, so `targetIdx` stays `-1`), the break still occurs. The receiver then gets no video at all: not the on-stage source, and no thumbnails.
- `preferredIndex` controls only two things:
  1. In `improve()`, an on-stage source with no layer jumps directly to the highest affordable layer up to `preferredIndex`. This is an optimization only. Without it, the allocator gets the same result one layer at a time.
  2. The stage-view break above. This is the real meaning of "preferred".
- Camera on stage: when the receiver allows more than 180p, `getPreferred()` gives `onstage-preferred-height-px` (default 360) and `onstage-preferred-framerate` (default 30). `preferredIndex` is the last layer at or below that height, and layers at that height or higher need at least that frame rate. This is a middle layer, so the break holds back the thumbnails only until the on-stage camera has a reasonable quality.
- Screenshare on stage: `preferredIndex` is the cheapest layer at full resolution. It is the top layer only for high-frame-rate screenshare (`DESKTOP_HIGH_FPS`) and for the VP9 structure with an unknown frame rate. For a single-layer screenshare, `preferredIndex` is 0, so `hasReachedPreferred()` is true as soon as the allocator selects a layer.
