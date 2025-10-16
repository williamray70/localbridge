/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: ChannelConfig.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Channel configuration DTO.
 *      - Supports multiple destinations.
 *      - Backward compatible: legacy setters/no-arg ctor for existing GUI code.
 * =============================================================================
 */
package com.localbridge.engine;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class ChannelConfig {

    // core
    private String name;
    private boolean enabled = true;

    // dirs
    private Path inputDir;
    private Path outputDir;   // legacy single destination (mirrors first destination if present)
    private Path errorDir;
    private Path archiveDir;

    // polling
    private String inputPattern = "*.hl7";
    private int pollIntervalMs = 1000;
    private int batchSize = 10;

    // transformer
    private String transformerType = "wrapi";   // "wrapi" or "java"
    private String transformerScript;           // for wrapi
    private String transformerClass;            // for java
    private boolean createMissing = true;
    private boolean validateProfile = false;
    private Map<String, Object> transformerConfig = new HashMap<>();

    // error handling
    private int retryCount = 3;
    private int retryDelayMs = 5000;
    private boolean moveToError = true;

    // archive
    private boolean archiveEnabled = true;
    private boolean archiveCompress = true;

    // NEW: multi-destination support
    private final List<Path> destinationDirs = new ArrayList<>();

    // ===== Constructors =====
    public ChannelConfig() {
        // no-arg for legacy code paths
    }

    public ChannelConfig(
            String name,
            boolean enabled,
            Path inputDir,
            Path outputDir,
            Path errorDir,
            Path archiveDir,
            String inputPattern,
            int pollIntervalMs,
            int batchSize,
            String transformerType,
            String transformerScript,
            String transformerClass,
            boolean createMissing,
            boolean validateProfile,
            int retryCount,
            int retryDelayMs,
            boolean moveToError,
            boolean archiveEnabled,
            boolean archiveCompress,
            List<Path> destinationDirs
    ) {
        this.name = name;
        this.enabled = enabled;
        this.inputDir = inputDir;
        this.outputDir = outputDir;
        this.errorDir = errorDir;
        this.archiveDir = archiveDir;
        this.inputPattern = inputPattern;
        this.pollIntervalMs = pollIntervalMs;
        this.batchSize = batchSize;
        this.transformerType = transformerType;
        this.transformerScript = transformerScript;
        this.transformerClass = transformerClass;
        this.createMissing = createMissing;
        this.validateProfile = validateProfile;
        this.retryCount = retryCount;
        this.retryDelayMs = retryDelayMs;
        this.moveToError = moveToError;
        this.archiveEnabled = archiveEnabled;
        this.archiveCompress = archiveCompress;
        if (destinationDirs != null) this.destinationDirs.addAll(destinationDirs);
    }

    // ===== Getters =====
    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }

    public Path getInputDir() { return inputDir; }
    /** Legacy single destination (first of destinations if present). */
    public Path getOutputDir() {
        if (!destinationDirs.isEmpty()) return destinationDirs.get(0);
        return outputDir;
    }
    public Path getErrorDir() { return errorDir; }
    public Path getArchiveDir() { return archiveDir; }

    public String getInputPattern() { return inputPattern; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public int getBatchSize() { return batchSize; }

    public String getTransformerType() { return transformerType; }
    public String getTransformerScript() { return transformerScript; }
    public String getTransformerClass() { return transformerClass; }
    public boolean isCreateMissing() { return createMissing; }
    public boolean isValidateProfile() { return validateProfile; }
    public Map<String, Object> getTransformerConfig() { return transformerConfig; }

    public int getRetryCount() { return retryCount; }
    public int getRetryDelayMs() { return retryDelayMs; }
    public boolean isMoveToError() { return moveToError; }

    public boolean isArchiveEnabled() { return archiveEnabled; }
    public boolean isArchiveCompress() { return archiveCompress; }

    public List<Path> getDestinationDirs() { return Collections.unmodifiableList(destinationDirs); }

    // ===== Setters for backward compatibility (GUI / App init) =====
    public void setName(String name) { this.name = name; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled != null && enabled; }

    public void setInputDir(String p) { this.inputDir = toPathOrNull(p); }
    public void setOutputDir(String p) { this.outputDir = toPathOrNull(p); }
    public void setErrorDir(String p) { this.errorDir = toPathOrNull(p); }
    public void setArchiveDir(String p) { this.archiveDir = toPathOrNull(p); }

    public void setInputPattern(String s) { if (s != null && !s.isBlank()) this.inputPattern = s.trim(); }
    public void setPollIntervalMs(long ms) { this.pollIntervalMs = (int) ms; }
    public void setBatchSize(Integer n) { this.batchSize = (n == null ? 10 : n); }

    public void setTransformerType(String s) { if (s != null) this.transformerType = s.trim(); }
    public void setTransformerScript(String s) { this.transformerScript = (s == null ? null : s.trim()); }
    public void setTransformerClass(String s) { this.transformerClass = (s == null ? null : s.trim()); }
    public void setCreateMissing(Boolean b) { this.createMissing = (b != null && b); }
    public void setValidateProfile(Boolean b) { this.validateProfile = (b != null && b); }
    public void setTransformerConfig(Map<String, Object> cfg) {
        this.transformerConfig = (cfg == null) ? new HashMap<>() : new HashMap<>(cfg);
    }

    public void setRetryCount(Integer n) { if (n != null) this.retryCount = n; }
    public void setRetryDelayMs(Integer n) { if (n != null) this.retryDelayMs = n; }
    public void setMoveToError(Boolean b) { this.moveToError = (b != null && b); }

    public void setArchiveEnabled(Boolean b) { this.archiveEnabled = (b != null && b); }
    public void setArchiveCompress(Boolean b) { this.archiveCompress = (b != null && b); }

    // Multi-destinations setter helpers (optional)
    public void clearDestinations() { this.destinationDirs.clear(); }
    public void addDestination(String p) { Path x = toPathOrNull(p); if (x != null) this.destinationDirs.add(x); }
    public void addDestination(Path p) { if (p != null) this.destinationDirs.add(p); }

    // ===== helpers =====
    private static Path toPathOrNull(String p) {
        if (p == null || p.trim().isEmpty()) return null;
        Path x = Paths.get(p.trim());
        return x.isAbsolute() ? x.normalize() : x.toAbsolutePath().normalize();
    }
}
