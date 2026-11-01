#!/usr/bin/env bash
# Build the JMH fat jar and run it pinned to the benchmark CPUs. Extra args go straight to JMH.
#
#   scripts/bench.sh                                  # everything, defaults from annotations
#   scripts/bench.sh GetBenchmark -p client=jdk,okhttp -t 8
#   scripts/bench.sh GetBenchmark -bm sample -tu us   # latency distribution
#   scripts/bench.sh -prof gc                         # + allocation per request
#
# Before measuring, the running server must match NETWORK/LATENCY (same variables and
# defaults as scripts/server.sh: host, no latency); otherwise the run aborts. Export them
# once per scenario so both scripts agree.
#
# Env: NETWORK / LATENCY  expected server configuration (default: host / none)
#      BASE_URI    explicit target; skips the server check (e.g. a server on another machine)
#      BENCH_CPUS  (default 0,2)  MAX_CONNECTIONS (default 64)
#      LABEL       suffix for the results file: <RESULTS_DIR>/<timestamp>-<LABEL>.json
#      RESULTS_DIR where results go (default results/local, ignored by git); curated results
#                  are committed under results/<machine>/
#      JAVA        java binary to run the benchmarks with (default: `java` on PATH); JMH forks use it too
#      EXECUTOR    PLATFORM (default) or VIRTUAL: run JMH benchmark threads as virtual threads
#                  (-Djmh.executor); the benchmark setup aborts if the threads are not of that kind
#      PIN_TRACE=1 print a stack trace whenever a virtual thread pins its carrier (diagnostics only:
#                  the tracing itself costs time, so don't use it for measurements)
#      FAIL_ON_ERROR=0  keep running the remaining benchmarks after one fails (default 1: stop,
#                  and exit non-zero); failures then only show as <failure> in the output
#      TUNE_CPU=1  pin all CPUs to the "performance" governor and disable turbo for the run
#                  (needs sudo; the original settings are restored on exit, including Ctrl+C)
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "ERROR: $*" >&2; exit 1; }

# --- 1. Resolve and verify the target (before touching the CPU or asking for sudo) ---------

needs_server=1
for arg in "$@"; do
  case "$arg" in -l | -lp | -h | -help) needs_server=0 ;; esac
done

SERVER_CONFIG="external (BASE_URI set explicitly)"
if [[ -z ${BASE_URI:-} ]]; then
  if [[ $needs_server == 1 ]]; then
    BASE_URI=$(scripts/server.sh uri 2>/dev/null) || die "no server running; start it with scripts/server.sh start"
    expected="network=${NETWORK:-host} latency=${LATENCY:-none}"
    SERVER_CONFIG=$(scripts/server.sh config)
    if [[ $SERVER_CONFIG != "$expected" ]]; then
      die "server is '$SERVER_CONFIG' but this run expects '$expected'.
       Run: NETWORK=${NETWORK:-host} LATENCY=${LATENCY:-} scripts/server.sh restart"
    fi
  else
    BASE_URI=http://127.0.0.1:8080
    SERVER_CONFIG="not needed (listing/help only)"
  fi
fi

if [[ $needs_server == 1 ]]; then
  curl -fsS -o /dev/null --max-time 5 "$BASE_URI/health" || die "server at $BASE_URI is not healthy"
fi
echo "target: $BASE_URI ($SERVER_CONFIG)"

JAVA=${JAVA:-java}
"$JAVA" -version >/dev/null 2>&1 || die "JAVA=$JAVA is not a working java binary"
echo "java: $("$JAVA" -version 2>&1 | head -1)"

EXECUTOR=${EXECUTOR:-PLATFORM}
case "$EXECUTOR" in PLATFORM | VIRTUAL) ;; *) die "EXECUTOR must be PLATFORM or VIRTUAL" ;; esac
JVM_PROPS=(-Dbench.baseUri="$BASE_URI" -Dbench.maxConnections="${MAX_CONNECTIONS:-64}" -Djmh.executor="$EXECUTOR")
if [[ ${PIN_TRACE:-0} == 1 ]]; then
  # The trace is lost when JMH forks (it never reaches the host output), so diagnostics run
  # in-process (-f 0). Numbers from such a run are not comparable with forked runs.
  JVM_PROPS+=(-Djdk.tracePinnedThreads=short)
  echo "executor: $EXECUTOR (pin trace on: non-forked diagnostic run, do not use the numbers)"
else
  echo "executor: $EXECUTOR"
fi

# --- 2. CPU frequency ------------------------------------------------------------------------

source scripts/lib/cpu.sh

ORIG_GOVERNOR=$(current_governor)
ORIG_NO_TURBO=$(current_no_turbo)

if [[ ${TUNE_CPU:-0} == 1 ]]; then
  trap 'cpu_restore "$ORIG_GOVERNOR" "$ORIG_NO_TURBO"' EXIT
  cpu_tune
elif ! cpu_is_tuned; then
  echo "WARNING: governor=$(current_governor) no_turbo=$(current_no_turbo); frequency scaling adds noise." >&2
  echo "         Re-run with TUNE_CPU=1 for stable numbers." >&2
fi

# --- 3. Build and run ------------------------------------------------------------------------

./gradlew -q :benchmarks:jmhJar

JAR=$(ls benchmarks/build/libs/*-jmh.jar)

RESULTS_DIR=${RESULTS_DIR:-results/local}
mkdir -p "$RESULTS_DIR"
OUT="$RESULTS_DIR/$(date +%Y%m%d-%H%M%S)${LABEL:+-$LABEL}.json"

if [[ $needs_server == 1 ]]; then
  # What this result actually measured, kept next to it.
  cat > "${OUT%.json}.env" <<EOF
date=$(date -Iseconds)
args=$*
target=$BASE_URI
server=$SERVER_CONFIG
bench_cpus=${BENCH_CPUS:-0,2}
max_connections=${MAX_CONNECTIONS:-64}
executor=$EXECUTOR
pin_trace=${PIN_TRACE:-0}
governor=$(current_governor)
no_turbo=$(current_no_turbo)
cpu=$(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2- | xargs)
java=$("$JAVA" -version 2>&1 | head -1)
kernel=$(uname -r)
EOF
fi

# -foe: fail on error, so a benchmark that throws makes this script (and `set -e` callers) fail.
if [[ ${FAIL_ON_ERROR:-1} == 0 ]]; then FOE=false; else FOE=true; fi
set +e
if [[ ${PIN_TRACE:-0} == 1 ]]; then
  taskset -c "${BENCH_CPUS:-0,2}" "$JAVA" "${JVM_PROPS[@]}" -jar "$JAR" \
    -foe "$FOE" -rf json -rff "$OUT" "$@" -f 0
else
  taskset -c "${BENCH_CPUS:-0,2}" "$JAVA" -jar "$JAR" \
    -jvmArgsPrepend "${JVM_PROPS[*]}" \
    -foe "$FOE" -rf json -rff "$OUT" "$@"
fi
status=$?
set -e

if [[ $status != 0 ]]; then
  echo "ERROR: JMH exited with status $status" >&2
  exit "$status"
fi
if [[ -f $OUT ]]; then
  # JMH records the JVM path; keep the home directory out of files that may be published.
  sed -i "/\"jvm\"/ s#\"$HOME/#\"~/#" "$OUT"
  echo "results: $OUT  (visualize at https://jmh.morethan.io)"
fi
