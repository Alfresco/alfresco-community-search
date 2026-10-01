#!/usr/bin/env bash

set -x

VERSION=$(mvn -q -Dexec.executable=echo -Dexec.args='${project.version}' --non-recursive exec:exec)
for DOCKER_BUILD_DIR in $(find . -name Dockerfile -exec dirname {} \;)
do
  if [[ "$DOCKER_BUILD_DIR" != *"/classes/"* ]]
  then
    continue
  fi

  cd $DOCKER_BUILD_DIR
  echo "Build docker image in:: $DOCKER_BUILD_DIR"
  source ./build.properties

  # Build the image locally.
  docker build --label "GIT_COMMIT=$COMMIT_MESSAGE" --label "GIT_BRANCH=$GITHUB_REF_NAME" --tag "$DOCKER_IMAGE_REPOSITORY:$VERSION" .

  cd -
done
