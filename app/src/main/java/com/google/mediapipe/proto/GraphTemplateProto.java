package com.google.mediapipe.proto;

/**
 * MediaPipe's tasks-vision 0.10.14 ships com.google.mediapipe.framework.Graph, whose loadBinaryGraphTemplate()
 * mentions this proto class, but the class itself is missing from every published artifact.
 * On a real phone that makes Graph fail to load (NoClassDefFoundError: com.google.mediapipe.framework.Graph),
 * so the image model never starts. This empty stand-in satisfies the reference; the method is never called.
 */
public final class GraphTemplateProto {
    private GraphTemplateProto() {}

    public static final class CalculatorGraphTemplate {
        public byte[] toByteArray() { return new byte[0]; }
    }
}
