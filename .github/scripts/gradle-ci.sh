#!/usr/bin/env bash
# A repository can transiently report an existing plugin as missing during Gradle
# configuration. Retry that bootstrap failure, never a compiler/test failure.
set -euo pipefail

log=$(mktemp)
trap 'rm -f "$log"' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for attempt in 1 2 3; do
    command=(./gradlew --no-daemon --console=plain)
    if (( attempt > 1 )); then
        # Gradle caches missing metadata too. Recheck it without deleting the
        # successfully downloaded artifacts from the previous attempt.
        # Include repository diagnostics if the retry also fails.
        command+=(--refresh-dependencies --info)
    fi
    command+=("$@")
    if "${command[@]}" 2>&1 | tee "$log"; then
        exit 0
    else
        statuses=("${PIPESTATUS[@]}")
        status=${statuses[0]}
        if (( status == 0 )); then
            exit "${statuses[1]}"
        fi
    fi

    if (( attempt == 3 || status >= 128 )) ||
        grep -qE '^> Task ' "$log" ||
        ! grep -qE '^Plugin \[id: .*\] was not found in any of the following sources:' "$log"; then
        exit "$status"
    fi

    delay=$((attempt * 10))
    printf 'Plugin resolution failed before task execution; retrying in %ss (attempt %s/3).\n' "$delay" "$((attempt + 1))"
    sleep "$delay"
done
