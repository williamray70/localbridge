// ============================================================================
// File: src/main/java/com/localbridge/transformer/wrapi/ClearCommand.java
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import com.localbridge.transformer.TransformException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLEAR <SEG>-<FIELD>
 *
 * Examples:
 *   CLEAR PID-6
 *   CLEAR PID-7
 *   CLEAR PID-8
 *
 * Behavior:
 *   - Clears the ENTIRE field (all repetitions/components/subcomponents) for every occurrence
 *     of the specified segment in the message.
 *   - No repetition subscripts. Always clears all repeats.
 *   - Ignores missing segments/fields without error.
 *
 * Notes:
 *   - Implemented by encoding the message, zeroing the requested field in matching segments,
 *     then parsing back. This avoids Terser group-path issues like "Can't find PID as a direct child".
 */
class ClearCommand implements WrapiCommand {

    private static final Pattern SIMPLE_PATH = Pattern.compile("^([A-Za-z]{3})-(\\d+)$");

    private final String segId;
    private final int fieldNum; // HL7 1-based field number

    ClearCommand(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("CLEAR requires a path like SEG-<field>");
        }
        Matcher m = SIMPLE_PATH.matcher(path.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("CLEAR only supports 'SEG-<field>' (e.g., PID-6): " + path);
        }
        this.segId = m.group(1).toUpperCase();
        this.fieldNum = Integer.parseInt(m.group(2));
        if (fieldNum < 1) {
            throw new IllegalArgumentException("Field number must be >= 1: " + path);
        }
    }

    @Override
    public Message execute(Message msg, Terser terser, PipeParser parser, boolean createMissing)
            throws TransformException {
        try {
            // Encode and normalize to CR
            String text = parser.encode(msg).replace("\r\n", "\r").replace("\n", "\r");
            String[] lines = text.split("\r", -1);
            if (lines.length == 0) return msg;

            // Determine field separator from MSH (default to '|')
            char fieldSep = '|';
            if (lines[0].startsWith("MSH") && lines[0].length() >= 4) {
                fieldSep = lines[0].charAt(3);
            }

            StringBuilder out = new StringBuilder(lines.length * 64);

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean isTerminalEmpty = (i == lines.length - 1) && (line != null && line.isEmpty());
                if (isTerminalEmpty) {
                    // Preserve a trailing CR at the very end; skip the empty split artifact itself
                    continue;
                }

                if (line == null || line.length() < 3) {
                    // Not a real segment line; just pass through
                    out.append(line == null ? "" : line).append('\r');
                    continue;
                }

                String id = line.substring(0, 3);
                if (!id.equalsIgnoreCase(segId)) {
                    // Unrelated segment: pass through
                    out.append(line).append('\r');
                    continue;
                }

                // Split fields (preserve empties)
                String[] fields = splitPreserveEmpty(line, fieldSep);

                // Map HL7 field number to token index:
                // - For non-MSH: HL7 field N -> fields[N] (fields[0] is "SEG")
                // - For MSH:     HL7 field 1 is the field separator char (not in tokens),
                //                HL7 field N (N>=2) -> fields[N-1]
                int tokenIndex;
                if (id.equals("MSH")) {
                    if (fieldNum == 1) {
                        // Field 1 is the separator character; treat as cleared (no-op on tokens)
                        tokenIndex = -1;
                    } else {
                        tokenIndex = fieldNum - 1; // N>=2
                    }
                } else {
                    tokenIndex = fieldNum; // N>=1
                }

                if (tokenIndex >= 0 && tokenIndex < fields.length) {
                    fields[tokenIndex] = ""; // clear entire field (all reps/components)
                }
                // If tokenIndex is outside range, field doesn't exist -> ignore

                // Rejoin
                out.append(join(fields, fieldSep)).append('\r');
            }

            return parser.parse(out.toString());

        } catch (HL7Exception e) {
            throw new TransformException("CLEAR failed for " + segId + "-" + fieldNum + ": " + e.getMessage(), e);
        }
    }

    private static String[] splitPreserveEmpty(String segmentLine, char fieldSep) {
        // Fast split preserving empties including trailing
        // We know segmentLine starts with 3-char segment id
        // e.g., "PID|a||b" -> ["PID","a","","b"]
        String sep = Character.toString(fieldSep);
        return segmentLine.split(java.util.regex.Pattern.quote(sep), -1);
    }

    private static String join(String[] parts, char fieldSep) {
        if (parts.length == 0) return "";
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(fieldSep).append(parts[i]);
        }
        return sb.toString();
    }
}
