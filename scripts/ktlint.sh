# Install the ktlint command-line utility
curl -sSLO https://github.com/shyiko/ktlint/releases/download/0.31.0/ktlint && chmod a+x ktlint && sudo mv ktlint /usr/local/bin/

# Integrate ktlint with Intellij.  This makes Intellij's built-in formatter produce 100% ktlint-compatible ode
ktlint --apply-to-idea-project

# Install a pre-push hook to check for violations
ktlint --install-git-pre-push-hook
