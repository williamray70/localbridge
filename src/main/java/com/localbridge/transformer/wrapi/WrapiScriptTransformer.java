/*
 * =============================================================================
 *  LocalBridge Health Integration Platform
 *  File: WrapiScriptTransformer.java
 * -----------------------------------------------------------------------------
 *  Purpose:
 *      Parse and execute WRAPI scripts (SET, COPY, CLEAR, DELSEG, TRUNC, ADDSEG, SAVE).
 *
 *  Change Log (2025-10-11):
 *      - Relax TRUNC regex to allow spaces around comma, e.g. 'TRUNC OBX, 3'.
 *        Old: ^([A-Z0-9-]+),(\d+)$
 *        New: ^([A-Z0-9-]+)\s*,\s*(\d+)$
 * =============================================================================
 */
// File: src/main/java/com/localbridge/transformer/wrapi/WrapiScriptTransformer.java
// ============================================================================

package com.localbridge.transformer.wrapi;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.parser.PipeParser;
import com.localbridge.transformer.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WrapiScriptTransformer implements HL7Transformer {
    private List<WrapiCommand> commands;
    private final Path scriptPath;
    private final boolean createMissing;
    
    public WrapiScriptTransformer(Path scriptPath, boolean createMissing) {
        this.scriptPath = scriptPath;
        this.createMissing = createMissing;
    }
    
    @Override
    public void initialize(Map<String, Object> config) throws TransformException {
        try {
            String script = Files.readString(scriptPath);
            this.commands = parseScript(script);
        } catch (IOException e) {
            throw new TransformException("Failed to load WRAPI script: " + scriptPath, e);
        }
    }
    
    @Override
    public Message transform(Message message, TransformContext context) throws TransformException {
        try {
            Terser terser = new Terser(message);
            PipeParser parser = new PipeParser();
            
            Message currentMessage = message;
            
            for (WrapiCommand cmd : commands) {
                Message result = cmd.execute(currentMessage, terser, parser, createMissing);
                if (result != null) {
                    // Command returned a new message - use it
                    currentMessage = result;
                    terser = new Terser(currentMessage);
                }
            }
            
            return currentMessage;
        } catch (Exception e) {
            throw new TransformException("WRAPI script execution failed", e);
        }
    }
    
    private List<WrapiCommand> parseScript(String script) throws TransformException {
        List<WrapiCommand> cmds = new ArrayList<>();
        String[] lines = script.split("\n");
        boolean afterSave = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            
            if (afterSave) {
                continue;
            }
            
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            
            try {
                WrapiCommand cmd = parseLine(line, i + 1);
                cmds.add(cmd);
                
                if (cmd instanceof SaveCommand) {
                    afterSave = true;
                }
            } catch (Exception e) {
                throw new TransformException(
                    String.format("Syntax error at line %d: %s\n%s", i + 1, line, e.getMessage()), e);
            }
        }
        
        return cmds;
    }
    
    private WrapiCommand parseLine(String line, int lineNum) throws TransformException {
        String upper = line.toUpperCase();
        
        if (upper.startsWith("SET ")) {
            return parseSet(line.substring(4).trim());
        } else if (upper.startsWith("COPY ")) {
            return parseCopy(line.substring(5).trim());
        } else if (upper.startsWith("CLEAR ")) {
            return parseClear(line.substring(6).trim());
        } else if (upper.startsWith("DELSEG ")) {
            return parseDelSeg(line.substring(7).trim());
        } else if (upper.startsWith("TRUNC ")) {
            return parseTrunc(line.substring(6).trim());
        } else if (upper.startsWith("ADDSEG ")) {
            return parseAddSeg(line.substring(7).trim());
        } else if (upper.equals("SAVE")) {
            return new SaveCommand();
        } else {
            throw new TransformException("Unknown command at line " + lineNum);
        }
    }
    
    private SetCommand parseSet(String args) throws TransformException {
        Pattern p = Pattern.compile("^([A-Z0-9-]+)\\s+\"(.*)\"$", Pattern.DOTALL);
        Matcher m = p.matcher(args);
        if (!m.matches()) {
            throw new TransformException("Invalid SET syntax: " + args);
        }
        return new SetCommand(m.group(1), unescapeString(m.group(2)));
    }
    
    private CopyCommand parseCopy(String args) throws TransformException {
        Pattern p = Pattern.compile("^([A-Z0-9-]+)\\s+->\\s+([A-Z0-9-]+)$");
        Matcher m = p.matcher(args);
        if (!m.matches()) {
            throw new TransformException("Invalid COPY syntax: " + args);
        }
        return new CopyCommand(m.group(1), m.group(2));
    }
    
    private ClearCommand parseClear(String args) throws TransformException {
        if (!args.matches("^[A-Z0-9-]+$")) {
            throw new TransformException("Invalid CLEAR syntax: " + args);
        }
        return new ClearCommand(args);
    }
    
    private DelSegCommand parseDelSeg(String args) throws TransformException {
        if (!args.matches("^[A-Z][A-Z0-9]{2}$")) {
            throw new TransformException("Invalid DELSEG syntax: " + args);
        }
        return new DelSegCommand(args);
    }
    
    private TruncCommand parseTrunc(String args) throws TransformException {
        Pattern p = Pattern.compile("^([A-Z0-9-]+)\\s*,\\s*(\\d+)$");
        Matcher m = p.matcher(args);
        if (!m.matches()) {
            throw new TransformException("Invalid TRUNC syntax: " + args);
        }
        
        String path = m.group(1);
        int keepUpToIndex = Integer.parseInt(m.group(2));
        boolean isSegment = path.matches("^[A-Z][A-Z0-9]{2}$");
        
        return new TruncCommand(path, keepUpToIndex, isSegment);
    }
    
    private AddSegCommand parseAddSeg(String args) throws TransformException {
        Pattern afterPattern = Pattern.compile("^after\\s+([A-Z][A-Z0-9]{2})\\s+\"(.*)\"$", Pattern.DOTALL);
        Pattern endPattern = Pattern.compile("^\"(.*)\"$", Pattern.DOTALL);
        
        Matcher afterMatcher = afterPattern.matcher(args);
        if (afterMatcher.matches()) {
            return new AddSegCommand(afterMatcher.group(1), unescapeString(afterMatcher.group(2)));
        }
        
        Matcher endMatcher = endPattern.matcher(args);
        if (endMatcher.matches()) {
            return new AddSegCommand(null, unescapeString(endMatcher.group(1)));
        }
        
        throw new TransformException("Invalid ADDSEG syntax: " + args);
    }
    
    private String unescapeString(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\n", "\n")
                .replace("\\r", "\r");
    }
}