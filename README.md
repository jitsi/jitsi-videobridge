# Jitsi Media Transform
Jitsi Media Transform contains classes for processing and transforming RTP and RTCP packets

# Code style
We use ktlint for linting and autoformatting. The ktlint command-line utility
can be installed by running:
```
curl -sSLO https://github.com/shyiko/ktlint/releases/download/0.34.2/ktlint && chmod a+x ktlint && sudo mv ktlint /usr/local/bin/
```

Or, on macOS with Homebrew:
```
brew install ktlint
```

To perform the checks simply run `ktlint`.

You can install a pre-commit or pre-push git hook by running this in the git
repository directory:
```
ktlint --install-git-pre-commit-hook
```

You can automatically update Intellij IDEA's formatting rules to to be
compatible with ktlint. However, note that version 0.34.2 of ktlint will
override any Java code style settings.
```
ktlint --apply-to-idea-project
```

Autoformatting can be run by calling `mvn antrun:run@ktlint-format`.
