// ============================================================================
// File: src/main/java/com/localbridge/transformer/wrapi/SaveCommand.java
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.parser.PipeParser;

class SaveCommand implements WrapiCommand {
    @Override
    public Message execute(Message msg, Terser terser, PipeParser parser, boolean createMissing) {
        // SAVE is a no-op in execution
        return null;
    }
}