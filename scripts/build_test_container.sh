#!/usr/bin/env bash

set -euo pipefail

sudo podman build -t boostbox_test -f Containerfile.tests
