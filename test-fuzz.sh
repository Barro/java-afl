#!/usr/bin/env bash

set -euo pipefail

export AFL_SKIP_CPUFREQ=1
export AFL_NO_UI=1

rm -rf out/fuzz-forking
timeout --preserve-status -s INT 15 ./java-afl-fuzz -m 30000 -i in/ -o out/fuzz-forking -- java -cp out/ins test.Forking
queue_files=$(find out/fuzz-forking/queue -type f | wc -l)
if [[ "$queue_files" -lt 15 ]]; then
    echo >&2 "Forking mode does not seem to provide unique paths!"
    exit 1
fi
