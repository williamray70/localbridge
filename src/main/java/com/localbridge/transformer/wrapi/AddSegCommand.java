/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: AddSegCommand.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      WRAPI ADDSEG command with strong de-duplication guarantees.
 *
 *  Behavior:
 *      ADDSEG after PID "NTE|1|PROCESSED|ADT_CLEANUP"
 *        - Remove ALL existing "NTE|1|PROCESSED|ADT_CLEANUP"
 *        - Insert exactly once immediately after the FIRST PID
 *        - If PID not found, skip gracefully
 *
 *      ADDSEG "ZXT|1|PROCESSED|ADT_CLEANUP"
 *        - Remove ALL existing "ZXT|1|PROCESSED|ADT_CLEANUP"
 *        - Append exactly once at the end
 *
 *  Notes:
 *      - Operates on encoded HL7 text to avoid structural surprises.
 *      - Ensures idempotence even if the transformer executes the same command
 *        multiple times within a single run.
 * =============================================================================
 */

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;
import com.localbridge.transformer.TransformException;

import java.util.ArrayList;
import java.util.List;

final class AddSegCommand implements WrapiCommand {

    private final String afterSegment;   // e.g., "PID" or null = append
    private final String segmentLine;    // e.g., "ZXT|1|PROCESSED|ADT_CLEANUP"

    AddSegCommand(String afterSegment, String segmentLine) {
        this.afterSegment = (afterSegment == null || afterSegment.isBlank())
                ? null : afterSegment.trim();
        this.segmentLine = segmentLine == null ? "" : segmentLine.trim();
    }

    @Override
    public Message execute(Message message, Terser terser, PipeParser parser, boolean createMissing) throws TransformException {
        try {
            // Encode to text, normalize line endings
            String text = parser.encode(message)
                    .replace("\r\n", "\r")
                    .replace("\n", "\r");
            if (!text.endsWith("\r")) text = text + "\r";

            String[] lines = text.split("\r", -1);

            // 1) Remove ALL existing copies of the target line (global dedupe)
            List<String> filtered = new ArrayList<>(lines.length);
            for (String ln : lines) {
                if (!ln.equals(segmentLine)) {
                    filtered.add(ln);
                }
            }

            // 2) Insert exactly once depending on mode
            if (afterSegment == null) {
                // APPEND MODE
                filtered.add(segmentLine);
            } else {
                // AFTER <SEGMENT> MODE — find FIRST occurrence of anchor
                int anchorIdx = -1;
                for (int i = 0; i < filtered.size(); i++) {
                    String ln = filtered.get(i);
                    if (ln.startsWith(afterSegment + "|") || ln.equals(afterSegment)) {
                        anchorIdx = i;
                        break;
                    }
                }
                if (anchorIdx < 0) {
                    // Anchor not found; skip gracefully (no exceptions)
                    return message;
                }
                filtered.add(anchorIdx + 1, segmentLine);
            }

            // 3) Re-encode and parse back
            String outText = String.join("\r", filtered);
            if (!outText.endsWith("\r")) outText += "\r";
            return parser.parse(outText);

        } catch (HL7Exception e) {
            throw new TransformException("ADDSEG failed: " + segmentLine + " - " + e.getMessage(), e);
        }
    }

    // Convenience factories (optional)
    static AddSegCommand ofAfter(String seg, String line) {
        return new AddSegCommand(seg, line);
    }
    static AddSegCommand ofAppend(String line) {
        return new AddSegCommand(null, line);
    }
}
