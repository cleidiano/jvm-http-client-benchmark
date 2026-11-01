#!/usr/bin/env bash
# Runs every README scenario in one go, unattended. Takes about 3 hours with the defaults.
#
#   scripts/run-all.sh                  # all scenarios
#   scripts/run-all.sh --only 1,3       # a subset
#   scripts/run-all.sh --no-tune        # don't pin the CPU frequency (no sudo needed)
#   scripts/run-all.sh --list           # show the plan and exit
#   scripts/run-all.sh --quick          # minimal iterations (~10 min): checks the whole pipeline
#                                       # on this machine; the numbers are meaningless
#
# Env: JAVA_VT     java binary of a JDK 24+ (required for scenario 5, virtual threads)
#      BENCH_CPUS / SERVER_CPUS / JAVA  as in bench.sh and server.sh; every other bench.sh or
#      server.sh variable in your shell is ignored, since each scenario sets its own
#
# Everything goes to results/local/run-<timestamp>/: one JSON + .env per step, a log per step,
# run-all.log (this output) and summary.txt (scripts/summarize.py over all the JSON files).
#
# Behaviour on failures: a step whose benchmarks throw keeps the results of the clients that
# worked (FAIL_ON_ERROR=0) and is reported as a warning; a scenario that cannot run at all
# (server not starting, wrong configuration) is reported as failed and the next one starts.
# The exit code is non-zero if any step failed or warned.
#
# The machine should be otherwise idle for the whole run. Close or pause anything heavy, and
# run it inside tmux/screen (or nohup) so that closing the terminal doesn't stop it.
set -uo pipefail
cd "$(dirname "$0")/.."
source scripts/lib/cpu.sh

# Each scenario sets its own configuration. Anything exported in the caller's shell would
# otherwise leak into every step (e.g. EXECUTOR=VIRTUAL turning scenarios 1-4 into virtual-thread
# runs, BASE_URI skipping the server check, TUNE_CPU re-pinning the CPU on every step).
# JAVA, BENCH_CPUS and SERVER_CPUS are kept on purpose.
unset NETWORK LATENCY EXECUTOR PIN_TRACE BASE_URI MAX_CONNECTIONS TUNE_CPU FAIL_ON_ERROR LABEL RESULTS_DIR

PROFILER=io.github.cleidiano.httpbench.bench.ResourceProfiler
ALL_SCENARIOS=(1 2 3 4 5)
declare -A TITLE=(
  [1]="Cost per request (loopback, 1 thread)"
  [2]="Concurrency on loopback (4 and 16 threads)"
  [3]="Latency distribution (loopback, percentiles)"
  [4]="Simulated network (bridge + 500us RTT)"
  [5]="Virtual threads vs platform threads (JDK 24+, 20ms RTT)"
)
declare -A MINUTES=([1]=48 [2]=39 [3]=10 [4]=29 [5]=60)

# --- arguments ---------------------------------------------------------------------------------

selected=("${ALL_SCENARIOS[@]}")
tune=1
list_only=0
quick=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --only) IFS=, read -r -a selected <<< "${2:?--only needs a list, e.g. 1,3}"; shift 2 ;;
    --no-tune) tune=0; shift ;;
    --list) list_only=1; shift ;;
    --quick) quick=1; shift ;;
    -h | --help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    *) echo "unknown argument: $1 (see --help)" >&2; exit 2 ;;
  esac
done
for n in "${selected[@]}"; do
  [[ -n ${TITLE[$n]:-} ]] || { echo "unknown scenario: $n (valid: ${ALL_SCENARIOS[*]})" >&2; exit 2; }
done

total=0
echo "Plan:"
for n in "${selected[@]}"; do
  printf "  %s. %-58s ~%3d min\n" "$n" "${TITLE[$n]}" "${MINUTES[$n]}"
  total=$((total + MINUTES[$n]))
