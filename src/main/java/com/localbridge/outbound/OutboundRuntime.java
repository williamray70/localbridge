/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: OutboundRuntime.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Singleton runtime responsible for managing outbound sender channels.
 *      Reads configuration YAMLs under:
 *          conf/channels/Outbound/
 *
 *      Responsibilities:
 *        • Load outbound channel configurations.
 *        • Start enabled outbound sender threads.
 *        • Stop all running senders cleanly.
 *        • Provide runtime status to the GUI.
 *
 *  Notes:
 *      Mirrors InboundRuntime for GUI compatibility.
 *      Uses SLF4J logging and safe concurrent collections.
 *
 *  Author : William Ray / LocalBridge Health
 *  Version: 1.7.3 – October 2025
 * =============================================================================
 */

package com.localbridge.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime controller for outbound MLLP sender channels.
 * Each outbound channel monitors a directory and sends messages
 * to remote endpoints defined in its YAML configuration.
 */
public final class OutboundRuntime {

    // -------------------------------------------------------------------------
    // Singleton
    // -------------------------------------------------------------------------
    private static final Logger log = LoggerFactory.getLogger(OutboundRuntime.class);
    private static final OutboundRuntime INSTANCE = new OutboundRuntime();

    public static OutboundRuntime get() { return INSTANCE; }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private final Map<String, OutboundSenderChannel> runningChannels = new ConcurrentHashMap<>();

    private OutboundRuntime() {
        log.info("[Outbound] Runtime initialized.");
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Load outbound YAML configurations and start enabled sender channels.
     * @param channelsRoot Path to conf/channels directory.
     */
    public synchronized void loadAndStart(Path channelsRoot) throws IOException {
        stopAll();

        OutboundYamlLoader loader = new OutboundYamlLoader();
        var configs = loader.loadFromDirectory(channelsRoot);

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
            try {
                OutboundSenderChannel sender = new OutboundSenderChannel(cfg);
                sender.start();
                runningChannels.put(cfg.getName(), sender);
                log.info("[Outbound] Started {}", cfg.getName());
            } catch (Exception e) {
                log.error("[Outbound] Failed to start {}: {}", cfg.getName(), e.getMessage(), e);
            }
        }

        if (runningChannels.isEmpty()) {
            log.info("[Outbound] No outbound senders currently active.");
        } else {
            log.info("[Outbound] {} sender(s) running: {}", runningChannels.size(),
                    String.join(", ", runningChannels.keySet()));
        }
    }

    /**
     * Stop all running outbound senders.
     */
    public synchronized void stopAll() {
        if (runningChannels.isEmpty()) {
            log.info("[Outbound] No senders to stop.");
            return;
        }
        for (OutboundSenderChannel ch : runningChannels.values()) {
            try {
                ch.close();
                log.info("[Outbound] Stopped {}", ch.getConfig() != null ? ch.getConfig().toString() : "<unknown>");
            } catch (Exception e) {
                log.error("[Outbound] Error stopping sender: {}", e.getMessage());
            }
        }
        runningChannels.clear();
        log.info("[Outbound] All outbound senders stopped.");
    }

    // -------------------------------------------------------------------------
    // Introspection
    // -------------------------------------------------------------------------

    /**
     * Returns the names of all currently running outbound channels
     * as a List<String> for GUI compatibility.
     */
    public synchronized List<String> getRunningNames() {
        return new ArrayList<>(runningChannels.keySet());
    }

    /**
     * Returns the number of currently active outbound sender channels.
     */
    public synchronized int getRunningCount() {
        return runningChannels.size();
    }

    @Override
    public String toString() {
        return "OutboundRuntime{running=" + runningChannels.keySet() + "}";
    }
}
