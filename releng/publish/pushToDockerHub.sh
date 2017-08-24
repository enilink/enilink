#!/bin/bash
SCRIPT_PATH=`dirname $0`

TAG=$(git describe --tags --exact-match 2>/dev/null)
TAG=${TAG#v}

VERSION_FILE="$SCRIPT_PATH/../../features/net.enilink.platform.updatesite/target-maven/repository/version.txt"
DOCKER_BUILD_CONTEXT="$SCRIPT_PATH/../../features/net.enilink.platform.product"

if [ ! -z "$TAG" ]; then
# use current tag as version
  VERSION=$TAG
elif [ -e "$VERSION_FILE" ]; then
# read version from file
  VERSION=$(<"$VERSION_FILE")
fi

REPO=enilink/enilink

docker build "$DOCKER_BUILD_CONTEXT" -t "$REPO:$VERSION"
docker tag "$REPO:$VERSION" "$REPO:latest"

# login to Docker registry if user and password are given as environment variables
if [ -n "$DOCKER_USER" ] && [ -n "$DOCKER_PASS" ]; then
  docker login -u "$DOCKER_USER" -p "$DOCKER_PASS"
fi

docker push "$REPO"
