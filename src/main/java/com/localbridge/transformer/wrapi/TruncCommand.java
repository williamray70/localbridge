// ============================================================================
// File: src/main/java/com/localbridge/transformer/wrapi/TruncCommand.java
// Author: LocalBridge Health Integration
// Description:
//   TRUNC command for WRAPI scripts — supports:
//     1) Segment truncation:   TRUNC OBX,5
//     2) Field repetition truncation: TRUNC PID-13,1
//
// Behavior:
//   - HL7 fields are 1-based.
//   - Segment truncation keeps first N matching segments.
//   - Field truncation keeps first N repetitions (split by '~').
//   - Derives separators from MSH (defaults: field '|', repetition '~').
//
// Revision History:
//   2025-10-10  Initial segment+field support
//   2025-10-11  Fixed MSH encoding field index (msh[1], not msh[2])
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import com.localbridge.transformer.TransformException;

import java.util.Arrays;
import java.util.regex.Pattern;

final class TruncCommand implements WrapiCommand {

    private final String seg;        // e.g., "PID", "OBX", etc.
    private final Integer fieldNum;  // null => segment mode
    private final int keepCount;     // 1-based

    public TruncCommand(String path, int count, boolean ignored) throws TransformException {
        if (path == null || path.isBlank()) {
            throw new TransformException("TRUNC requires a path");
        }
        String p = path.trim();
        if (p.contains("-")) {
            String[] parts = p.split("-", 2);
            if (parts.length != 2) throw new TransformException("Invalid TRUNC path: " + path);
            this.seg = parts[0].toUpperCase();
            try {
                this.fieldNum = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                throw new TransformException("Invalid field number in TRUNC path: " + parts[1], e);
            }
        } else {
            this.seg = p.toUpperCase();
            this.fieldNum = null; // segment truncation mode
        }

        if (count < 1) throw new TransformException("TRUNC count must be >= 1 (1-based)");
        this.keepCount = count;
    }

    @Override
    public Message execute(Message msg, Terser terser, PipeParser parser, boolean createMissing)
            throws TransformException {

        try {
            String encoded = parser.encode(msg)
                    .replace("\r\n", "\r")
                    .replace("\n", "\r");
            String[] lines = encoded.split("\r", -1);
            if (lines.length == 0) return msg;

            // Determine separators
            char fieldSep = '|';
            char repSep = '~';
            if (lines[0].startsWith("MSH") && lines[0].length() >= 4) {
                fieldSep = lines[0].charAt(3);
                String[] msh = lines[0].split(Pattern.quote(String.valueOf(fieldSep)), -1);
                // Fixed: MSH-2 encoding characters live at msh[1], not msh[2]
                if (msh.length > 1 && msh[1] != null && msh[1].length() >= 2) {
                    repSep = msh[1].charAt(1);
                }
            }

            // ================= SEGMENT MODE =================
            if (fieldNum == null) {
                StringBuilder out = new StringBuilder(encoded.length());
                int seen = 0;
                for (String line : lines) {
                    if (line == null || line.isEmpty()) continue;
                    if (line.regionMatches(true, 0, seg, 0, 3)) {
                        seen++;
                        if (seen > keepCount) continue; // drop extras
                    }
                    out.append(line).append('\r');
                }
                return parser.parse(out.toString());
            }

            // ================= FIELD REPETITION MODE =================
            StringBuilder out = new StringBuilder(encoded.length());
            for (String line : lines) {
                if (line == null || line.isEmpty()) continue;

                if (line.regionMatches(true, 0, seg, 0, 3)) {
                    String[] fields = line.split(Pattern.quote(String.valueOf(fieldSep)), -1);
                    int idx = fieldNum; // HL7 1-based field index

                    if (idx < fields.length) {
                        String val = fields[idx];
                        if (val != null && val.contains(String.valueOf(repSep))) {
                            String[] reps = val.split(Pattern.quote(String.valueOf(repSep)), -1);
                            if (reps.length > keepCount) {
                                fields[idx] = String.join(
                                        String.valueOf(repSep),
                                        Arrays.copyOfRange(reps, 0, keepCount)
                                );
                            }
                        }
                    }
                    line = join(fields, fieldSep);
                }
                out.append(line).append('\r');
            }

            return parser.parse(out.toString());

        } catch (HL7Exception e) {
            throw new TransformException("TRUNC failed: " + e.getMessage(), e);
        }
    }

    private static String join(String[] parts, char sep) {
        if (parts.length == 0) return "";
        StringBuilder sb = new StringBuilder(parts[0] == null ? "" : parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(sep).append(parts[i] == null ? "" : parts[i]);
        }
        return sb.toString();
    }
}
