#!/bin/bash

set -e

cd $WORKSPACE
mvn clean verify package
mvn antrun:run@ktlint
