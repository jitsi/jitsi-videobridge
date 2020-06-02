#!/bin/bash

set -e

mvn clean verify package

mvn clean verify package -f jvb-api/pom.xml
