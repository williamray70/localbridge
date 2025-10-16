// ============================================================================
// File: src/main/java/com/localbridge/transformer/wrapi/DelSegCommand.java
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.HL7Exception;
import com.localbridge.transformer.TransformException;

/**
 * DELSEG <SEGID>
 * Removes all segments whose three-character ID matches SEGID (case-insensitive).
 * Examples:
 *   DELSEG IN1
 *   DELSEG IN2
 */
class DelSegCommand implements WrapiCommand {
    private final String segId; // e.g., "IN1", "IN2"

    DelSegCommand(String segId) {
        if (segId == null || segId.isBlank())
            throw new IllegalArgumentException("DELSEG requires a segment id");
        this.segId = segId.trim();
    }

    @Override
    public Message execute(Message msg, Terser terser, PipeParser parser, boolean createMissing)
            throws TransformException {
        try {
            // Encode and normalize line endings to CR
            String original = parser.encode(msg).replace("\r\n", "\r").replace("\n", "\r");
            // Split preserving empties so a terminal CR shows up as a last empty element
            String[] segs = original.split("\r", -1);

            String target = segId;
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segs.length; i++) {
                String line = segs[i];
                boolean isTerminalEmpty = (i == segs.length - 1) && (line != null && line.isEmpty());
                if (isTerminalEmpty) {
                    // ignore the artificial last empty piece from split
                    continue;
                }
                if (!matchesId(line, target)) {
                    out.append(line).append("\r");
                }
            }

            return parser.parse(out.toString());

        } catch (HL7Exception e) {
            throw new TransformException("DELSEG failed for " + segId + ": " + e.getMessage(), e);
        }
    }

    private static boolean matchesId(String line, String want) {
        if (line == null || line.isEmpty()) return false;
        int len = Math.min(3, line.length());
        String id = line.substring(0, len);
        return id.equalsIgnoreCase(want);
    }

    // Optional helper
    static DelSegCommand of(String segId) { return new DelSegCommand(segId); }
}
