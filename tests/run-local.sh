#!/bin/sh
# Local test runner: JS polyfills. Runs on any Linux box with node (no
# Android SDK needed); the Kotlin unit tests run in CI. The exec-hook /
# bash-wrapper C tests were removed with the single-runtime refactor (no
# forwarding layer anymore).
set -eu
cd "$(dirname "$0")/.."

echo "== JS: compat-polyfills =="
node tests/js/polyfills.test.js

echo "ALL LOCAL TESTS PASSED"