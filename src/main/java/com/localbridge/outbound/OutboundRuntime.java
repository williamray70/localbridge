/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: OutboundRuntime.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Manage outbound sender channels with per-channel counters.
 * =============================================================================
 */
package com.localbridge.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class OutboundRuntime {
    private static final Logger log = LoggerFactory.getLogger(OutboundRuntime.class);
    private static final OutboundRuntime INSTANCE = new OutboundRuntime();
    public static OutboundRuntime get() { return INSTANCE; }

    private final Map<String, OutboundChannelConfig> configsByName = new ConcurrentHashMap<>();
    private final Map<String, OutboundSenderChannel> runningChannels = new ConcurrentHashMap<>();

    private OutboundRuntime() {
        log.info("[Outbound] Runtime initialized.");
    }

    public synchronized void loadAndStart(Path channelsRoot) throws IOException {
        stopAll();

        OutboundYamlLoader loader = new OutboundYamlLoader();
        var configs = loader.loadFromDirectory(channelsRoot);
        configsByName.clear();
        for (OutboundChannelConfig c : configs) {
            configsByName.put(c.getName(), c);
        }

        if (configs.isEmpty()) {
            log.info("[Outbound] No outbound channels found under {}",
                    channelsRoot.resolve(OutboundYamlLoader.OUTBOUND_SUBDIR));
            return;
        }

        for (OutboundChannelConfig cfg : configs) {
            if (!cfg.isEnabled()) {
                log.info("[Outbound] Skipping disabled channel {}", cfg.getName());
                continue;
            }
            startChannel(cfg.getName());
        }

        if (runningChannels.isEmpty()) {
            log.info("[Outbound] No outbound senders currently active.");
        } else {
            log.info("[Outbound] {} sender(s) running: {}", runningChannels.size(),
                    String.join(", ", runningChannels.keySet()));
        }
    }

    public synchronized void startChannel(String name) throws IOException {
        if (runningChannels.containsKey(name)) return;
        OutboundChannelConfig cfg = configsByName.get(name);
        if (cfg == null) throw new IOException("Outbound config not found: " + name);
        OutboundSenderChannel sender = new OutboundSenderChannel(cfg);
        sender.start();
        runningChannels.put(name, sender);
        log.info("[Outbound] Started {}", name);
    }

    public synchronized void stopChannel(String name) {
        OutboundSenderChannel ch = runningChannels.remove(name);
        if (ch != null) {
            try {
                ch.close();
                log.info("[Outbound] Stopped {}", name);
            } catch (Exception e) {
                log.error("[Outbound] Error stopping {}: {}", name, e.getMessage());
            }
        }
    }

    public synchronized void stopAll() {
        for (OutboundSenderChannel ch : runningChannels.values()) {
            try { ch.close(); } catch (Exception ignore) {}
        }
        runningChannels.clear();
        log.info("[Outbound] All outbound senders stopped.");
    }

    // GUI helpers
    public synchronized List<String> getRunningNames() { return new ArrayList<>(runningChannels.keySet()); }
    public synchronized boolean isRunning(String name) { return runningChannels.containsKey(name); }
    public synchronized List<String> getAllNames() { return new ArrayList<>(configsByName.keySet()); }
    public synchronized int getProcessed(String name) {
        OutboundSenderChannel ch = runningChannels.get(name);
        return ch == null ? 0 : ch.getProcessedCount();
    }
    public synchronized int getErrors(String name) {
        OutboundSenderChannel ch = runningChannels.get(name);
        return ch == null ? 0 : ch.getErrorCount();
    }

    @Override
    public String toString() {
        return "OutboundRuntime{running=" + runningChannels.keySet() + "}";
    }
}