done
if [[ $quick == 1 ]]; then
  echo "  QUICK mode: minimal iterations, ~10 min in total; the numbers are meaningless"
else
  printf "  Total: ~%dh%02d\n" $((total / 60)) $((total % 60))
fi
echo "  CPU frequency: $([[ $tune == 1 ]] && echo pinned || echo NOT pinned)"
[[ $list_only == 1 ]] && exit 0

# --- preflight: fail now, not two hours in -----------------------------------------------------

die() { echo "ERROR: $*" >&2; exit 1; }

for tool in docker curl taskset python3; do
  command -v "$tool" >/dev/null || die "$tool not found"
done
docker info >/dev/null 2>&1 || die "docker is not usable by this user"

if [[ " ${selected[*]} " == *" 5 "* ]]; then
  [[ -n ${JAVA_VT:-} ]] || die "scenario 5 needs JAVA_VT=/path/to/jdk-24+/bin/java (or use --only without 5)"
  vt_feature=$("$JAVA_VT" -XshowSettings:properties -version 2>&1 \
    | awk -F'= ' '/java.specification.version/ {print $2}')
  [[ ${vt_feature:-0} -ge 24 ]] || die "JAVA_VT must be JDK 24+ (got '${vt_feature:-unknown}')"
fi

if [[ $tune == 1 ]]; then
  command -v cpupower >/dev/null || die "cpupower not found (or run with --no-tune)"
  echo "sudo is needed to pin the CPU frequency (asked once; kept alive during the run):"
  sudo -v || die "sudo failed (or run with --no-tune)"
fi

echo "building..."
./gradlew -q :benchmarks:jmhJar || die "build failed"

RUN_DIR=results/local/run-$(date +%Y%m%d-%H%M%S)$([[ $quick == 1 ]] && echo -quick)
mkdir -p "$RUN_DIR/logs"
export RESULTS_DIR=$RUN_DIR
exec > >(tee -a "$RUN_DIR/run-all.log") 2>&1
echo "run directory: $RUN_DIR"

# --- CPU pinning for the whole run, restored on any exit -----------------------------------------

ORIG_GOVERNOR=$(current_governor)
ORIG_NO_TURBO=$(current_no_turbo)
KEEPALIVE_PID=

cleanup() {
  scripts/server.sh stop >/dev/null 2>&1 || true
  if [[ $tune == 1 ]]; then
    cpu_restore "$ORIG_GOVERNOR" "$ORIG_NO_TURBO"
    [[ -n $KEEPALIVE_PID ]] && kill "$KEEPALIVE_PID" 2>/dev/null
  fi
}
trap cleanup EXIT
trap 'echo "interrupted"; exit 130' INT TERM

if [[ $tune == 1 ]]; then
  # sudo forgets the password after ~15 idle minutes; refresh it so the final restore never
  # sits waiting for a password nobody is there to type.
  ( while true; do sudo -n -v 2>/dev/null; sleep 50; done ) &
  KEEPALIVE_PID=$!
  cpu_tune
fi

# --- helpers ------------------------------------------------------------------------------------

# JMH rejects repeated options, so quick mode replaces the run-length options instead of
# appending to them: scenarios put their own -f in FORKS_* and never pass -wi/-i/-w/-r.
if [[ $quick == 1 ]]; then
  QUICK_ARGS=(-f 1 -wi 1 -w 1s -i 1 -r 1s)
  FORKS_SCENARIO_5=()
else
  QUICK_ARGS=()
  FORKS_SCENARIO_5=(-f 2)
fi

