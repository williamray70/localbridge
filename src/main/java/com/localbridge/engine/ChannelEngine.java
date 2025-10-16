/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: ChannelEngine.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Runs a single channel: polls input dir, transforms, writes outputs,
 *      persists processed/error counts.
 *      Now supports writing to MULTIPLE DESTINATIONS (filesystem).
 * =============================================================================
 */
package com.localbridge.engine;

import com.localbridge.transformer.HL7Transformer;
import com.localbridge.transformer.TransformContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.parser.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class ChannelEngine implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ChannelEngine.class);

    private final ChannelConfig config;
    private final HL7Transformer transformer;
    private final Parser parser = new GenericParser();

    // persisted counts
    private final AtomicInteger processedCount = new AtomicInteger();
    private final AtomicInteger errorCount = new AtomicInteger();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;

    private final ChannelStatsStore stats = ChannelStatsStore.getInstance();

    public ChannelEngine(ChannelConfig config) {
        this.config = config;

        try {
            this.transformer = ChannelEngineFactory.createTransformer(config);
        } catch (Exception e) {
            throw new IllegalStateException("Failed creating transformer for channel '" + config.getName() + "'", e);
        }

        long[] loaded = stats.get(config.getName());
        processedCount.set((int) Math.min(Integer.MAX_VALUE, Math.max(0, loaded[0])));
        errorCount.set((int) Math.min(Integer.MAX_VALUE, Math.max(0, loaded[1])));
        stats.putAndFlush(config.getName(), processedCount.get(), errorCount.get());
        log.debug("Loaded stats for [{}]: processed={}, errors={}", config.getName(), processedCount.get(), errorCount.get());
    }

    public void reloadCountsFromStore() {
        long[] loaded = stats.get(config.getName());
        processedCount.set((int) Math.min(Integer.MAX_VALUE, Math.max(0, loaded[0])));
        errorCount.set((int) Math.min(Integer.MAX_VALUE, Math.max(0, loaded[1])));
        log.info("Reloaded stats for [{}]: processed={}, errors={}", config.getName(), processedCount.get(), errorCount.get());
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            worker = new Thread(this, "ChannelEngine-" + config.getName());
            worker.start();
            log.info("Starting channel: {}", config.getName());
        }
    }

    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping channel: {}", config.getName());
        }
    }

    public boolean isRunning() { return running.get(); }
    public int getProcessedCount() { return processedCount.get(); }
    public int getErrorCount() { return errorCount.get(); }

    @Override
    public void run() {
        while (running.get()) {
            try {
                pollInputDirectory();
                Thread.sleep(config.getPollIntervalMs());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ex) {
                log.error("Channel loop error [{}]: {}", config.getName(), ex.getMessage(), ex);
            }
        }
        log.info("Channel stopped: {}", config.getName());
    }

    private void pollInputDirectory() {
        Path inDir  = config.getInputDir();
        if (inDir == null || !Files.isDirectory(inDir)) return;

        String pattern = config.getInputPattern() == null ? "*.hl7" : config.getInputPattern();
        int limit = Math.max(1, config.getBatchSize());

        int count = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(inDir, pattern)) {
            for (Path p : ds) {
                if (!running.get()) break;
                processFile(p);
                if (++count >= limit) break;
            }
        } catch (IOException e) {
            log.error("Directory scan failed [{}]: {}", inDir, e.getMessage(), e);
        }
    }

    private void processFile(Path file) {
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);

            Message in = parser.parse(raw);
            TransformContext ctx = new TransformContext(
                    config.getName(),
                    file.getFileName().toString(),
                    file,
                    Instant.now()
            );
            Message out = transformer.transform(in, ctx);

            String encoded = parser.encode(out);

            // Write to ALL destinations
            List<Path> dests = new ArrayList<>(config.getDestinationDirs());
            if (dests.isEmpty() && config.getOutputDir() != null) {
                dests.add(config.getOutputDir());
            }
            List<String> failures = new ArrayList<>();
            for (Path d : dests) {
                try {
                    Files.createDirectories(d);
                    Files.writeString(d.resolve(file.getFileName()), encoded, StandardCharsets.UTF_8);
                } catch (Exception wex) {
                    failures.add(d + " -> " + wex.getMessage());
                }
            }

            if (!failures.isEmpty()) {
                // Fail the whole transaction so the input goes to error flow
                throw new IllegalStateException("One or more destinations failed: " + String.join(" | ", failures));
            }

            // Archive or delete input
            if (config.isArchiveEnabled()) {
                Path archDir = config.getArchiveDir();
                if (archDir != null) {
                    Files.createDirectories(archDir);
                    Files.move(file, archDir.resolve(file.getFileName()), REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(file);
                }
            } else {
                Files.deleteIfExists(file);
            }

            int newProcessed = processedCount.incrementAndGet();
            stats.updateProcessedAndFlush(config.getName(), newProcessed, errorCount.get());
            log.debug("Processed [{}]: {} (processed={}, errors={})",
                    config.getName(), file.getFileName(), newProcessed, errorCount.get());

        } catch (Exception ex) {
            int newErrors = errorCount.incrementAndGet();
            stats.updateErrorsAndFlush(config.getName(), processedCount.get(), newErrors);
            log.error("Processing failed [{}]: {} -> {}",
                    config.getName(), file.getFileName(), ex.getMessage(), ex);

            saveErrorArtifacts(file, ex);

            try {
                if (config.getErrorDir() != null) {
                    Files.createDirectories(config.getErrorDir());
                    Files.move(file, config.getErrorDir().resolve(file.getFileName()), REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(file);
                }
            } catch (Exception moveEx) {
                log.error("Failed moving errored file [{}]: {}", file, moveEx.getMessage());
            }
        }
    }

    private void saveErrorArtifacts(Path original, Exception ex) {
        try {
            if (config.getErrorDir() == null) return;
            Files.createDirectories(config.getErrorDir());

            String base = original.getFileName().toString();
            Path errTxt = config.getErrorDir().resolve(base + ".error.txt");
            String msg = "Channel: " + config.getName() + "\n"
                       + "File   : " + base + "\n"
                       + "When   : " + Instant.now() + "\n"
                       + "Error  : " + ex.getClass().getName() + " - " + ex.getMessage() + "\n";
            Files.writeString(errTxt, msg, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception e) {
            log.error("Failed writing error artifact: {}", e.getMessage());
        }
    }
}
