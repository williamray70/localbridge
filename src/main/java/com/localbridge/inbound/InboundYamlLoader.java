/*
 * =============================================================================
 *  InboundYamlLoader
 * =============================================================================
 *  Loads inbound MLLP channel configs from:
 *      conf/channels/Inbound/*.yaml
 *  Schema (simplified):
 *    - name: Admissions-Inbound
 *      enabled: true
 *      port: 2575
 *      saveDir: "/path/to/inbound"
 *      filePrefix: ADT_
 *      fileSuffix: .hl7
 *      description: "ADT inbound feed"
 * =============================================================================
 */
package com.localbridge.inbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

public class InboundYamlLoader {

    private static final Logger log = LoggerFactory.getLogger(InboundYamlLoader.class);

    public static final String INBOUND_SUBDIR = "Inbound";

    public List<InboundChannelConfig> loadFromDirectory(Path channelsRoot) throws IOException {
        Path inboundDir = channelsRoot.resolve(INBOUND_SUBDIR);

        if (!Files.exists(inboundDir)) {
            Files.createDirectories(inboundDir);
        }
        if (!Files.isDirectory(inboundDir)) {
            throw new IOException("Inbound path is not a directory: " + inboundDir);
        }

        List<InboundChannelConfig> results = new ArrayList<>();

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inboundDir, "*.y{a,}ml")) {
            for (Path p : ds) {
                results.addAll(loadFile(p));
            }
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }

        return results;
    }

    private List<InboundChannelConfig> loadFile(Path yamlFile) throws IOException {
        List<InboundChannelConfig> list = new ArrayList<>();

        LoaderOptions opts = new LoaderOptions();
        Yaml yaml = new Yaml(new Constructor(Object.class, opts));

        try (InputStream is = Files.newInputStream(yamlFile)) {
            Object root = yaml.load(is);

            if (root == null) {
                return list;
            } else if (root instanceof Map<?, ?> m) {
                // Single object
                list.add(parseOne(yamlFile, asMapStringObject(m)));
            } else if (root instanceof List<?> arr) {
                // Array of objects
                for (Object o : arr) {
                    if (o instanceof Map<?, ?> mm) {
                        list.add(parseOne(yamlFile, asMapStringObject(mm)));
                    } else {
                        log.warn("Skipping non-map entry in {}: {}", yamlFile, o);
                    }
                }
            } else {
                log.warn("Unsupported YAML root in {}: {}", yamlFile, root.getClass());
            }
        }

        return list;
    }

    private static Map<String, Object> asMapStringObject(Map<?, ?> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : in.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private InboundChannelConfig parseOne(Path source, Map<String, Object> m) throws IOException {
        String name = str(m.get("name"), "Inbound-" + UUID.randomUUID());
        boolean enabled = bool(m.get("enabled"), true);
        Integer port = integer(m.get("port"), 0);
        String saveDirStr = str(m.get("saveDir"), null);
        String filePrefix = str(m.getOrDefault("filePrefix", ""), "");
        String fileSuffix = str(m.getOrDefault("fileSuffix", ".hl7"), ".hl7");
        String description = str(m.get("description"), "");

        if (port == null || port <= 0 || port > 65535) {
            throw new IOException("Invalid or missing 'port' in " + source);
        }
        if (saveDirStr == null || saveDirStr.isBlank()) {
            throw new IOException("Missing 'saveDir' in " + source);
        }

        Path saveDir = Paths.get(saveDirStr);

        // Use the helper to enforce MLLP + autoAck=true
        return InboundChannelConfig.of(name, enabled, port, saveDir, filePrefix, fileSuffix, description);
    }

    private static String str(Object o, String def) {
        return (o == null) ? def : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        if (o == null) return def;
        if (o instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(o));
    }

    private static Integer integer(Object o, Integer def) {
        if (o == null) return def;
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return def;
        }
    }
}
