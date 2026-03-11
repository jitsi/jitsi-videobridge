# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Jitsi Videobridge (JVB) is a WebRTC-compatible Selective Forwarding Unit (SFU) that routes media streams between participants in video conferences. It is a backend component of the Jitsi Meet stack.

## Build System & Commands

This project uses Maven with Java 11+ and Kotlin. Requires Maven 3.6.3+.

### Build Commands

```bash
# Build the entire project
mvn install

# Build without running tests
mvn install -DskipTests

# Run all tests
mvn test

# Run tests in a specific module
cd jvb && mvn test

# Run a specific test class
mvn test -Dtest=ClassName

# Run a specific test method
mvn test -Dtest=ClassName#methodName

# Build package (creates archive in jvb/target/)
mvn install
# Output: jvb/target/jitsi-videobridge-2.3-SNAPSHOT-archive.zip
```

### Code Quality Commands

```bash
# Run checkstyle (Java formatting)
mvn checkstyle:check

# Run ktlint (Kotlin formatting, must be run inside a submodule directory, e.g. ./jvb/)
mvn ktlint:check

# Auto-format Kotlin code (must be run inside a submodule directory, e.g. ./jvb/)
mvn ktlint:format
```

## Project Structure

This is a multi-module Maven project with three main modules:

### 1. `rtp/` - RTP Protocol Layer
Low-level RTP/RTCP packet handling and parsing.

### 2. `jitsi-media-transform/` - Media Processing
Core media transformation logic including:
- Codec-specific processing (VP8, VP9, AV1, H.264)
- Simulcast and SVC (Scalable Video Coding) handling
- Bandwidth allocation and quality adaptation
- SRTP encryption/decryption

### 3. `jvb/` - Main Videobridge Application
The main application that ties everything together:

**Key Java Classes:**
- `Videobridge.java` - Main service managing conferences
- `Conference.java` - Represents a single video conference, manages endpoints and media routing
- `Endpoint` (Kotlin) - Represents a participant in a conference
- `EncodingsManager.java` - Manages simulcast encodings for endpoints

**Key Kotlin Packages:**
- `cc/allocation/` - Bandwidth allocation and bitrate control
- `cc/vp8/`, `cc/vp9/`, `cc/av1/` - Codec-specific frame processing
- `load_management/` - CPU and packet rate based load management
- `relay/` - Multi-region support (Octo relay)
- `websocket/` - WebSocket transport for signaling
- `xmpp/` - XMPP/Colibri signaling

**Main Entry Point:** `org.jitsi.videobridge.Main.kt`

## Architecture Principles

### Media Forwarding Pipeline
1. **Ingress**: Media arrives at endpoints via ICE/DTLS/SRTP
2. **Processing**: Packets flow through codec-specific handlers that understand simulcast/SVC structure
3. **Bandwidth Allocation**: `BitrateController` determines which quality layers to forward to each receiver
4. **Rewriting**: RTP headers (sequence numbers, timestamps, picture IDs) are rewritten to make stream switches transparent
5. **Egress**: Forwarded packets are sent to receiving endpoints

### Simulcast & SVC Handling
The bridge supports temporal and spatial scalability in VP8, VP9, and AV1. It performs transparent stream switching by rewriting RTP sequence numbers, timestamps, and codec-specific picture IDs. See `doc/svc.md` for detailed rewriting principles.

**Key Constraint**: Switches must be completely transparent to receivers - no gaps in sequence numbers, continuous timestamps, and continuous picture IDs.

### Configuration System
Uses Typesafe Config (HOCON format):
- `reference.conf` contains all default values with documentation
- User config in `/etc/jitsi/videobridge/jvb.conf` or `~/.jvb/jvb.conf` overrides defaults
- Config is accessed via `jitsi-metaconfig` library with type-safe property delegates

## Code Style & Conventions

### Java Code
- **Brace Style**: Opening braces on new line for methods, classes, control structures
- **Line Length**: Maximum 120 characters
- **Indentation**: 4 spaces, NO tabs
- Validated by Checkstyle (see `jvb/checkstyle.xml`)

### Kotlin Code
- Follows ktlint conventions (standard Kotlin style)
- **Line Length**: Maximum 120 characters
- Use ktlint Maven plugin for linting and formatting

### Testing
- Uses JUnit 5 for new tests (JUnit 4 vintage engine for legacy tests)
- Kotlin tests use Kotest framework
- Mocking with MockK (Kotlin) or Mockito (Java)

## Important Implementation Notes

### Thread Safety
- `Conference.endpointsById` is a `ConcurrentHashMap` but writes must be synchronized on the map itself to keep it in sync with `endpointsCache`
- Most core components use concurrent data structures but may require explicit synchronization for compound operations

### Performance Considerations
- `endpointsCache` is a read-only cache updated on writes - used in hot path (per-packet processing)
- Buffer pools (`ByteBufferPool`) are used throughout to reduce GC pressure
- Packet queues are bounded to prevent memory exhaustion under load

### Logging
- Uses `org.jitsi.utils.logging2.Logger` (custom logging framework)
- Can optionally redact remote IP addresses (controlled by `videobridge.redact-remote-addresses`)

### Dependencies
- **ice4j**: ICE/STUN/TURN implementation
- **jicoco**: Common Jitsi infrastructure (config, metrics, REST)
- **Jetty**: HTTP server for REST APIs and WebSockets
- **Smack**: XMPP client library

## Testing & CI

Tests are organized by module. When implementing features, consider whether tests are needed and discuss with the user if implementation is non-trivial.

Run module-specific tests:
```bash
cd jvb && mvn test
cd jitsi-media-transform && mvn test
cd rtp && mvn test
```
