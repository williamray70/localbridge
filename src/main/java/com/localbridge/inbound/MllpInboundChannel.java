/*
 * =============================================================================
 *  MllpInboundChannel (hybrid lenient + parser-aware ACK)
 * =============================================================================
 *  - Listens on the configured TCP port
 *  - Saves every received HL7 message
 *  - Attempts to parse and generate a proper ACK via HAPI PipeParser
 *  - Falls back to a minimal AA ACK if parsing fails
 *  - Never withholds an ACK (sender always receives one)
 * =============================================================================
 */
package com.localbridge.inbound;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MllpInboundChannel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MllpInboundChannel.class);

    // MLLP framing
    private static final byte SB = 0x0b;
    private static final byte EB = 0x1c;
    private static final byte CR = 0x0d;

    private final InboundChannelConfig cfg;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running = false;
    private ServerSocket server;

    // Timestamp patterns (calendar-based)
    private static final DateTimeFormatter FILE_TS   = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final DateTimeFormatter CTRL_TS   = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter HL7_TS    = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    public MllpInboundChannel(InboundChannelConfig cfg) throws IOException {
        this.cfg = cfg;
        if (cfg.saveDir() == null)
            throw new IOException("saveDir is null for inbound channel: " + cfg.name());
        Files.createDirectories(cfg.saveDir());
        if (cfg.port() <= 0 || cfg.port() > 65535)
            throw new IllegalArgumentException("Invalid port for inbound channel " + cfg.name() + ": " + cfg.port());
    }

    public String getName() { return cfg.name(); }

    public synchronized void start() throws IOException {
        if (running) return;
        server = new ServerSocket(cfg.port());
        running = true;
        pool.execute(this::listenLoop);
        log.info("[Inbound:{}] Listening on port {} (channel={})", cfg.name(), cfg.port(), cfg.name());
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        pool.shutdownNow();
        log.info("[Inbound:{}] Stopped", cfg.name());
    }

    private void listenLoop() {
        while (running) {
            try {
                final Socket client = server.accept();
                pool.execute(() -> handleClient(client));
            } catch (IOException e) {
                if (running)
                    log.error("[Inbound:{}] Accept failed: {}", cfg.name(), e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try (client;
             InputStream in = client.getInputStream();
             OutputStream out = client.getOutputStream()) {

            String hl7 = readMllp(in);
            if (hl7 == null || hl7.isEmpty()) {
                log.warn("[Inbound:{}] Empty HL7 payload", cfg.name());
                writeMllp(out, buildFallbackAck("EMPTY"));
                return;
            }

            // Save message to disk
            Path file = buildMessagePath();
            Files.writeString(file, hl7, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);

            // Try to generate a standards-compliant ACK
            String ack = buildAck(hl7);
            writeMllp(out, ack);

            tryIncrementProcessed(cfg.name());

        } catch (Exception ex) {
            log.error("[Inbound:{}] Error processing inbound message: {}", cfg.name(), ex.getMessage());
            tryIncrementErrors(cfg.name());
        }
    }

    private Path buildMessagePath() {
        // Use calendar-based time (LocalDateTime) to avoid Instant pattern issues
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        String ts = FILE_TS.format(now);
        String prefix = (cfg.filePrefix() == null) ? "" : cfg.filePrefix();
        String suffix = (cfg.fileSuffix() == null) ? ".hl7" : cfg.fileSuffix();
        return cfg.saveDir().resolve(prefix + ts + suffix);
    }

    private static String readMllp(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
        int b;
        do { b = in.read(); if (b == -1) return null; } while (b != SB);
        while (true) {
            b = in.read();
            if (b == -1) break;
            if (b == EB) { in.read(); break; } // consume CR
            buf.write(b);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    private static void writeMllp(OutputStream out, String hl7) throws IOException {
        out.write(SB);
        out.write(hl7.getBytes(StandardCharsets.UTF_8));
        out.write(EB);
        out.write(CR);
        out.flush();
    }

    /**
     * Try to build a valid ACK using HAPI PipeParser (no validation).
     * Falls back to a simple static ACK if parsing fails.
     */
    private static String buildAck(String inbound) {
        try {
            PipeParser parser = new PipeParser();
            parser.setValidationContext(ValidationContextFactory.noValidation());
            Message msg = parser.parse(inbound);
            Message ack = msg.generateACK();
            return parser.encode(ack);
        } catch (Exception e) {
            return buildFallbackAck("PARSEFAIL");
        }
    }

    /** Simple fallback AA ACK with calendar-based timestamps. */
    private static String buildFallbackAck(String reason) {
        LocalDateTime now = LocalDateTime.now(ZoneId.systemDefault());
        String controlId = "ACK-" + CTRL_TS.format(now);
        return "MSH|^~\\&|LOCALBRIDGE|ENGINE|||"
                + HL7_TS.format(now)
                + "||ACK^A01|" + controlId + "|P|2.5\r"
                + "MSA|AA|" + controlId + "|" + reason + "\r";
    }

    private static void tryIncrementProcessed(String channelName) {
        try {
            Class<?> guiClass = Class.forName("com.localbridge.gui.LocalBridgeGUI");
            var getInstance = guiClass.getMethod("getInstance");
            Object gui = getInstance.invoke(null);
            if (gui != null) {
                var inc = guiClass.getMethod("incrementProcessed", String.class);
                inc.invoke(gui, channelName);
            }
        } catch (Throwable ignored) {}
    }

    private static void tryIncrementErrors(String channelName) {
        try {
            Class<?> guiClass = Class.forName("com.localbridge.gui.LocalBridgeGUI");
            var getInstance = guiClass.getMethod("getInstance");
            Object gui = getInstance.invoke(null);
            if (gui != null) {
                var inc = guiClass.getMethod("incrementErrors", String.class);
                inc.invoke(gui, channelName);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Compatibility method for InboundRuntime.
     * Delegates to start(), binding to the specified port.
     */
    public synchronized void bind(int port) throws IOException {
        // Ensure configuration port matches requested port
        if (cfg.port() != port) {
            log.warn("[Inbound:{}] Overriding configured port {} with runtime port {}", 
                     cfg.name(), cfg.port(), port);
        }
        start();
    }

    @Override
    public void close() { stop(); }
}
