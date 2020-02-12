#!/bin/bash
set -x
set -e

# Make sure you have the following environment variables set, example:
# export DEBFULLNAME="Jitsi Team"
# export DEBEMAIL="dev@jitsi.org"
# You need package devscripts installed (command dch).

echo "==================================================================="
echo "   Building DEB packages...   "
echo "==================================================================="

SCRIPT_FOLDER=$(dirname "$0")
cd "$SCRIPT_FOLDER/.."

# Let's get version from maven
MVNVER=$(xmllint --xpath "/*[local-name()='project']/*[local-name()='version']/text()" pom.xml)
TAG_NAME="v${MVNVER/-SNAPSHOT/}"

echo "Current tag name: $TAG_NAME"

if ! git rev-parse "$TAG_NAME" >/dev/null 2>&1
then
  git tag -a "$TAG_NAME" -m "Tagged automatically by Jenkins"
  git push origin "$TAG_NAME"
else
  echo "Tag: $TAG_NAME already exists."
fi

VERSION_FULL=$(git describe --match "v[0-9\.]*" --long)
echo "Full version: ${VERSION_FULL}"

VERSION=${VERSION_FULL:1}
echo "Package version: ${VERSION}"

REV=$(git log --pretty=format:'%h' -n 1)
dch -v "$VERSION-1" "Build from git. $REV"
dch -D unstable -r ""

# We need to make sure all dependencies are downloaded before start building
# the debian package
mvn dependency:resolve

# sets the version in the pom file so it will propagte to resulting jar
mvn versions:set -DnewVersion="${VERSION}"

# now build the deb
dpkg-buildpackage -tc -us -uc -A

# clean the current changes as dch had changed the change log
git checkout debian/changelog
git checkout pom.xml

echo "Here are the resulting files in $(pwd ..)"
echo "-----"
ls -l ../{*.changes,*.deb,*.buildinfo}
echo "-----"

# Let's try deploying
cd ..
([ ! -x deploy.sh ] || ./deploy.sh)
