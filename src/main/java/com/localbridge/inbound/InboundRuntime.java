/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: InboundRuntime.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Manage inbound MLLP listener channels with per-channel counters.
 * =============================================================================
 */
package com.localbridge.inbound;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class InboundRuntime {
    private static final InboundRuntime INSTANCE = new InboundRuntime();
    public static InboundRuntime get() { return INSTANCE; }

    private final Map<String, InboundChannelConfig> configsByName = new ConcurrentHashMap<>();
    private final Map<String, MllpInboundChannel> running = new ConcurrentHashMap<>();

    private InboundRuntime() {}

    public synchronized void loadAndStart(Path channelsRoot) throws IOException {
        stopAll();

        InboundYamlLoader loader = new InboundYamlLoader();
        List<InboundChannelConfig> configs = loader.loadFromDirectory(channelsRoot);
        configsByName.clear();
        for (InboundChannelConfig c : configs) {
            configsByName.put(c.getName(), c);
        }
        for (InboundChannelConfig cfg : configs) {
            if (!isEnabled(cfg)) continue;
            startChannel(cfg.getName());
        }
    }

    public synchronized void startChannel(String name) throws IOException {
        if (running.containsKey(name)) return;
        InboundChannelConfig cfg = configsByName.get(name);
        if (cfg == null) throw new IOException("Inbound config not found: " + name);
        MllpInboundChannel ch = new MllpInboundChannel(cfg);
        ch.bind(getPort(cfg));
        running.put(name, ch);
    }

    public synchronized void stopChannel(String name) {
        MllpInboundChannel ch = running.remove(name);
        if (ch != null) {
            try { ch.close(); } catch (Exception ignore) {}
        }
    }

    public synchronized void stopAll() {
        for (MllpInboundChannel ch : running.values()) {
            try { ch.close(); } catch (Exception ignore) {}
        }
        running.clear();
    }

    // GUI helpers
    public synchronized List<String> getRunningNames() { return new ArrayList<>(running.keySet()); }
    public synchronized boolean isRunning(String name) { return running.containsKey(name); }
    public synchronized List<String> getAllNames() { return new ArrayList<>(configsByName.keySet()); }
    public synchronized int getProcessed(String name) {
        MllpInboundChannel ch = running.get(name);
        return ch == null ? 0 : ch.getProcessedCount();
    }
    public synchronized int getErrors(String name) {
        MllpInboundChannel ch = running.get(name);
        return ch == null ? 0 : ch.getErrorCount();
    }

    // --- helpers ---
    private static boolean isEnabled(InboundChannelConfig cfg) {
        try { return (boolean) cfg.getClass().getMethod("enabled").invoke(cfg); }
        catch (Exception ignore) {
            try { return (Boolean) cfg.getClass().getMethod("isEnabled").invoke(cfg); }
            catch (Exception e) { return true; }
        }
    }
    private static int getPort(InboundChannelConfig cfg) {
        try { return (int) cfg.getClass().getMethod("port").invoke(cfg); }
        catch (Exception ignore) {
            try { return ((Number) cfg.getClass().getMethod("getPort").invoke(cfg)).intValue(); }
            catch (Exception e) { return 2575; }
        }
    }
}
