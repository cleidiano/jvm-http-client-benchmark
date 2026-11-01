# CPU frequency helpers shared by bench.sh and run-all.sh (sourced, not executed).
#
# Pinning the frequency (performance governor, turbo off) removes most run-to-run noise on
# laptops. Turbo control uses intel_pstate; on other drivers no_turbo reads as "unknown" and only
# the governor is changed.

CPUFREQ=/sys/devices/system/cpu
NO_TURBO=$CPUFREQ/intel_pstate/no_turbo

current_governor() { cat "$CPUFREQ/cpu0/cpufreq/scaling_governor" 2>/dev/null || echo unknown; }
current_no_turbo() { cat "$NO_TURBO" 2>/dev/null || echo unknown; }

cpu_is_tuned() { [[ $(current_governor) == performance && $(current_no_turbo) != 0 ]]; }

# Pins the frequency. Needs sudo.
cpu_tune() {
  sudo cpupower frequency-set -g performance >/dev/null
  [[ $(current_no_turbo) == unknown ]] || echo 1 | sudo tee "$NO_TURBO" >/dev/null
  echo "CPU tuned: governor=$(current_governor) no_turbo=$(current_no_turbo)"
}

# Restores the given governor and no_turbo value. Needs sudo; never fails.
cpu_restore() {
  local governor=$1 no_turbo=$2
  echo "restoring CPU settings: governor=$governor no_turbo=$no_turbo"
  sudo cpupower frequency-set -g "$governor" >/dev/null || true
  [[ $no_turbo == unknown ]] || echo "$no_turbo" | sudo tee "$NO_TURBO" >/dev/null || true
}
