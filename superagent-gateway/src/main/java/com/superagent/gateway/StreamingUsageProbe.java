package com.superagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;

/**
 * Incremental scan of a streamed upstream body for a final vendor {@code usage} total.
 * Only reports a total when the stream completed successfully (R06); initial Anthropic
 * {@code message_start} usage alone is not enough.
 */
final class StreamingUsageProbe {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int JSON_TAIL_CAP = 64 * 1024;
    private static final int LINE_CAP = 256 * 1024;

    private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    private final StringBuilder line = new StringBuilder();
    private final StringBuilder jsonTail = new StringBuilder();
    private final ByteBuffer leftover = ByteBuffer.allocate(4);
    private boolean sse;
    private long inputish;
    private long output;
    private boolean hasInput;
    private boolean hasOutput;
    private boolean completed;
    private boolean failed;

    void accept(byte[] buf, int off, int len) {
        if (buf == null || len <= 0) {
            return;
        }
        ByteBuffer in = ByteBuffer.allocate(leftover.position() + len);
        leftover.flip();
        in.put(leftover);
        leftover.clear();
        in.put(buf, off, len);
        in.flip();
        CharBuffer chars = CharBuffer.allocate(in.remaining());
        CoderResult cr = decoder.decode(in, chars, false);
        if (in.hasRemaining()) {
            leftover.put(in);
        }
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
        // Flush UTF-8 remainder (#Important UTF-8).
        CharBuffer chars = CharBuffer.allocate(8);
        decoder.decode(ByteBuffer.allocate(0), chars, true);
        decoder.flush(chars);
        chars.flip();
        for (int i = 0; i < chars.length(); i++) {
            char c = chars.charAt(i);
            if (c == '\n') {
                finishLine();
            } else if (c != '\r' && line.length() < LINE_CAP) {
                line.append(c);
            }
        }
        finishLine();
        if (!sse && !failed) {
            if (!hasInput && !hasOutput && jsonTail.length() > 0) {
                VendorUsageParser.parse(jsonTail.toString()).ifPresent(this::merge);
            }
            if (hasInput || hasOutput) {
                completed = true; // non-stream JSON body is complete when the HTTP body ends
            }
        }
    }

    OptionalLong total() {
        if (failed || !completed) {
            return OptionalLong.empty();
        }
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
            if (payload.isEmpty()) {
                return;
            }
            if ("[DONE]".equalsIgnoreCase(payload)) {
                completed = true;
                return;
            }
            observeEvent(payload);
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

    private void observeEvent(String payload) {
        try {
            JsonNode root = JSON.readTree(payload);
            String type = root.path("type").asText("");
            if ("error".equals(type)
                    || "response.failed".equals(type)
                    || "response.incomplete".equals(type)) {
                failed = true;
                return;
            }
            if (root.has("error") && !type.startsWith("message_") && !type.startsWith("response.")) {
                failed = true;
                return;
            }
            if ("message_stop".equals(type) || "response.completed".equals(type)) {
                completed = true;
            }
        } catch (Exception ignored) {
            // non-JSON data lines still feed the usage parser
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
