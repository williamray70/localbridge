/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: ChannelStatsStore.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Tiny JSON-backed store for persisting per-channel processed/error counts.
 *      - File: conf/channel-stats.json (created on first save)
 *      - API:  load(), get(name), put(name, processed, errors), flush()
 *      - Thread-safe; cheap reads; flushes on each update call from engines.
 * =============================================================================
 */
package com.localbridge.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class ChannelStatsStore {
    private static final Logger log = LoggerFactory.getLogger(ChannelStatsStore.class);

    private static final Path DEFAULT_PATH = Paths.get("conf", "channel-stats.json");

    private static volatile ChannelStatsStore INSTANCE;

    public static ChannelStatsStore getInstance() {
        if (INSTANCE == null) {
            synchronized (ChannelStatsStore.class) {
                if (INSTANCE == null) INSTANCE = new ChannelStatsStore(DEFAULT_PATH);
            }
        }
        return INSTANCE;
    }

    // ---- instance ----
    private final Path file;
    private final Object lock = new Object();
    // name -> [processed, errors]
    private Map<String, long[]> counts = new HashMap<>();

    private ChannelStatsStore(Path file) {
        this.file = file;
        load();
    }

    /** Reload from disk (best-effort). */
    public void load() {
        synchronized (lock) {
            counts.clear();
            try {
                if (Files.exists(file)) {
                    String json = Files.readString(file, StandardCharsets.UTF_8).trim();
                    // minimal JSON parser for a map: {"Channel":{"processed":X,"errors":Y},...}
                    if (!json.isEmpty() && json.startsWith("{")) {
                        parseInto(json, counts);
                    }
                }
            } catch (Exception e) {
                log.warn("Stats reload failed: {}", e.getMessage());
                counts.clear();
            }
        }
    }

    /** Return immutable snapshot of all counts. */
    public Map<String, long[]> snapshot() {
        synchronized (lock) {
            Map<String, long[]> out = new HashMap<>();
            counts.forEach((k, v) -> out.put(k, new long[]{v[0], v[1]}));
            return Collections.unmodifiableMap(out);
        }
    }

    /** Get counts for channel (processed, errors). Returns {0,0} if missing. */
    public long[] get(String channelName) {
        synchronized (lock) {
            long[] v = counts.get(channelName);
            return v == null ? new long[]{0L, 0L} : new long[]{v[0], v[1]};
        }
    }

    /** Put/replace counts for channel and flush to disk. */
    public void putAndFlush(String channelName, long processed, long errors) {
        synchronized (lock) {
            counts.put(channelName, new long[]{processed, errors});
            flushLocked();
        }
    }

    /** Update only processed value and flush. */
    public void updateProcessedAndFlush(String channelName, long processed, long errors) {
        synchronized (lock) {
            counts.put(channelName, new long[]{processed, errors});
            flushLocked();
        }
    }

    /** Update only error value and flush. */
    public void updateErrorsAndFlush(String channelName, long processed, long errors) {
        synchronized (lock) {
            counts.put(channelName, new long[]{processed, errors});
            flushLocked();
        }
    }

    /** Force-write current in-memory counts to disk. */
    public void flush() {
        synchronized (lock) {
            flushLocked();
        }
    }

    // ---- internals ----

    private void flushLocked() {
        try {
            Files.createDirectories(file.getParent());
            String json = toJson(counts);
            Files.writeString(file, json, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            log.warn("Stats flush failed: {}", e.getMessage());
        }
    }

    private static void parseInto(String json, Map<String, long[]> out) {
        // Super-lightweight parse; assumes flat object: {"A":{"processed":1,"errors":2}, ...}
        // Not a general JSON parser—kept tiny to avoid dependencies.
        String s = json.trim();
        if (s.length() < 2) return;
        s = s.substring(1, s.length() - 1).trim(); // remove outer { }
        if (s.isEmpty()) return;

        // split top-level by "},"
        int idx = 0;
        while (idx < s.length()) {
            // key
            int q1 = s.indexOf('"', idx);
            if (q1 < 0) break;
            int q2 = s.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            String key = s.substring(q1 + 1, q2);
            int colon = s.indexOf(':', q2 + 1);
            if (colon < 0) break;
            int braceStart = s.indexOf('{', colon + 1);
            if (braceStart < 0) break;
            int braceEnd = findMatchingBrace(s, braceStart);
            if (braceEnd < 0) break;
            String obj = s.substring(braceStart + 1, braceEnd).trim();
            long processed = 0, errors = 0;

            // parse "processed":N,"errors":M (order-insensitive)
            String[] parts = obj.split(",");
            for (String p : parts) {
                String t = p.trim();
                if (t.startsWith("\"processed\"")) {
                    int c = t.indexOf(':');
                    if (c > 0) processed = parseLongSafe(t.substring(c + 1).trim());
                } else if (t.startsWith("\"errors\"")) {
                    int c = t.indexOf(':');
                    if (c > 0) errors = parseLongSafe(t.substring(c + 1).trim());
                }
            }
            out.put(key, new long[]{processed, errors});

            // move idx
            idx = braceEnd + 1;
            // skip comma if present
            if (idx < s.length() && s.charAt(idx) == ',') idx++;
        }
    }

    private static int findMatchingBrace(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static long parseLongSafe(String t) {
        try { return Long.parseLong(t.replaceAll("[^0-9-]", "")); }
        catch (Exception e) { return 0L; }
    }

    private static String toJson(Map<String, long[]> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, long[]> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            String name = e.getKey().replace("\"", "\\\"");
            long[] v = e.getValue();
            long p = v != null && v.length > 0 ? v[0] : 0L;
            long er = v != null && v.length > 1 ? v[1] : 0L;
            sb.append("\"").append(name).append("\":{\"processed\":").append(p).append(",\"errors\":").append(er).append("}");
        }
        sb.append("}");
        return sb.toString();
    }
}
