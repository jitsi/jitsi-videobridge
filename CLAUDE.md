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

# Run a specific test class from the root. -Dtest also applies to the modules that do not contain the
# test, and these modules fail with 'No tests matching pattern', so add the flag.
mvn test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false

# Run a specific test method
mvn test -Dtest=ClassName#methodName -Dsurefire.failIfNoSpecifiedTests=false

# Run a test in one module. -am also builds rtp, which does not contain the test, so keep the flag.
mvn -pl jitsi-media-transform -am test -Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false

# Build package (creates archive in jvb/target/)
mvn install
# Output: jvb/target/jitsi-videobridge-2.3-SNAPSHOT-archive.zip
```

### Code Quality Commands

```bash
# Run checkstyle (Java formatting)
mvn checkstyle:check

# Run ktlint (Kotlin formatting, must be run inside a submodule directory, e.g. ./jvb/; sibling modules must be
# resolvable, so run "mvn install -DskipTests" from the root first if they are not)
mvn exec:exec@ktlint-check

# Auto-format Kotlin code (must be run inside a submodule directory, e.g. ./jvb/)
mvn exec:exec@ktlint-format
```

### Building against an unreleased dependency

To build against a local, unreleased version of a dependency, run `mvn install -DskipTests` in the checkout of that dependency. Then point this repository at its SNAPSHOT version. `jicoco.version` and `jitsi.utils.version` are properties in the root `pom.xml`. Override them on the command line, for example `mvn -Djicoco.version=1.1-SNAPSHOT install`, and the pom stays unchanged. Repeat the `-D` option on every `mvn` call, including the ktlint calls. The other pins have literal versions, so they need a pom edit:

| Dependency | Where it is pinned | SNAPSHOT version |
|---|---|---|
| `jicoco-*` | `<jicoco.version>` in the root `pom.xml` | `1.1-SNAPSHOT` |
| `jitsi-utils` | `<jitsi.utils.version>` in the root `pom.xml` | `1.0-SNAPSHOT` |
| `jitsi-xmpp-extensions` | `<version>` of the dependency in the root `pom.xml` | `1.0-SNAPSHOT` |
| `ice4j` | `<version>` of the dependency in the root `pom.xml` | `3.2-SNAPSHOT` |
| `jitsi-metaconfig` | `<version>` of the dependency in the root `pom.xml` | `1.0-SNAPSHOT` |
| `jitsi-srtp` | `<version>` of the dependency in `jitsi-media-transform/pom.xml` | `1.1-SNAPSHOT` |
| `jitsi-dcsctp` | `<version>` of the dependency in `jvb/pom.xml` | the version in the `pom.xml` of that repository |

Do not commit a SNAPSHOT pin. After the dependency PR merges and a new version is tagged, set the pin to the released version.

The `jicoco.version` pin is a `git describe` version that the release job of jicoco creates. A local build does not create it. `jicoco-mucclient` is published to the Jitsi Maven repository, separately from the other `jicoco` modules, which go to Maven Central. A `jicoco.version` bump resolves only when the version is available in both places.

## Project Structure

This is a multi-module Maven project with three main modules. All three are in this repository. They are not git submodules, so put a change that spans the modules in one PR. `jicoco`, `jitsi-utils` and `jitsi-xmpp-extensions` are external dependencies, each in its own repository. A change to one of them needs a separate PR in that repository (see "Building against an unreleased dependency").

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
- ktlint runs from Maven via exec-maven-plugin (version pinned by `ktlint.version` in the root pom); `mvn verify` runs the check

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
- **Jackson**: The root `pom.xml` pins the Jackson version with a `jackson-bom` import (see "Jackson version" below).

### Jackson version

The `jackson-bom` import in `dependencyManagement` of the root `pom.xml` pins all Jackson artifacts to `jackson.version`. Do not remove it. Without it, Maven mediation among the transitive dependencies selects the `jackson-databind` version. `jackson-databind` 2.18.x can drop `@JsonAnySetter` properties of types with a creator constructor, when the any-setter field comes before the creator property in the received JSON. This removes the payload of a forwarded `EndpointMessage`. If JSON serialization looks wrong, first make sure that `mvn dependency:tree | grep jackson-databind` shows only one version. The first run of the dependency plugin needs network access.

## Testing & CI

Tests are organized by module. When implementing features, consider whether tests are needed and discuss with the user if implementation is non-trivial.

Before you push a code change, run `mvn clean verify` from the root after the final change. It runs the tests, checkstyle (`jvb`, `jitsi-media-transform`) and ktlint. CI runs `resources/build.sh` (`mvn clean verify package`) with `-Pcoverage`, on Java 17, 21 and 25. A build without `clean` can hide a stale-class failure.

In `rtp` and `jitsi-media-transform`, the `verify` phase also runs the spotbugs check. `jvb` has no spotbugs execution. Each module has its own `spotbugs-exclude.xml`, which the module pom sets through `<excludeFilterFile>`. A Kotlin anonymous subclass of a generic collection (for example an anonymous `LRUCache` that overrides `removeEldestEntry`) causes a `UMAC_UNCALLABLE_METHOD_OF_ANONYMOUS_CLASS` false positive on the synthetic bridge methods (`get`, `getOrDefault`, `remove`). `jitsi-media-transform/spotbugs-exclude.xml` already excludes this pattern in its Kotlin `<Match>` block (the block with `<Source name="~.*\.kt" />`). `rtp/spotbugs-exclude.xml` has no Kotlin-scoped block, so `rtp` needs a new one for the same case.

`cd jvb && mvn test` (and `-pl jvb` without `-am`) takes `rtp` and `jitsi-media-transform` from `~/.m2`, and these can be days old. Build with `-am`, or run `mvn -pl rtp,jitsi-media-transform -am install -DskipTests` first.

Kotest specs have no Surefire test count. In `TEST-<Spec>.xml`, `tests` is `0`, and there is one `<testcase name="">` for each `should` leaf that ran. To check that every leaf ran, compare `grep -c '<testcase' TEST-<Spec>.xml` with `grep -c 'should("' <Spec>.kt`. Use the Maven exit status to decide pass or fail.

Run module-specific tests:
```bash
cd jvb && mvn test
cd jitsi-media-transform && mvn test
cd rtp && mvn test
```
