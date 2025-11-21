#!/usr/bin/env sh
nix develop --impure path:.#testenv -c bin/run_tests.sh
