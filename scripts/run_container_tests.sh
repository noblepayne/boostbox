#!/usr/bin/env bash

set -euo pipefail

sudo podman run --rm -v "$PWD:/app" -w /app --name boostbox_tests -it --network none -u ubuntu boostbox_test "/app/scripts/ci.sh"
