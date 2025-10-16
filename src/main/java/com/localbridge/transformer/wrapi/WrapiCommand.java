// ============================================================================
// File: src/main/java/com/localbridge/transformer/wrapi/WrapiCommand.java
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.parser.PipeParser;
import com.localbridge.transformer.TransformException;

interface WrapiCommand {
    // Returns Message (possibly new instance) or null (use original)
    Message execute(Message msg, Terser terser, PipeParser parser, boolean createMissing) 
        throws TransformException;
}