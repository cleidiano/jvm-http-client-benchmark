package io.github.cleidiano.httpbench.bench;

import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Run settings shared by every benchmark (JMH inherits these annotations).
 *
 * <p>Warmup is long on purpose: the JDK client was still speeding up after 10 s
 * (2.3k → 7.5k ops/s across iterations), so 30 s keeps every client in steady state.
 * Three forks expose fork-to-fork variance (JIT and memory-layout luck).
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(value = 3, jvmArgsAppend = {"-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"})
public abstract class BenchmarkDefaults {
}
