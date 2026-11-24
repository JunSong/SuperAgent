package com.superagent.gateway;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * Incremental scan of a streamed upstream body for a final vendor {@code usage} total.
 * Does not retain the whole response — only a line buffer for SSE / a capped JSON scrape for non-stream.
 * Anthropic may split input and output across events; snippets are merged.
 */
final class StreamingUsageProbe {

    private static final int JSON_TAIL_CAP = 64 * 1024;
    private static final int LINE_CAP = 256 * 1024;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder jsonTail = new StringBuilder();
    private boolean sse;
    private long inputish;
    private long output;
    private boolean hasInput;
    private boolean hasOutput;

    void accept(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) {
            return;
        }
        CharBuffer chars = CharBuffer.allocate(len);
        CoderResult cr = decoder.decode(ByteBuffer.wrap(buf, off, len), chars, false);
        if (cr.isError()) {
            return;
        }
        chars.flip();
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (c == '\n') {
                finishLine();
            } else if (c != '\r') {
                if (line.length() < LINE_CAP) {
                    line.append(c);
                }
            }
        }
    }

    void finish() {
        finishLine();
        if (!sse && !hasInput && !hasOutput && jsonTail.length() > 0) {
            VendorUsageParser.parse(jsonTail.toString()).ifPresent(this::merge);
        }
    }

    OptionalLong total() {
        if (!hasInput && !hasOutput) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(Math.max(0, inputish) + Math.max(0, output));
    }

    private void finishLine() {
        if (line.isEmpty()) {
            return;
        }
        String raw = line.toString();
        line.setLength(0);
        String t = raw.strip();
        if (t.regionMatches(true, 0, "data:", 0, 5)) {
            sse = true;
            String payload = t.substring(5).strip();
            if (payload.isEmpty() || "[DONE]".equalsIgnoreCase(payload)) {
                return;
            }
            VendorUsageParser.parse(payload).ifPresent(this::merge);
            return;
        }
        if (!sse) {
            appendJsonTail(raw);
            if (t.startsWith("{") || t.endsWith("}")) {
                VendorUsageParser.parse(jsonTail.toString()).ifPresent(this::merge);
            }
        }
    }

    private void merge(VendorUsageParser.Snippet s) {
        if (s.hasInput()) {
            inputish = s.inputish();
            hasInput = true;
        }
        if (s.hasOutput()) {
            output = s.output();
            hasOutput = true;
        }
    }

    private void appendJsonTail(String chunk) {
        jsonTail.append(chunk);
        if (jsonTail.length() > JSON_TAIL_CAP) {
            jsonTail.delete(0, jsonTail.length() - JSON_TAIL_CAP);
        }
    }
}
