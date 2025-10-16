/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: OutboundSenderChannel.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Poll a source directory for HL7 files and deliver each file as an MLLP
 *      message to a configured remote host:port. On success, optionally move to
 *      an archive folder; on failure, move the file to an error folder.
 *
 *  Design:
 *      - Zero-dependency on GUI singletons; logs only.
 *      - Robust to different OutboundChannelConfig shapes:
 *          * Java record-style accessors: name(), sourceDir(), targetHost(), ...
 *          * JavaBean-style getters: getName(), getSourceDir(), getTargetHost(), ...
 *          * Public fields: name, sourceDir, targetHost, ...
 *        We resolve properties via reflection so you don’t need to rename your config.
 *
 *      - MLLP framing: <VT> + HL7 + <FS><CR>
 *      - ACK read until <FS><CR> or timeout.
 *
 *  Author: LocalBridge Team
 *  Version: 1.0 (Outbound)
 *  Date: 2025-10-15
 * =============================================================================
 */

package com.localbridge.outbound;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class OutboundSenderChannel implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OutboundSenderChannel.class);

    // MLLP control characters
    private static final byte VT = 0x0B;   // <VT> 0x0B
    private static final byte FS = 0x1C;   // <FS> 0x1C
    private static final byte CR = 0x0D;   // <CR> 0x0D

    private final Object cfg; // tolerant to any OutboundChannelConfig shape
    private final ScheduledExecutorService poller;
    private final ExecutorService senderPool;

    private volatile boolean running = false;

    public OutboundSenderChannel(Object config) {
        this.cfg = Objects.requireNonNull(config, "OutboundChannelConfig must not be null");

        int workers = getInt("concurrentSends", 1);
        if (workers < 1) workers = 1;

        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OutboundPoller-" + getNameSafe());
            t.setDaemon(true);
            return t;
        });
        int finalWorkers = workers;
        this.senderPool = Executors.newFixedThreadPool(finalWorkers, r -> {
            Thread t = new Thread(r, "OutboundSender-" + getNameSafe());
            t.setDaemon(true);
            return t;
        });
    }

    public String getName() {
        return getNameSafe();
    }

    public void start() throws IOException {
        Path sourceDir = getPath("sourceDir", true);
        ensureDirectory(sourceDir);

        Path archiveDir = getPath("archiveDir", false);
        if (archiveDir != null) ensureDirectory(archiveDir);

        Path errorDir = getPath("errorDir", false);
        if (errorDir != null) ensureDirectory(errorDir);

        running = true;
        long initialDelayMs = 0L;
        long periodMs = Math.max(200L, getLong("pollIntervalMs", 1000L));
        poller.scheduleWithFixedDelay(this::pollOnce, initialDelayMs, periodMs, TimeUnit.MILLISECONDS);

        log.info("[Outbound:{}] Started polling {} every {} ms => {}:{}",
                getNameSafe(),
                sourceDir,
                periodMs,
                getString("targetHost", "127.0.0.1"),
                getInt("targetPort", 2575));
    }

    public void stop() {
        running = false;
        poller.shutdownNow();
        senderPool.shutdown();
        try {
            if (!senderPool.awaitTermination(5, TimeUnit.SECONDS)) {
                senderPool.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            senderPool.shutdownNow();
        }
        log.info("[Outbound:{}] Stopped", getNameSafe());
    }

    @Override
    public void close() {
        stop();
    }

    /* -----------------------------------------------------------
     * Poll/send
     * ----------------------------------------------------------- */
    private void pollOnce() {
        if (!running) return;

        try {
            Path sourceDir = getPath("sourceDir", true);
            String filePattern = getString("filePattern", "*.hl7");
            List<Path> files = listMatchingFiles(sourceDir, filePattern);
            if (files.isEmpty()) return;

            for (Path f : files) {
                senderPool.submit(() -> {
                    try {
                        processFile(f);
                    } catch (Exception ex) {
                        log.error("[Outbound:{}] Unexpected error processing {}: {}",
                                getNameSafe(), f.getFileName(), ex.getMessage(), ex);
                        moveToError(f, ex);
                    }
                });
            }
        } catch (Exception e) {
            log.error("[Outbound:{}] Poll error: {}", getNameSafe(), e.getMessage(), e);
        }
    }

    private List<Path> listMatchingFiles(Path dir, String globPattern) throws IOException {
        String pat = (globPattern == null || globPattern.isBlank()) ? "*.hl7" : globPattern;
        PathMatcher matcher = dir.getFileSystem().getPathMatcher("glob:" + pat);
        List<Path> list = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && matcher.matches(p.getFileName())) {
                    list.add(p);
                }
            }
        }
        return list;
    }

    private void processFile(Path file) {
        byte[] hl7;
        try {
            hl7 = Files.readAllBytes(file);
        } catch (IOException io) {
            log.warn("[Outbound:{}] Unable to read {}: {}", getNameSafe(), file.getFileName(), io.getMessage());
            moveToError(file, io);
            return;
        }

        if (hl7.length == 0) {
            log.warn("[Outbound:{}] Empty file {}, moving to error.", getNameSafe(), file.getFileName());
            moveToError(file, new IOException("Empty HL7 file"));
            return;
        }

        try {
            String ack = sendMllp(hl7, Duration.ofMillis(getLong("socketTimeoutMs", 10000L)));
            log.info("[Outbound:{}] Sent {} ({} bytes) -> {}:{} | ACK: {}",
                    getNameSafe(), file.getFileName(), hl7.length,
                    getString("targetHost", "127.0.0.1"),
                    getInt("targetPort", 2575),
                    summarizeAck(ack));

            moveOnSuccess(file);
        } catch (Exception ex) {
            log.error("[Outbound:{}] Send failed for {} -> {}:{} : {}",
                    getNameSafe(), file.getFileName(),
                    getString("targetHost", "127.0.0.1"),
                    getInt("targetPort", 2575),
                    ex.getMessage());
            moveToError(file, ex);
        }
    }

    /* -----------------------------------------------------------
     * MLLP
     * ----------------------------------------------------------- */
    private String sendMllp(byte[] hl7, Duration timeout) throws IOException {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            timeout = Duration.ofSeconds(10);
        }

        String host = getString("targetHost", "127.0.0.1");
        int port = getInt("targetPort", 2575);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
            socket.setSoTimeout((int) timeout.toMillis());

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Write MLLP framed message
            out.write(VT);
            out.write(hl7);
            if (hl7[hl7.length - 1] != CR) { // ensure trailing CR before FS CR
                out.write(CR);
            }
            out.write(FS);
            out.write(CR);
            out.flush();

            // Read ACK until FS CR or timeout
            byte[] buf = new byte[8192];
            int read;
            ByteArrayOutputStreamEx ackBuffer = new ByteArrayOutputStreamEx(1024);

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout.toMillis());
            while (System.nanoTime() < deadline && (read = in.read(buf)) != -1) {
                ackBuffer.write(buf, 0, read);
                if (ackBuffer.endsWith(new byte[]{FS, CR})) break;
            }

            byte[] ackBytes = ackBuffer.toByteArray();
            int start = (ackBytes.length > 0 && ackBytes[0] == VT) ? 1 : 0;
            int end = ackBytes.length;
            if (end >= 2 && ackBytes[end - 2] == FS && ackBytes[end - 1] == CR) end -= 2;

            byte[] trimmed = (end > start) ? java.util.Arrays.copyOfRange(ackBytes, start, end) : new byte[0];
            return new String(trimmed, Charset.forName("UTF-8"));
        }
    }

    private String summarizeAck(String ack) {
        if (ack == null || ack.isBlank()) return "<empty>";
        int max = Math.min(80, ack.length());
        return ack.substring(0, max).replace('\r', '␍').replace('\n', '␊');
    }

    /* -----------------------------------------------------------
     * Post-processing
     * ----------------------------------------------------------- */
    private void moveOnSuccess(Path file) {
        try {
            Path archiveDir = getPath("archiveDir", false);
            if (archiveDir != null) {
                Files.createDirectories(archiveDir);
                Path target = archiveDir.resolve(file.getFileName());
                Files.move(file, target, REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(file);
            }
        } catch (IOException io) {
            log.warn("[Outbound:{}] Post-send move failed for {}: {}", getNameSafe(), file.getFileName(), io.getMessage());
        }
    }

    private void moveToError(Path file, Exception reason) {
        try {
            Path errorDir = getPath("errorDir", false);
            if (errorDir != null) {
                Files.createDirectories(errorDir);
                Path target = errorDir.resolve(file.getFileName());
                Files.move(file, target, REPLACE_EXISTING);

                Path sidecar = errorDir.resolve(file.getFileName().toString() + ".err.txt");
                String msg = reason == null ? "Unknown error" : (reason.getClass().getName() + ": " + reason.getMessage());
                Files.writeString(sidecar, msg);
            } else {
                log.warn("[Outbound:{}] Error dir not configured; leaving failed file in place: {}", getNameSafe(), file);
            }
        } catch (IOException io) {
            log.error("[Outbound:{}] Failed moving {} to error: {}", getNameSafe(), file.getFileName(), io.getMessage());
        }
    }

    private void ensureDirectory(Path dir) throws IOException {
        if (dir == null) return;
        if (!Files.exists(dir)) Files.createDirectories(dir);
        if (!Files.isDirectory(dir)) throw new IOException("Not a directory: " + dir);
    }

    /* -----------------------------------------------------------
     * Tolerant config accessors (record, bean, or field)
     * ----------------------------------------------------------- */
    private String getNameSafe() {
        return getString("name", "Outbound");
    }

    private String getString(String key, String defVal) {
        Object v = readProp(key);
        return v == null ? defVal : String.valueOf(v);
    }

    private int getInt(String key, int defVal) {
        Object v = readProp(key);
        if (v == null) return defVal;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return defVal; }
    }

    private long getLong(String key, long defVal) {
        Object v = readProp(key);
        if (v == null) return defVal;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (Exception e) { return defVal; }
    }

    private Path getPath(String key, boolean required) {
        Object v = readProp(key);
        if (v == null) {
            if (required) throw new IllegalStateException("Missing required config: " + key);
            return null;
        }
        if (v instanceof Path p) return p;
        return Paths.get(String.valueOf(v));
    }

    /**
     * Try, in order:
     *  - method: getKey()
     *  - method: key()
     *  - field:  key
     */
    private Object readProp(String key) {
        Class<?> c = cfg.getClass();
        String cap = key.substring(0, 1).toUpperCase() + key.substring(1);
        String[] methods = new String[]{"get" + cap, key};

        for (String m : methods) {
            try {
                Method mm = c.getMethod(m);
                return mm.invoke(cfg);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                log.debug("[Outbound:{}] Accessor {}() failed: {}", getNameSafe(), m, e.toString());
            }
        }
        try {
            Field f = c.getField(key);
            return f.get(cfg);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            log.debug("[Outbound:{}] Field {} access failed: {}", getNameSafe(), key, e.toString());
        }
        return null;
    }
 /**
     * Compatibility accessor for runtime and GUI components.
     * Returns the original configuration object passed to the constructor.
     */
    public Object getConfig() {
        return cfg;
    }
    /* Small utility to test ACK terminator efficiently */
    private static final class ByteArrayOutputStreamEx extends java.io.ByteArrayOutputStream {
        ByteArrayOutputStreamEx(int size) { super(size); }
        boolean endsWith(byte[] suffix) {
            if (count < suffix.length) return false;
            for (int i = 0; i < suffix.length; i++) {
                if (buf[count - suffix.length + i] != suffix[i]) return false;
            }
            return true;
        }
    }
}
