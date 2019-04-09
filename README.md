# Jitsi RTP
Jitsi RTP contains classes for parsing and creating RTP and RTCP packets.

# Code style
We use ktlint for linting and autoformatting.  The ktlint command-line utility can be installed by
running `scripts/ktlint.sh`.  This will also change Intellij's autoformatting to be compatible with
ktlint.  Autoformatting can be run by calling `mvn antrun:run@ktlint-format`
