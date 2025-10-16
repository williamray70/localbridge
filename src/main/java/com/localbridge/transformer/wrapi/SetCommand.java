// ============================================================================
// File: src/main/java/com/localbridge/transformer/wrapi/SetCommand.java
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Structure;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.HL7Exception;
import com.localbridge.transformer.TransformException;

class SetCommand implements WrapiCommand {
    private final String path;
    private final String value;
    
    public SetCommand(String path, String value) {
        this.path = path;
        this.value = value;
    }
    
    @Override
    public Message execute(Message msg, Terser terser, PipeParser parser, boolean createMissing) 
            throws TransformException {
        try {
            String segmentName = path.split("-")[0];
            Structure[] segments = msg.getAll(segmentName);
            
            if (segments.length == 0 && createMissing) {
                terser.set(path, value);
            } else {
                for (int i = 0; i < segments.length; i++) {
                    String indexedPath = path + "(" + i + ")";
                    terser.set(indexedPath, value);
                }
            }
        } catch (HL7Exception e) {
            throw new TransformException("SET failed for path: " + path, e);
        }
        return null;
    }
}
