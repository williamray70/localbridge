/*
 * =============================================================================
 *  InboundEngineManager
 *  -----------------------------------------------------------------------------
 *  Purpose : Start/stop and reload inbound MLLP listeners defined by YAML files
 *            under conf/channels/Inbound/. Runs each listener on a background
 *            (daemon) thread and logs concise lifecycle messages.
 *
 *  Why this exists:
 *    - Your project referenced an earlier InboundEngineManager that expected
 *      different getter names (enabled(), name(), port()) and a blocking start().
 *      This version tolerates bean/record styles and avoids blocking the FX UI.
 *
 *  Key behavior:
 *    - loadAndStart(Path root)  -> reads {root}/Inbound, starts enabled channels
 *    - reload(Path root)        -> stopAll(); then loadAndStart(root)
 *    - stopAll()                -> gracefully shuts down listeners
 *
 *  Compatibility notes:
 *    - Accepts config getters: enabled()/isEnabled()/getEnabled(),
 *      name()/getName(), port()/getPort().
 *    - Accepts start methods: startAsync()/startServerAsync()/start()/startServer()/run().
 *      (All invoked off the FX thread.)
 *
 *  License : LocalBridge LLC (internal)
 *  Author  : LocalBridge Engineering
 *  Since   : 1.0
 * =============================================================================
 */
/*
 * =============================================================================
 *  InboundEngineManager
 * =============================================================================
 *  Starts/stops inbound MLLP listeners defined under conf/channels/Inbound/.
 *  This version prefers calling a concrete startServer() to ensure the socket
 *  is actually bound. It logs which start method was invoked and runs listeners
 *  on daemon threads so the GUI stays responsive.
 * =============================================================================
 */
package com.localbridge.inbound;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class InboundEngineManager {

    private final InboundYamlLoader loader = new InboundYamlLoader();

    private final List<MllpInboundChannel> running = new ArrayList<>();
    private final Map<MllpInboundChannel, Future<?>> futures = new ConcurrentHashMap<>();
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "Inbound-Listener");
        t.setDaemon(true);
        return t;
    });

    public synchronized void loadAndStart(Path channelsRoot) throws IOException {
        stopAll();

        List<InboundChannelConfig> configs = loader.loadFromDirectory(channelsRoot);
        log("Loaded " + configs.size() + " inbound config(s) from " + channelsRoot.resolve("Inbound"));

        for (InboundChannelConfig cfg : configs) {
            if (!isEnabled(cfg)) {
                log("Skipping disabled inbound: " + getName(cfg));
                continue;
            }
            final String name = getName(cfg);
            final int port = getPort(cfg);

            MllpInboundChannel ch = new MllpInboundChannel(cfg);
            Future<?> f = exec.submit(() -> {
                try {
                    String used = invokeStartPreferringStartServer(ch);
                    log("Started " + name + " on port " + port + " via " + used);
                } catch (Throwable t) {
                    log("Failed to start inbound " + name + ": " + t.getMessage());
                }
            });

            running.add(ch);
            futures.put(ch, f);
        }

        if (running.isEmpty()) {
            log("No inbound listeners started (none enabled).");
        }
    }

    public synchronized void loadAndStartDefault() throws IOException {
        loadAndStart(Paths.get("conf", "channels"));
    }

    public synchronized void reload(Path channelsRoot) throws IOException {
        log("Reloading inbound listeners...");
        loadAndStart(channelsRoot);
    }

    public synchronized void stopAll() {
        for (MllpInboundChannel ch : running) {
            try { invokeStop(ch); } catch (Exception ex) { log("Error stopping inbound: " + ex.getMessage()); }
            Future<?> f = futures.remove(ch);
            if (f != null) f.cancel(true);
        }
        running.clear();
    }

    // ---------- reflection helpers ----------

    private static boolean isEnabled(InboundChannelConfig cfg) {
        try { return (boolean) cfg.getClass().getMethod("enabled").invoke(cfg); }
        catch (Exception ignore) {
            try { return (boolean) cfg.getClass().getMethod("isEnabled").invoke(cfg); }
            catch (Exception ignore2) {
                try { return (boolean) cfg.getClass().getMethod("getEnabled").invoke(cfg); }
                catch (Exception ignore3) { return true; }
            }
        }
    }

    private static String getName(InboundChannelConfig cfg) {
        try { return (String) cfg.getClass().getMethod("name").invoke(cfg); }
        catch (Exception ignore) {
            try { return (String) cfg.getClass().getMethod("getName").invoke(cfg); }
            catch (Exception ignore2) { return "Inbound"; }
        }
    }

    private static int getPort(InboundChannelConfig cfg) {
        try { return (int) cfg.getClass().getMethod("port").invoke(cfg); }
        catch (Exception ignore) {
            try { return ((Number) cfg.getClass().getMethod("getPort").invoke(cfg)).intValue(); }
            catch (Exception ignore2) { return 2575; }
        }
    }

    /** Prefer a concrete startServer(); then start(); then startAsync()/startServerAsync(); then run(). Returns the method name used. */
    private static String invokeStartPreferringStartServer(MllpInboundChannel ch) throws Exception {
        Method m;

        // 1) Prefer startServer()
        m = findNoArg(ch, "startServer");
        if (m != null) { m.invoke(ch); return m.getName(); }

        // 2) Fallback to start()
        m = findNoArg(ch, "start");
        if (m != null) { m.invoke(ch); return m.getName(); }

        // 3) Async variants
        m = findNoArg(ch, "startServerAsync", "startAsync");
        if (m != null) { m.invoke(ch); return m.getName(); }

        // 4) Last resort: run()
        m = findNoArg(ch, "run");
        if (m != null) { m.invoke(ch); return m.getName(); }

        throw new IllegalStateException("No supported start method on " + ch.getClass().getSimpleName());
    }

    private static void invokeStop(MllpInboundChannel ch) {
        for (String n : new String[]{"stop", "stopServer", "close", "shutdown"}) {
            try {
                Method m = ch.getClass().getMethod(n);
                m.setAccessible(true);
                m.invoke(ch);
                return;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                throw new RuntimeException("Failed invoking " + n + " on " + ch.getClass().getSimpleName() + ": " + e.getMessage(), e);
            }
        }
    }

    private static Method findNoArg(Object target, String... names) {
        for (String name : names) {
            try {
                Method m = target.getClass().getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignore) { }
        }
        return null;
    }

    private static void log(String msg) {
        System.out.println("[Inbound] " + msg);
    }
}
