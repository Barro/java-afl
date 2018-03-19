#!/usr/bin/env bash
#
# Copyright 2018  Jussi Judin
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -xeuo pipefail

export AFL_SKIP_CPUFREQ=1
export AFL_NO_UI=1
# Enable running in Travis CI without root access:
export AFL_NO_AFFINITY=1
export AFL_I_DONT_CARE_ABOUT_MISSING_CRASHES=1

test_timeout=10
testcase_timeout=1000+

function check_fuzz_status()
{
    local mode=$1
    local queue_files
    queue_files=$(find out/fuzz-"$mode"/queue -type f | grep -v .state | wc -l)
    if [[ "$queue_files" -lt 15 ]]; then
        echo >&2 "$mode mode does not seem to provide unique paths!"
        exit 1
    fi
    local unstable_results
    unstable_results=$(
        grep stability out/fuzz-"$mode"/fuzzer_stats | grep -v "100.00%" || :)
    if [[ -n "${unstable_results:-}" ]]; then
        echo >&2 "$mode mode was unstable: $unstable_results"
        exit 1
    fi
}

for mode in Forking Deferred Persistent; do
    rm -rf out/fuzz-"$mode"
    timeout --preserve-status -s INT "$test_timeout" \
            ./java-afl-fuzz -t "$testcase_timeout" -m 30000 -i in/ -o out/fuzz-"$mode" \
            -- java -cp out/ins test."$mode"
    check_fuzz_status "$mode"

    # TODO persistent dynamic instrumentation is not 100% stable.
    if [[ "$mode" == Persistent ]]; then
        continue
    fi
    timeout --preserve-status -s INT "$test_timeout" \
            ./java-afl-fuzz -t "$testcase_timeout" -m 30000 -i in/ -o out/fuzz-"$mode" \
            -- java -cp java-afl-run.jar:out javafl.run test."$mode"
    check_fuzz_status "$mode"
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

rm -rf out/fuzz-Crashing
timeout --preserve-status -s INT "$test_timeout" \
        ./java-afl-fuzz -t "$testcase_timeout" -m 30000 -i in/ -o out/fuzz-Crashing \
        -- java -cp java-afl-run.jar:out javafl.run test.Crashing
crash_files=$(find out/fuzz-Crashing/crashes -name 'id:*' -type f | grep -v .state | wc -l)
if [[ "$crash_files" -lt 1 ]]; then
    echo >&2 "There definitely should be some dynamic crashes!"
    exit 1
fi
