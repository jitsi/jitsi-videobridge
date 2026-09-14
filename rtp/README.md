# Jitsi RTP
Jitsi RTP contains classes for parsing and creating RTP and RTCP packets.

# Code style
We use [ktlint](https://pinterest.github.io/ktlint/) for linting and autoformatting. It runs as part of
`mvn verify` (the build fails on style violations), and the version used is pinned by `ktlint.version` in the
root `pom.xml`, so no separate installation is needed.

To run only the check, or to autoformat, run these in this module's directory:
```
mvn exec:exec@ktlint-check
mvn exec:exec@ktlint-format
```

If you also want the `ktlint` command-line tool (for editor integration or a git hook), install a version matching
`ktlint.version`, e.g. on macOS with Homebrew:
```
brew install ktlint
```
It can then be run directly with `ktlint` (or `ktlint -F` to autoformat), install a pre-commit hook with
`ktlint installGitPreCommitHook`, and configure IntelliJ IDEA with `ktlint applyToIDEAProject`.
