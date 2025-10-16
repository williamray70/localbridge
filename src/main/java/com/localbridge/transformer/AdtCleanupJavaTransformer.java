// ============================================================================
// File: src/main/java/com/localbridge/transformer/AdtCleanupJavaTransformer.java
// ============================================================================

package com.localbridge.transformer;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.PipeParser;

/**
 * Java equivalent of conf/transformers/adt-cleanup.wrapi
 */
public final class AdtCleanupJavaTransformer implements HL7Transformer {

    @Override
    public Message transform(Message msg, TransformContext context) throws TransformException {
        PipeParser parser = new PipeParser();
        try {
            String text = parser.encode(msg).replace("\r\n", "\r").replace("\n", "\r");
            String[] lines = text.split("\r", -1);
            if (lines.length == 0) return msg;

            char fieldSep = '|';
            char repSep = '~';
            if (lines[0].startsWith("MSH") && lines[0].length() >= 4) {
                fieldSep = lines[0].charAt(3);
                String[] msh = split(lines[0], fieldSep);
                if (msh.length > 2 && msh[2] != null && msh[2].length() >= 2) repSep = msh[2].charAt(1);
            }

            StringBuilder out = new StringBuilder(lines.length * 64);
            boolean insertedAfterPid = false;

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                boolean termEmpty = (i == lines.length - 1) && line != null && line.isEmpty();
                if (termEmpty) continue;
                if (line == null || line.length() < 3) { out.append(line == null ? "" : line).append('\r'); continue; }

                String seg = line.substring(0, 3);

                // DELSEG: IN1, PR1, AL1, IN2
                if (eq3(seg, "IN1") || eq3(seg, "PR1") || eq3(seg, "AL1") || eq3(seg, "IN2")) continue;

                String outLine = line;

                if (eq3(seg, "MSH")) {
                    String[] f = split(line, fieldSep);
                    outLine = setField(f, 4,  "MAIN_HOSPITAL",  fieldSep, true);                 // SET MSH-4
                    outLine = setField(split(outLine, fieldSep), 6,  "PRIMARY_SYSTEM", fieldSep, true); // SET MSH-6
                    outLine = setField(split(outLine, fieldSep), 12, "2.2",            fieldSep, true); // SET MSH-12

                } else if (eq3(seg, "PID")) {
                    String[] f = split(line, fieldSep);
                    // CLEAR PID-5..8 (all repeats/components)
                    clear(f, 5); clear(f, 6); clear(f, 7); clear(f, 8);
                    // TRUNC PID-13 to max 2 repetitions
                    truncReps(f, 13, repSep, 2);
                    outLine = join(f, fieldSep);
                }

                out.append(outLine).append('\r');

                // ADDSEG after first PID
                if (!insertedAfterPid && eq3(seg, "PID")) {
                    out.append("NTE|1|PROCESSED|ADT_CLEANUP").append('\r');
                    insertedAfterPid = true;
                }
            }

            // ADDSEG at end
            out.append("ZXT|1|PROCESSED|ADT_CLEANUP").append('\r');

            return parser.parse(out.toString());

        } catch (HL7Exception e) {
            throw new TransformException("ADT cleanup (Java) failed: " + e.getMessage(), e);
        }
    }

    // ---- helpers ----
    private static boolean eq3(String a, String id){
        return a!=null&&a.length()>=3&&id!=null&&id.length()==3&&a.regionMatches(true,0,id,0,3);
    }
    private static String[] split(String line, char sep){
        return line.split(java.util.regex.Pattern.quote(Character.toString(sep)),-1);
    }
    private static String join(String[] p, char s){
        if(p.length==0)return"";
        StringBuilder b=new StringBuilder(p[0]);
        for(int i=1;i<p.length;i++) b.append(s).append(p[i]);
        return b.toString();
    }
    // Set HL7 field N; for MSH, N>=2 maps to array index N-1 (field 1 is the separator char)
    private static String setField(String[] fields,int hl7Field,String val,char fieldSep,boolean isMSH){
        int idx = isMSH ? (hl7Field==1?-1:hl7Field-1) : hl7Field;
        if(idx>=0){
            if(idx>=fields.length){
                String[] g=new String[idx+1];
                System.arraycopy(fields,0,g,0,fields.length);
                for(int i=fields.length;i<g.length;i++) g[i]="";
                fields=g;
            }
            fields[idx]=val;
        }
        return join(fields,fieldSep);
    }
    private static void clear(String[] fields,int hl7Field){
        int idx=hl7Field; if(idx>=0&&idx<fields.length) fields[idx]="";
    }
    private static void truncReps(String[] fields,int hl7Field,char repSep,int max){
        int idx=hl7Field; if(idx<0||idx>=fields.length) return;
        String v=fields[idx]; if(v==null||v.isEmpty()) return;
        String[] reps=v.split(java.util.regex.Pattern.quote(Character.toString(repSep)),-1);
        if(reps.length<=max) return;
        StringBuilder b=new StringBuilder();
        for(int i=0;i<max;i++){ if(i>0)b.append(repSep); b.append(reps[i]); }
        fields[idx]=b.toString();
    }
}
