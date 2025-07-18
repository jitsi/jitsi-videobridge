#!/usr/bin/env bash
set -e
cd "$(dirname "$0")/jvb"
mvn clean package -P buildFatJar 