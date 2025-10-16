package com.localbridge.outbound;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Scans conf/channels/Outbound for YAML files and loads outbound channel configs.
 * Accepts either:
 *  - A list of channel maps (common case), or
 *  - A single channel map
 */
public final class OutboundYamlLoader {

    public static final String OUTBOUND_SUBDIR = "Outbound";

    public List<OutboundChannelConfig> loadFromDirectory(Path channelsRoot) throws IOException {
        Path outboundDir = channelsRoot.resolve(OUTBOUND_SUBDIR);

        if (!Files.exists(outboundDir)) {
            Files.createDirectories(outboundDir);
        }
        if (!Files.isDirectory(outboundDir)) {
            throw new IOException("Outbound path is not a directory: " + outboundDir);
        }

        List<Path> yamls;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(outboundDir, "*.yaml")) {
            yamls = new ArrayList<>();
            for (Path p : ds) yamls.add(p);
        }

        List<OutboundChannelConfig> results = new ArrayList<>();
        for (Path yaml : yamls) {
            results.addAll(loadOneFile(yaml));
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private List<OutboundChannelConfig> loadOneFile(Path yamlFile) throws IOException {
        LoaderOptions opts = new LoaderOptions();
        Yaml yaml = new Yaml(new SafeConstructor(opts));
        try (InputStream in = Files.newInputStream(yamlFile)) {
            Object root = yaml.load(in);
            if (root == null) return List.of();

            if (root instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) root;
                return list.stream().map(this::toConfig).collect(Collectors.toList());
            } else if (root instanceof Map) {
                return List.of(toConfig((Map<String, Object>) root));
            } else {
                throw new IOException("Unsupported YAML structure in " + yamlFile + ": " + root.getClass());
            }
        }
    }

    private OutboundChannelConfig toConfig(Map<String, Object> map) {
        String name = str(map.get("name"), "Outbound-" + UUID.randomUUID());
        boolean enabled = bool(map.get("enabled"), true);
        String host = str(map.get("host"), "127.0.0.1");
        int port = intval(map.get("port"), 2576);

        Path sourceDir = Paths.get(str(map.get("sourceDir"), "outbox"));
        String pattern = str(map.get("pattern"), "*.hl7");

        boolean waitForAck = bool(map.get("waitForAck"), true);
        int connectTimeoutMs = intval(map.get("connectTimeoutMs"), 5000);
        int readTimeoutMs = intval(map.get("readTimeoutMs"), 5000);
        int pollIntervalMs = intval(map.get("pollIntervalMs"), 1000);

        Path errorDir = optPath(map.get("errorDir"));
        String description = str(map.get("description"), null);

        return new OutboundChannelConfig(
                name, enabled, host, port,
                sourceDir, pattern,
                waitForAck, connectTimeoutMs, readTimeoutMs, pollIntervalMs,
                errorDir, description
        );
    }

    private static String str(Object v, String dflt) {
        return v == null ? dflt : String.valueOf(v);
    }

    private static boolean bool(Object v, boolean dflt) {
        return v == null ? dflt : (v instanceof Boolean ? (Boolean) v : Boolean.parseBoolean(String.valueOf(v)));
    }

    private static int intval(Object v, int dflt) {
        if (v == null) return dflt;
        if (v instanceof Number) return ((Number) v).intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return dflt; }
    }

    private static Path optPath(Object v) {
        if (v == null) return null;
        return Paths.get(String.valueOf(v));
    }
}
