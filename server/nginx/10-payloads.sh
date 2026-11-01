#!/bin/sh
# Runs from the official image's /docker-entrypoint.d/ before nginx starts.
# Sizes must match benchmarks/src/jmh/java/io/github/cleidiano/httpbench/bench/Payloads.java.
set -eu

dir=/usr/share/nginx/bench/bytes
mkdir -p "$dir"

: > "$dir/0"
head -c 1024 /dev/zero | tr '\0' 'x' > "$dir/1k"
head -c 102400 /dev/zero | tr '\0' 'x' > "$dir/100k"
