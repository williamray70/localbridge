// ============================================================================
// File: src/main/java/com/localbridge/transformer/AdtCleanupJavaTransformer.java
// Purpose: Java equivalent of conf/transformers/adt-cleanup.wrapi
// ============================================================================

package com.localbridge.transformer;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;

/**
 * Behavior mirrored from WRAPI script:
 * - SET MSH-4 "MAIN_HOSPITAL", MSH-6 "PRIMARY_SYSTEM", MSH-12 "2.2"
 * - CLEAR PID-5, PID-6, PID-7, PID-8 (all repeats/components)
 * - DELSEG IN1, PR1, AL1, IN2 (remove all occurrences)
 * - TRUNC PID-13 to at most 2 repetitions (keep first two phone numbers)
 * - ADDSEG after PID: NTE|1|PROCESSED|ADT_CLEANUP
 * - ADDSEG (append):  ZXT|1|PROCESSED|ADT_CLEANUP
 * - SAVE (implicit: return new Message)
 */
public final class AdtCleanupJavaTransformer implements Transformer {

    @Override
    public Message transform(Message msg) throws TransformException {
        PipeParser parser = new PipeParser();
        try {
            String text = parser.encode(msg).replace("\r\n", "\r").replace("\n", "\r");
            String[] lines = text.split("\r", -1); // keep trailing artifact

            if (lines.length == 0) return msg;

            // Derive separators from MSH
            char fieldSep = '|';
            char repSep = '~';
            if (lines[0].startsWith("MSH") && lines[0].length() >= 4) {
                fieldSep = lines[0].charAt(3);
                // MSH-2 encoding chars: component, repetition, escape, subcomponent (e.g., ^~\&)
                String[] mshFields = splitPreserveEmpty(lines[0], fieldSep);
                if (mshFields.length > 2 && mshFields[2] != null && mshFields[2].length() >= 2) {
                    repSep = mshFields[2].charAt(1); // default "~"
                }
            }

            // Pass 1: mutate lines in place (SET, CLEAR, TRUNC) and drop unwanted segments (DELSEG)
            StringBuilder sb = new StringBuilder(lines.length * 64);
            boolean insertedNTE = false;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean terminalEmpty = (i == lines.length - 1) && (line != null && line.isEmpty());
                if (terminalEmpty) continue; // preserve final CR implicitly

                if (line == null || line.length() < 3) {
                    sb.append(line == null ? "" : line).append('\r');
                    continue;
                }

                String seg = line.substring(0, 3);
                if (equals3(seg, "IN1") || equals3(seg, "PR1") || equals3(seg, "AL1") || equals3(seg, "IN2")) {
                    // DELSEG these
                    continue;
                }

                String outLine = line;

                if (equals3(seg, "MSH")) {
                    String[] f = splitPreserveEmpty(line, fieldSep);
                    // MSH mapping: HL7 field N (N>=2) -> f[N-1]; field 1 is the separator char
                    outLine = setField(f, /*HL7*/4, "MAIN_HOSPITAL", fieldSep, true);   // MSH-4
                    outLine = setField(splitPreserveEmpty(outLine, fieldSep), 6, "PRIMARY_SYSTEM", fieldSep, true); // MSH-6
                    outLine = setField(splitPreserveEmpty(outLine, fieldSep), 12, "2.2", fieldSep, true); // MSH-12

                } else if (equals3(seg, "PID")) {
                    String[] f = splitPreserveEmpty(line, fieldSep);

                    // CLEAR PID-5..8 (all repeats/components/subcomponents)
                    clearWholeField(f, /*HL7*/5, fieldSep);
                    clearWholeField(f, 6, fieldSep);
                    clearWholeField(f, 7, fieldSep);
                    clearWholeField(f, 8, fieldSep);

                    // TRUNC PID-13 to max 2 repetitions
                    truncateReps(f, /*HL7*/13, fieldSep, repSep, 2);

                    outLine = join(f, fieldSep);

                } else {
                    // leave other segments as-is
                }

                sb.append(outLine).append('\r');

                // ADDSEG after first PID
                if (!insertedNTE && equals3(seg, "PID")) {
                    sb.append("NTE|1|PROCESSED|ADT_CLEANUP").append('\r');
                    insertedNTE = true;
                }
            }

            // Append ZXT at end
            sb.append("ZXT|1|PROCESSED|ADT_CLEANUP").append('\r');

            return parser.parse(sb.toString());

        } catch (HL7Exception e) {
            throw new TransformException("ADT cleanup (Java) failed: " + e.getMessage(), e);
        }
    }

    // ---------------- helpers ----------------

    private static boolean equals3(String a, String id) {
        return a != null && a.length() >= 3 && id != null && id.length() == 3 && a.regionMatches(true, 0, id, 0, 3);
    }

    private static String[] splitPreserveEmpty(String line, char sep) {
        return line.split(java.util.regex.Pattern.quote(Character.toString(sep)), -1);
    }

    private static String join(String[] parts, char sep) {
        if (parts.length == 0) return "";
        StringBuilder b = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) b.append(sep).append(parts[i]);
        return b.toString();
    }

    /** Set HL7 field N, accounting for MSH offset rules. */
    private static String setField(String[] fields, int hl7Field, String value, char fieldSep, boolean isMSH) {
        int idx = isMSH ? (hl7Field == 1 ? -1 : hl7Field - 1) : hl7Field;
        if (idx >= 0) {
            if (idx >= fields.length) {
                // grow array
                String[] grown = new String[idx + 1];
                System.arraycopy(fields, 0, grown, 0, fields.length);
                for (int i = fields.length; i < grown.length; i++) grown[i] = "";
                fields = grown;
            }
            fields[idx] = value;
        }
        return join(fields, fieldSep);
    }

    /** Clear entire field (all repetitions/components) for HL7 field number N. */
    private static void clearWholeField(String[] fields, int hl7Field, char fieldSep) {
        int idx = hl7Field; // non-MSH semantics
        if (idx >= 0 && idx < fields.length) {
            fields[idx] = "";
        }
    }

    /** Keep at most maxReps for the HL7 field number; drops extras. */
    private static void truncateReps(String[] fields, int hl7Field, char fieldSep, char repSep, int maxReps) {
        int idx = hl7Field; // non-MSH
        if (idx >= 0 && idx < fields.length) {
            String val = fields[idx];
            if (val == null || val.isEmpty()) return;
            String[] reps = val.split(java.util.regex.Pattern.quote(Character.toString(repSep)), -1);
            if (reps.length > maxReps) {
                StringBuilder b = new StringBuilder();
                int keep = Math.max(0, maxReps);
                for (int i = 0; i < keep; i++) {
                    if (i > 0) b.append(repSep);
                    b.append(reps[i]);
                }
                fields[idx] = b.toString();
            }
        }
    }
}
