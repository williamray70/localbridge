/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: TransformContext.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Context data passed to transformers. ChannelEngine reflectively requests
 *      a one-arg constructor TransformContext(String). This version adds that
 *      constructor while preserving the original 4-arg API from your ZIP.
 *
 *  Change Log:
 *      2025-10-11  Added 1-arg ctor TransformContext(String) for compatibility.
 * =============================================================================
 */
package com.localbridge.transformer;

import java.nio.file.Path;
import java.time.Instant;

public class TransformContext {
    private final String channelName;
    private final String originalFilename;
    private final Path inputPath;
    private final Instant receivedTime;

    /** Added for compatibility with ChannelEngine’s reflective construction */
    public TransformContext(String channelName) {
        this(channelName, null, null, Instant.now());
    }

    /** Original constructor (unchanged) */
    public TransformContext(String channelName, String originalFilename,
                            Path inputPath, Instant receivedTime) {
        this.channelName = channelName;
        this.originalFilename = originalFilename;
        this.inputPath = inputPath;
        this.receivedTime = receivedTime;
    }

    public String getChannelName() { return channelName; }
    public String getOriginalFilename() { return originalFilename; }
    public Path getInputPath() { return inputPath; }
    public Instant getReceivedTime() { return receivedTime; }
}