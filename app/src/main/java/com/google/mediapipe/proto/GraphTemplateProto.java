package com.google.mediapipe.proto;

/**
 * MediaPipe's tasks-vision 0.10.14 ships com.google.mediapipe.framework.Graph, whose loadBinaryGraphTemplate()
 * mentions this proto class, but the class itself is missing from every published artifact. This empty stand-in only
 * satisfies that reference (the method is never called). NOTE: it was NOT the cause of the
 * "NoClassDefFoundError: com.google.mediapipe.framework.Graph" seen on phones; that came from R8 renaming Flogger's
 * backend classes (see proguard-rules.pro).
 */
public final class GraphTemplateProto {
    private GraphTemplateProto() {}

    public static final class CalculatorGraphTemplate {
        public byte[] toByteArray() { return new byte[0]; }
    }
}
