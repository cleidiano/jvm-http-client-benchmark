package io.github.cleidiano.httpbench.clients;

import io.github.cleidiano.httpbench.api.BenchClient;
import io.github.cleidiano.httpbench.api.BenchClientProvider;
import io.github.cleidiano.httpbench.api.ClientConfig;

/**
 * Experiment, not a recommendation: N independent {@link OkHttpBenchClient}s, picked by the
 * calling thread's id.
 *
 * <p>OkHttp finds a free connection by scanning every pooled connection under a per-connection
 * lock ({@code RealConnectionPool.callAcquirePooledConnection}), so the cost of each request grows
 * with the pool size. With 1024 concurrent requests that scan took ~26% of CPU. Splitting the
 * connections across N pools makes each scan N times shorter; if throughput recovers, the scan is
 * the cause.
 */
public final class OkHttpShardedBenchClient implements BenchClient {

    private static final int SHARDS = 8;

    private final OkHttpBenchClient[] shards = new OkHttpBenchClient[SHARDS];

    OkHttpShardedBenchClient(ClientConfig config) {
        var perShard = new ClientConfig(config.baseUri(),
                Math.max(1, config.maxConnections() / SHARDS), config.timeout());
        for (int i = 0; i < SHARDS; i++) {
            shards[i] = new OkHttpBenchClient(perShard);
        }
    }

    private OkHttpBenchClient shard() {
        return shards[(int) (Thread.currentThread().threadId() % SHARDS)];
    }

    @Override
    public byte[] get(String path) throws Exception {
        return shard().get(path);
    }

    @Override
    public byte[] postJson(String path, byte[] body) throws Exception {
        return shard().postJson(path, body);
    }

    @Override
    public void close() {
        for (var shard : shards) {
            shard.close();
        }
    }

    public static final class Provider implements BenchClientProvider {
        @Override
        public String name() {
            return "okhttp-sharded";
        }

        @Override
        public BenchClient create(ClientConfig config) {
            return new OkHttpShardedBenchClient(config);
        }
    }
}
