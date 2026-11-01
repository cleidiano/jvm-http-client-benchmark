package io.github.cleidiano.httpbench.bench;

import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.profile.InternalProfiler;
import org.openjdk.jmh.results.AggregationPolicy;
import org.openjdk.jmh.results.IterationResult;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.ScalarResult;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * What a request costs beyond wall-clock time, per measurement iteration:
 * <ul>
 *   <li>{@code cpu.time.norm}: process CPU time per operation (ns/op). Covers every thread of
 *       the benchmark JVM, including the client's internal selector/executor threads, which
 *       wall-clock time per thread hides. Includes JIT and GC threads too.</li>
 *   <li>{@code threads.platform.peak}: peak live platform threads (virtual threads are not
 *       counted by the JVM's thread MXBean, which is the point).</li>
 *   <li>{@code rss}: resident memory of the process at the end of the iteration (MB).</li>
 * </ul>
 *
 * <p>Use with {@code -prof io.github.cleidiano.httpbench.bench.ResourceProfiler}.
 */
public class ResourceProfiler implements InternalProfiler {

    private static final Path STATUS = Path.of("/proc/self/status");

    private final com.sun.management.OperatingSystemMXBean os =
            (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();

    private long cpuAtStart;

    @Override
    public String getDescription() {
        return "Process CPU time per op, peak live platform threads, resident memory";
    }

    @Override
    public void beforeIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams) {
        threads.resetPeakThreadCount();
        cpuAtStart = os.getProcessCpuTime();
    }

    @Override
    public List<Result> afterIteration(BenchmarkParams benchmarkParams, IterationParams iterationParams,
                                       IterationResult result) {
        long cpuNanos = os.getProcessCpuTime() - cpuAtStart;
        // All ops, not just measured ones: the CPU window spans the whole iteration, including
        // JMH's ramp-up/ramp-down where threads keep working but ops are not "measured". With
        // many threads those phases are long, and dividing by measured ops alone inflated the
        // result (it implied >2 CPUs busy on a process pinned to 2).
        long ops = result.getMetadata().getAllOps();
        var results = new java.util.ArrayList<Result>(3);
        if (ops > 0) {
            results.add(new ScalarResult("cpu.time.norm", (double) cpuNanos / ops, "ns/op", AggregationPolicy.AVG));
        }
        results.add(new ScalarResult("threads.platform.peak", threads.getPeakThreadCount(), "threads",
                AggregationPolicy.MAX));
        double rss = residentMegabytes();
        if (rss >= 0) {
            results.add(new ScalarResult("rss", rss, "MB", AggregationPolicy.MAX));
        }
        return results;
    }

    private static double residentMegabytes() {
        try {
            for (String line : Files.readAllLines(STATUS)) {
                if (line.startsWith("VmRSS:")) {
                    String kb = line.substring("VmRSS:".length()).replace("kB", "").trim();
                    return Long.parseLong(kb) / 1024.0;
                }
            }
        } catch (IOException | NumberFormatException e) {
            // Not on Linux, or unexpected format: report nothing rather than a wrong number.
        }
        return -1;
    }
}
