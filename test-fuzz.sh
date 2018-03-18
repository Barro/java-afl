#!/usr/bin/env bash

set -xeuo pipefail

export AFL_SKIP_CPUFREQ=1
export AFL_NO_UI=1
# Enable running in Travis CI without root access:
export AFL_NO_AFFINITY=1
export AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES=1

if [[ "${TRAVIS:-false}" == false ]]; then
    test_timeout=10
    testcase_timeout=1000
else
    # Travis CI needs more time for everything:
    test_timeout=20
    testcase_timeout=2000
fi

for mode in Forking Deferred Persistent; do
    rm -rf out/fuzz-"$mode"
    timeout --preserve-status -s INT "$test_timeout" \
            ./java-afl-fuzz -t "$testcase_timeout" -m 30000 -i in/ -o out/fuzz-"$mode" \
            -- java -cp out/ins test."$mode"
    queue_files=$(find out/fuzz-"$mode"/queue -type f | grep -v .state | wc -l)
    if [[ "$queue_files" -lt 15 ]]; then
        echo >&2 "$mode mode does not seem to provide unique paths!"
        exit 1
    fi
done

rm -rf out/fuzz-Null
timeout --preserve-status -s INT "$test_timeout" \
        ./java-afl-fuzz -t "$testcase_timeout" -m 30000 -i in/ -o out/fuzz-Null \
        -- java -cp out/ins test.Null
queue_files=$(find out/fuzz-Null/queue -name 'id:*' -type f | grep -v .state | wc -l)
in_files=$(find in/ -type f | wc -l)
if [[ "$queue_files" -ne "$in_files" ]]; then
    echo >&2 "When input is not read, program should not create any outputs!"
    exit 1
fi

rm -rf out/fuzz-Crashing
timeout --preserve-status -s INT "$test_timeout" \
        ./java-afl-fuzz -t "$testcase_timeout" -m 30000 -i in/ -o out/fuzz-Crashing \
        -- java -cp out/ins test.Crashing
crash_files=$(find out/fuzz-Crashing/crashes -name 'id:*' -type f | grep -v .state | wc -l)
if [[ "$crash_files" -lt 1 ]]; then
    echo >&2 "There definitely should be some crashes!"
    exit 1
fi
