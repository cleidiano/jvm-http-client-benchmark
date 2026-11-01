package io.github.cleidiano.httpbench.api;

import java.util.ServiceLoader;
import java.util.TreeSet;

public final class BenchClients {

    private BenchClients() {
    }

    public static BenchClient create(String name, ClientConfig config) throws Exception {
        for (BenchClientProvider provider : ServiceLoader.load(BenchClientProvider.class)) {
            if (provider.name().equals(name)) {
                return provider.create(config);
            }
        }
        throw new IllegalArgumentException("Unknown client '" + name + "'. Available: " + available());
    }

    public static TreeSet<String> available() {
        var names = new TreeSet<String>();
        ServiceLoader.load(BenchClientProvider.class).forEach(p -> names.add(p.name()));
        return names;
    }
}
