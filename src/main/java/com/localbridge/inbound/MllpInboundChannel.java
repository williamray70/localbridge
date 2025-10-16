/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: MllpInboundChannel.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Inbound MLLP listener with per-channel processed/error counters for GUI.
 *      Minimal, compatible implementation: bind(int), start/close, counters.
 *      (If your project uses a richer ACK/parse flow, keep that logic and
 *       add the same counters + accessors shown below.)
 * =============================================================================
 */
package com.localbridge.inbound;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MllpInboundChannel {

    private final InboundChannelConfig cfg;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    private final AtomicInteger processedCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    public MllpInboundChannel(InboundChannelConfig config) {
        this.cfg = config;
    }

    /** Compatibility for InboundRuntime; binds to cfg.port(). */
    public synchronized void bind(int port) throws IOException {
        if (cfg.port() != port) {
            // proceed; cfg.port() will be used for actual bind
        }
        start();
    }

    /** Start listener. */
    public synchronized void start() throws IOException {
        if (running) return;
        serverSocket = new ServerSocket(cfg.port());
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MLLP-Inbound-" + cfg.port());
            t.setDaemon(true);
            return t;
        });
        running = true;
        executor.submit(this::acceptLoop);
        log("Bound to port " + cfg.port() + " (" + cfg.getName() + ")");
    }

    /** Graceful shutdown. */
    public synchronized void close() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignore) {}
        try { if (executor != null) executor.shutdownNow(); } catch (Exception ignore) {}
        log("Closed listener for " + cfg.getName());
    }

    private void acceptLoop() {
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                log("Inbound connection accepted from " + socket.getInetAddress());
                handleIncoming(socket);
                processedCount.incrementAndGet();
            } catch (IOException e) {
                if (running) {
                    errorCount.incrementAndGet();
                    log("Socket error: " + e.getMessage());
                }
            }
        }
    }

    // Stub — replace with your HAPI/ACK pipeline if present.
    private void handleIncoming(Socket socket) {
        try { Thread.sleep(50); } catch (InterruptedException ignore) {}
    }

    public boolean isRunning() { return running; }
    public InboundChannelConfig getConfig() { return cfg; }

    public int getProcessedCount() { return processedCount.get(); }
    public int getErrorCount() { return errorCount.get(); }

    private static void log(String msg) {
        System.out.println("[InboundChannel] " + msg);
    }
}
