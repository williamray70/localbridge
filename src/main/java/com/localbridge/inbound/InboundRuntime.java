package com.localbridge.inbound;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Singleton manager to start/stop inbound MLLP listeners.
 * Loads from conf/channels/Inbound/.
 */
public final class InboundRuntime {
    private static final InboundRuntime INSTANCE = new InboundRuntime();

    public static InboundRuntime get() { return INSTANCE; }

    private final Map<String, MllpInboundChannel> running = new ConcurrentHashMap<>();

    private InboundRuntime() {}

    /** Stop existing, load from <root>/Inbound, and start enabled channels. */
    public synchronized void loadAndStart(Path channelsRoot) throws IOException {
        stopAll();

        InboundYamlLoader loader = new InboundYamlLoader();
        List<InboundChannelConfig> configs = loader.loadFromDirectory(channelsRoot);
        log("[Inbound] config folder: " + channelsRoot);

        for (InboundChannelConfig cfg : configs) {
            if (!isEnabled(cfg)) continue;
            MllpInboundChannel ch = new MllpInboundChannel(cfg);
            try {
                ch.bind(getPort(cfg));
                running.put(cfg.getName(), ch);
                log("[Inbound] Started " + cfg.getName() + " on port " + getPort(cfg));
            } catch (Exception e) {
                log("[Inbound] Failed to start " + cfg.getName() + ": " + e.getMessage());
            }
        }
    }

    public synchronized void stopAll() {
        for (MllpInboundChannel ch : running.values()) {
            try { ch.close(); } catch (Exception ignore) {}
        }
        running.clear();
    }

    /** Names of currently running inbound channels (for GUI logging). */
    public synchronized List<String> getRunningNames() {
        return new ArrayList<>(running.keySet());
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

    private static void log(String msg) {
        System.out.println(msg);
    }
}
