#!/bin/bash

set -e

mvn clean "$@"
mvn verify "$@"
mvn package "$@"
