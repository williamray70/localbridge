/*
 * =============================================================================
 *  InboundChannelConfig
 * =============================================================================
 *  Immutable configuration record for inbound (MLLP) listeners.
 *  Used by InboundYamlLoader and InboundEngineManager.
 * =============================================================================
 */
package com.localbridge.inbound;

import java.nio.file.Path;

/**
 * Represents a single inbound listener configuration.
 * All fields are loaded from YAML.
 */
public record InboundChannelConfig(
        String name,
        boolean enabled,
        InboundType type,
        int port,
        String description,
        boolean autoAck,
        Path saveDir,
        String filePrefix,
        String fileSuffix
) {

    /** Default type is always MLLP per current engine spec. */
    public static InboundChannelConfig of(
            String name,
            boolean enabled,
            int port,
            Path saveDir,
            String filePrefix,
            String fileSuffix,
            String description
    ) {
        return new InboundChannelConfig(
                name,
                enabled,
                InboundType.MLLP,
                port,
                description,
                true,          // autoAck always true
                saveDir,
                filePrefix,
                fileSuffix
        );
    }

    /** Enum for future extensibility (e.g. HTTP, file drop, etc.) */
    public enum InboundType {
        MLLP
    }

     /** Compatibility getter for legacy runtime classes */
    public String getName() {
        return name();
    }
}
