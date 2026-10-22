#!/usr/bin/env bash

set -o errexit

usage() {
    echo "Create docker images for local use." 1>&2;
    echo "Requires a prior build with the docker-image profile, e.g.:" 1>&2;
    echo "  mvn install -DskipTests -P docker-image" 1>&2;
    echo "Usage: $0 [-t <tag>] [-p] [-h]" 1>&2;
    echo "  -t <tag>: Specify the tag to create (default 'local')" 1>&2;
    echo "  -p: Use the Maven project version as the tag (what the *DockerComposeIT tests expect)" 1>&2;
    echo "  -h: Display this help" 1>&2;
    exit 1;
}

DOCKER_IMAGE_TAG=local
while getopts ":ht:p" arg; do
  case $arg in
    t)
      DOCKER_IMAGE_TAG=${OPTARG}
      ;;
    p)
      USE_PROJECT_VERSION=true
      ;;
    h | *) # Display help.
      usage
      ;;
  esac
done

# Always run from the repository root, wherever the script is called from.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [[ "${USE_PROJECT_VERSION}" == "true" ]]; then
  DOCKER_IMAGE_TAG=$(mvn -q -Dexec.executable=echo -Dexec.args='${project.version}' --non-recursive exec:exec)
fi

FOUND=false
for DOCKER_BUILD_DIR in $(find . -name Dockerfile -exec dirname {} \;); do
  if [[ "$DOCKER_BUILD_DIR" != *"/classes/"* ]]; then
    continue
  fi
  FOUND=true
  pushd "$DOCKER_BUILD_DIR" > /dev/null
  echo "Build docker image in:: $DOCKER_BUILD_DIR"
  source ./build.properties
  docker build ${DOCKER_BUILD_ARGS} -t "${DOCKER_IMAGE_REPOSITORY}:${DOCKER_IMAGE_TAG}" .
  echo "---- done: ${DOCKER_IMAGE_REPOSITORY}:${DOCKER_IMAGE_TAG} ----"
  popd > /dev/null
done

if [[ "${FOUND}" != "true" ]]; then
  echo "No Dockerfile found under target/classes. Build first with: mvn install -DskipTests -P docker-image" 1>&2
  exit 1
fi
