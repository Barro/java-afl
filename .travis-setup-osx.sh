#!/bin/bash

set -xeuo pipefail

HOMEBREW_NO_AUTO_UPDATE=1 brew install bazel coreutils cmake ninja
