package com.localbridge.outbound;

import java.nio.file.Path;

/**
 * LocalBridge - Outbound Channel Config (file -> MLLP sender)
 * ----------------------------------------------------------
 * Minimal config used by the outbound runtime. Mirrors inbound style,
 * but adds host/port and a source directory to poll.
 *
 * Defaults (applied if omitted in YAML):
 *  - pattern:        "*.hl7"
 *  - waitForAck:     true
 *  - connectTimeout: 5000 ms
 *  - readTimeout:    5000 ms
 *  - pollIntervalMs: 1000 ms
 *  - errorDir:       <sourceDir>/errors
 */
public final class OutboundChannelConfig {

    private final String  name;
    private final boolean enabled;

    private final String host;
    private final int    port;

    private final Path   sourceDir;
    private final String pattern;          // Glob pattern, e.g. "*.hl7"

    private final boolean waitForAck;
    private final int     connectTimeoutMs;
    private final int     readTimeoutMs;
    private final int     pollIntervalMs;

    private final Path    errorDir;       // Optional; if null we’ll use <sourceDir>/errors
    private final String  description;

    public OutboundChannelConfig(
            String name,
            boolean enabled,
            String host,
            int port,
            Path sourceDir,
            String pattern,
            boolean waitForAck,
            int connectTimeoutMs,
            int readTimeoutMs,
            int pollIntervalMs,
            Path errorDir,
            String description
    ) {
        this.name = name;
        this.enabled = enabled;
        this.host = host;
        this.port = port;
        this.sourceDir = sourceDir;
        this.pattern = pattern != null ? pattern : "*.hl7";
        this.waitForAck = waitForAck;
        this.connectTimeoutMs = connectTimeoutMs > 0 ? connectTimeoutMs : 5000;
        this.readTimeoutMs = readTimeoutMs > 0 ? readTimeoutMs : 5000;
        this.pollIntervalMs = pollIntervalMs > 0 ? pollIntervalMs : 1000;
        this.errorDir = errorDir; // may be null; resolved in sender
        this.description = description;
    }

    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Path getSourceDir() { return sourceDir; }
    public String getPattern() { return pattern; }
    public boolean isWaitForAck() { return waitForAck; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public int getPollIntervalMs() { return pollIntervalMs; }
    public Path getErrorDir() { return errorDir; }
    public String getDescription() { return description; }

    @Override public String toString() {
        return "OutboundChannelConfig{" +
                "name='" + name + '\'' +
                ", enabled=" + enabled +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", sourceDir=" + sourceDir +
                ", pattern='" + pattern + '\'' +
                ", waitForAck=" + waitForAck +
                ", pollIntervalMs=" + pollIntervalMs +
                '}';
    }
}