# bench <label> <bench.sh args...>: one bench.sh run, logged to logs/<label>.log.
bench() {
  local label=$1; shift
  local log="$RUN_DIR/logs/$label.log"
  echo "  $(date +%H:%M:%S)  $label"
  local status=0
  # `|| status=$?` keeps set -e (active in the scenario subshell) from exiting before we report.
  LABEL=$label FAIL_ON_ERROR=0 scripts/bench.sh "$@" "${QUICK_ARGS[@]}" > "$log" 2>&1 || status=$?
  if [[ $status != 0 ]]; then
    echo "  FAILED: $label (exit $status), see $log" >&2
    return "$status"
  fi
  if grep -q '<failure>' "$log"; then
    echo "  WARNING: some benchmarks in $label threw; the others were kept (see $log)"
    echo "$label" >> "$RUN_DIR/warnings"
  fi
}

server() {
  scripts/server.sh restart > "$RUN_DIR/logs/server-$1.log" 2>&1 \
    || { echo "  FAILED: server for scenario $1 (see $RUN_DIR/logs/server-$1.log)" >&2; return 1; }
}

# --- scenarios (same as the README blocks) ------------------------------------------------------

scenario_1() {
  export NETWORK=host LATENCY=
  server 1
  bench cost-t1 -t 1 -prof gc -prof "$PROFILER"
}

scenario_2() {
  export NETWORK=host LATENCY=
  server 2
  bench get-t4 GetBenchmark -t 4 -prof "$PROFILER"
  bench loopback-1k-t16 GetBenchmark -p payload=1k -t 16 -prof "$PROFILER"
}

scenario_3() {
  export NETWORK=host LATENCY=
  server 3
  bench latency-1k GetBenchmark -p payload=1k -bm sample -tu us -t 1
}

scenario_4() {
  export NETWORK=bridge LATENCY=500us
  server 4
  for t in 1 4 16; do
    bench "network-500us-t$t" GetBenchmark -p payload=1k -t "$t" -prof "$PROFILER"
  done
}

scenario_5() {
  export NETWORK=bridge LATENCY=20ms JAVA=$JAVA_VT MAX_CONNECTIONS=1024
  server 5
  for executor in PLATFORM VIRTUAL; do
    for t in 64 256 1024; do
      export EXECUTOR=$executor
      bench "vt-$executor-t$t" GetBenchmark -p payload=1k \
        -p client=jdk,jdk-vt,apache5,okhttp,jetty,jetty-vt -t "$t" "${FORKS_SCENARIO_5[@]}" \
        -prof gc -prof "$PROFILER"
    done
  done
}

# --- run ----------------------------------------------------------------------------------------

declare -A RESULT
started=$(date +%s)
for n in "${selected[@]}"; do
  echo
  echo "=== Scenario $n: ${TITLE[$n]} (~${MINUTES[$n]} min, started $(date +%H:%M))"
  t0=$(date +%s)
  # A plain subshell (never inside if/&&/||, where bash would silently disable set -e).
  ( set -e; "scenario_$n" )
  status=$?
  minutes=$(( ($(date +%s) - t0) / 60 ))
  if [[ $status == 0 ]]; then RESULT[$n]="ok (${minutes} min)"; else RESULT[$n]="FAILED (${minutes} min)"; fi
  echo "=== Scenario $n: ${RESULT[$n]}"
done
scripts/server.sh stop >/dev/null 2>&1

# --- report -------------------------------------------------------------------------------------

shopt -s nullglob
jsons=("$RUN_DIR"/*.json)
if [[ ${#jsons[@]} -gt 0 ]]; then
  python3 -I scripts/summarize.py "${jsons[@]}" > "$RUN_DIR/summary.txt" 2>&1
fi

echo
echo "=== Done in $(( ($(date +%s) - started) / 60 )) min"
failed=0
for n in "${selected[@]}"; do
  echo "  $n. ${TITLE[$n]}: ${RESULT[$n]}"
  [[ ${RESULT[$n]} == ok* ]] || failed=1
done
if [[ -f $RUN_DIR/warnings ]]; then
  echo "  steps with failed benchmarks (other clients kept): $(paste -sd' ' "$RUN_DIR/warnings")"
  failed=1
fi
echo "  results: $RUN_DIR (summary: $RUN_DIR/summary.txt)"
exit "$failed"
