/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: ChannelEngineFactory.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Loads YAML channel configs and constructs ChannelConfig.
 *      Supports 'destinations:' + legacy 'output-dir'.
 *      FIX: call WrapiScriptTransformer(Path, boolean) per current project.
 * =============================================================================
 */
package com.localbridge.engine;

import com.localbridge.transformer.HL7Transformer;
import com.localbridge.transformer.wrapi.WrapiScriptTransformer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class ChannelEngineFactory {

    private ChannelEngineFactory() {}

    public static ChannelEngine createEngine(Path yamlPath) throws Exception {
        ChannelConfig cfg = loadConfig(yamlPath);
        return new ChannelEngine(cfg);
    }

    public static HL7Transformer createTransformer(ChannelConfig cfg) throws Exception {
        String type = s(cfg.getTransformerType());
        if ("wrapi".equalsIgnoreCase(type)) {
            String script = s(cfg.getTransformerScript());
            boolean createMissing = cfg.isCreateMissing();
            Path scriptPath = resolvePath(script);
            return new WrapiScriptTransformer(scriptPath, createMissing);
        } else if ("java".equalsIgnoreCase(type)) {
            String clazz = s(cfg.getTransformerClass());
            Class<?> c = Class.forName(clazz);
            return (HL7Transformer) c.getDeclaredConstructor().newInstance();
        }
        throw new IllegalArgumentException("Unknown transformer type: " + type);
    }

    public static ChannelConfig loadConfig(Path yamlPath) throws IOException {
        List<String> lines = Files.readAllLines(yamlPath, StandardCharsets.UTF_8);
        ChannelConfig cfg = new ChannelConfig();

        // defaults
        cfg.setName(yamlPath.getFileName().toString());
        cfg.setEnabled(true);
        cfg.setInputPattern("*.hl7");
        cfg.setPollIntervalMs(1000);
        cfg.setBatchSize(10);
        cfg.setTransformerType("wrapi");
        cfg.setCreateMissing(true);
        cfg.setValidateProfile(false);
        cfg.setArchiveEnabled(true);
        cfg.setArchiveCompress(true);
        cfg.setMoveToError(true);

        // collect destinations
        List<String> destinations = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;

            if (line.startsWith("name:")) cfg.setName(unquote(val(line)));
            else if (line.startsWith("enabled:")) cfg.setEnabled(bool(val(line)));

            else if (line.startsWith("input-dir:")) cfg.setInputDir(unquote(val(line)));
            else if (line.startsWith("output-dir:")) cfg.setOutputDir(unquote(val(line)));
            else if (line.startsWith("error-dir:")) cfg.setErrorDir(unquote(val(line)));
            else if (line.startsWith("archive-dir:")) cfg.setArchiveDir(unquote(val(line)));

            else if (line.startsWith("input-pattern:")) cfg.setInputPattern(unquote(val(line)));
            else if (line.startsWith("poll-interval-ms:")) cfg.setPollIntervalMs(toInt(val(line), 1000));
            else if (line.startsWith("batch-size:")) cfg.setBatchSize(toInt(val(line), 10));

            else if (line.equals("transformer:")) {
                // parse nested block
                int base = leadingSpaces(lines.get(i));
                int j = i + 1;
                while (j < lines.size() && leadingSpaces(lines.get(j)) > base) {
                    String t = lines.get(j).strip();
                    if (t.startsWith("type:")) cfg.setTransformerType(unquote(val(t)));
                    else if (t.startsWith("script:")) cfg.setTransformerScript(unquote(val(t)));
                    else if (t.startsWith("class:")) cfg.setTransformerClass(unquote(val(t)));
                    else if (t.startsWith("create-missing:")) cfg.setCreateMissing(bool(val(t)));
                    else if (t.startsWith("validate-profile:")) cfg.setValidateProfile(bool(val(t)));
                    j++;
                }
                i = j - 1;
            }

            else if (line.equals("error-handling:")) {
                int base = leadingSpaces(lines.get(i));
                int j = i + 1;
                while (j < lines.size() && leadingSpaces(lines.get(j)) > base) {
                    String t = lines.get(j).strip();
                    if (t.startsWith("retry-count:")) cfg.setRetryCount(toInt(val(t), 3));
                    else if (t.startsWith("retry-delay-ms:")) cfg.setRetryDelayMs(toInt(val(t), 5000));
                    else if (t.startsWith("move-to-error:")) cfg.setMoveToError(bool(val(t)));
                    j++;
                }
                i = j - 1;
            }

            else if (line.equals("archive:")) {
                int base = leadingSpaces(lines.get(i));
                int j = i + 1;
                while (j < lines.size() && leadingSpaces(lines.get(j)) > base) {
                    String t = lines.get(j).strip();
                    if (t.startsWith("enabled:")) cfg.setArchiveEnabled(bool(val(t)));
                    else if (t.startsWith("compress:")) cfg.setArchiveCompress(bool(val(t)));
                    j++;
                }
                i = j - 1;
            }

            else if (line.equals("destinations:")) {
                int base = leadingSpaces(lines.get(i));
                int j = i + 1;
                while (j < lines.size() && leadingSpaces(lines.get(j)) > base) {
                    String rr = lines.get(j);
                    if (rr.trim().isEmpty()) { j++; continue; }
                    String t = rr.strip();
                    if (t.startsWith("-")) {
                        // try nested "path:" or shorthand "- /dir"
                        int k = j + 1;
                        String pathStr = null;
                        while (k < lines.size() && leadingSpaces(lines.get(k)) > leadingSpaces(rr)) {
                            String ts = lines.get(k).strip();
                            if (ts.startsWith("path:")) pathStr = unquote(val(ts));
                            k++;
                        }
                        if (pathStr == null) {
                            String after = t.substring(1).trim();
                            if (!after.isEmpty() && !after.contains(":")) pathStr = unquote(after);
                        }
                        if (pathStr != null && !pathStr.isBlank()) destinations.add(pathStr);
                        j = k;
                    } else {
                        j++;
                    }
                }
                i = j - 1;
            }
        }

        // apply destinations into config (and keep legacy outputDir for mirror)
        cfg.clearDestinations();
        if (!destinations.isEmpty()) {
            for (String d : destinations) cfg.addDestination(d);
        } else if (cfg.getOutputDir() != null) {
            // if no destinations declared, legacy outputDir acts as first destination
            cfg.addDestination(cfg.getOutputDir());
        }

        return cfg;
    }

    // ---- helpers ----
    private static String s(String v) { return v == null ? "" : v; }
    private static String val(String line) {
        int i = line.indexOf(':'); return i < 0 ? "" : line.substring(i + 1).trim();
    }
    private static String unquote(String s) {
        String t = s == null ? "" : s.trim();
        if ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1).replace("\\\"", "\"");
        }
        return t;
    }
    private static boolean bool(String s) {
        String t = s == null ? "" : s.trim();
        return t.equalsIgnoreCase("true") || t.equalsIgnoreCase("yes");
    }
    private static int toInt(String s, int def) {
        try { return Integer.parseInt(s.replaceAll("[^0-9-]", "")); } catch (Exception e) { return def; }
    }
    private static int leadingSpaces(String s) {
        int n = 0; for (int i=0;i<s.length();i++) { if (s.charAt(i)==' ') n++; else break; } return n;
    }
    private static Path resolvePath(String p) {
        Path x = Paths.get(p);
        return x.isAbsolute() ? x.normalize() : x.toAbsolutePath().normalize();
    }
}
