#!/bin/bash

set -e

./resources/build.sh
mvn install
