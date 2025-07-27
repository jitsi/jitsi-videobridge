#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/jvb"
JAR="target/jitsi-videobridge-2.3-SNAPSHOT-jar-with-dependencies.jar"
if [ ! -f "$JAR" ]; then
  echo "Fat JAR not found. Please run ./build.sh first."
  exit 1
fi
exec java -jar "$JAR" "$@" 