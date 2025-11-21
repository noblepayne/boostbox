#!/usr/bin/env bash
export BB_REAL_S3_IN_TEST=1
process-compose up -D
devenv-flake-test
process-compose down
