#!/bin/bash

set -e

docker build -t jenkins-lts .
export GITHUB_SHA=$(git rev-parse --verify HEAD)
# not portable ...
export DOCKERHOST_IP=$(ifconfig en0 | grep 'inet '| grep -v '127.0.0.1' | cut -d' ' -f2)
docker run --rm --name jenkins-lts -p 8080:8080 -e GITHUB_SHA -v "/var/run/docker.sock":"/var/run/docker.sock" --add-host kind-1-control-plane:${DOCKERHOST_IP} --add-host kubernetes.default.svc:127.0.0.1 -it jenkins-lts $*
