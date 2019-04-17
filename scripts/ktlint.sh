# Install the ktlint command-line utility
curl -sSLO https://github.com/shyiko/ktlint/releases/download/0.31.0/ktlint && chmod a+x ktlint && sudo mv ktlint /usr/local/bin/

# Install a pre-push hook to check for violations
ktlint --install-git-pre-commit-hook
