// ============================================================================
// Core Transformer Interface
// ============================================================================

package com.localbridge.transformer;

import ca.uhn.hl7v2.model.Message;
import java.util.Map;

public interface HL7Transformer {
    Message transform(Message message, TransformContext context) throws TransformException;
    default void initialize(Map<String, Object> config) throws TransformException {}
    default void shutdown() {}
}
