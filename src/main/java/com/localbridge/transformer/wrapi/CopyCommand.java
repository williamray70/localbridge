/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: CopyCommand.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      WRAPI "COPY <fromPath> <toPath>" command.
 *      Copies a field/segment value from <fromPath> to <toPath>.
 *
 *  Behavior (graceful):
 *      - If the source path is missing/unreadable, log a warning and CONTINUE.
 *      - If the destination path is not writable/missing, log a warning and CONTINUE.
 *      - Never throw for these cases; always return the (possibly modified) Message.
 *
 *  Notes:
 *      - Uses HAPI Terser paths (e.g., "PID-5", "OBX(2)-5-1").
 *      - We do NOT auto-create structures here regardless of createMissing.
 * =============================================================================
 */

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;
import ca.uhn.hl7v2.util.Terser;

public final class CopyCommand implements WrapiCommand {

    private final String fromPath;
    private final String toPath;

    public CopyCommand(String fromPath, String toPath) {
        this.fromPath = fromPath;
        this.toPath   = toPath;
    }

    @Override
    public Message execute(Message message, Terser terser, PipeParser parser, boolean createMissing) {
        // 1) Safe GET
        String value = safeGet(terser, fromPath);
        if (value == null) {
            warn("COPY skipped: source path not found or unreadable: '" + fromPath + "'");
            return message; // graceful: continue processing
        }

        // 2) Safe SET
        if (!safeSet(terser, toPath, value)) {
            warn("COPY skipped: destination path not writable: '" + toPath + "'");
            // graceful: continue processing
        }

        return message;
    }

    private String safeGet(Terser terser, String path) {
        try {
            // Terser#get returns "" for empty fields; throws if inaccessible
            return terser.get(path);
        } catch (HL7Exception e) {
            return null; // treat as missing/unreadable
        }
    }

    private boolean safeSet(Terser terser, String path, String value) {
        try {
            terser.set(path, value != null ? value : "");
            return true;
        } catch (HL7Exception e) {
            return false; // destination not present or invalid path
        }
    }

    private static void warn(String msg) {
        System.err.println("[WRAPI][COPY][WARN] " + msg);
    }

    @Override
    public String toString() {
        return "COPY '" + fromPath + "' -> '" + toPath + "'";
    }
}
