#!/usr/bin/env bash
set -e

# Always run from the script's directory
cd "$(dirname "$0")"

# Build all modules in the correct order using the parent pom.xml
mvn clean install -U

# Optionally, build the fat jar for jvb
cd jvb
mvn package -P buildFatJar -U
cd ..

echo "Build complete. Artifacts are in their respective target/ folders." 