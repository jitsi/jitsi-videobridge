# JVB Audio Last-N Feature

## Overview

The `jvb-audio-last-n` feature allows controlling the maximum number of audio streams forwarded to clients in Jitsi Videobridge. This is similar to the existing `jvb-last-n` feature for video streams, but specifically for audio.

## Configuration

The feature is configured in `jitsi-videobridge/jvb/src/main/resources/reference.conf`:

```hocon
videobridge {
  cc {
    # A JVB-wide audio last-n value, observed by all endpoints.  Endpoints
    # will only receive the loudest N audio streams (-1 implies
    # no audio last-n limit)
    jvb-audio-last-n = -1
  }
}
```

## How It Works

1. **Audio Level Tracking**: The system tracks audio levels from all endpoints using the existing speech activity detection.

2. **Loudest Speaker Selection**: When `jvb-audio-last-n` is set to a positive value (e.g., 3), only the loudest N speakers will have their audio forwarded to other participants.

3. **Integration with Existing Features**: The feature integrates with the existing loudest audio routing system (`videobridge.loudest.route-loudest-only`) but provides a separate limit specifically for audio streams.

## Implementation Details

### Key Files Modified

1. **`JvbAudioLastN.kt`** - New class that manages the audio last-n configuration
2. **`reference.conf`** - Added configuration option
3. **`Conference.java`** - Modified `levelChanged()` method to check audio last-n
4. **`Endpoint.kt`** - Modified `wants()` method for audio packets
5. **`Relay.kt`** - Modified `wants()` method for relayed audio packets

### Logic Flow

1. When an audio packet arrives, the system checks if `jvb-audio-last-n` is enabled (not -1)
2. If enabled, it checks if the source endpoint is among the loudest speakers
3. If the source is not among the loudest N speakers, the audio packet is dropped
4. If the source is among the loudest N speakers, the audio packet is forwarded

## Usage Examples

### Example 1: Limit to 3 Loudest Speakers
```hocon
jvb-audio-last-n = 3
```
This will forward audio from only the 3 loudest speakers to all participants.

### Example 2: No Limit (Default)
```hocon
jvb-audio-last-n = -1
```
This forwards audio from all speakers (default behavior).

### Example 3: Single Speaker Only
```hocon
jvb-audio-last-n = 1
```
This forwards audio from only the loudest speaker.

## Benefits

1. **Bandwidth Reduction**: Reduces bandwidth usage by limiting the number of audio streams
2. **Improved Performance**: Reduces processing load on endpoints
3. **Better User Experience**: Focuses on the most important audio sources
4. **Configurable**: Can be adjusted based on conference size and requirements

## Compatibility

- Works with existing loudest audio routing features
- Compatible with relay endpoints
- Maintains backward compatibility when set to -1 (default)

## Testing

Run the test suite to verify the implementation:

```bash
cd jitsi-videobridge
./gradlew test --tests JvbAudioLastNTest
```

## Notes

- The feature uses the existing speech activity detection system
- Audio level ranking is based on the same algorithm used for dominant speaker detection
- The feature is applied per-endpoint, so each endpoint only receives audio from the loudest N speakers
- When set to -1, the feature is disabled and all audio is forwarded (default behavior) 