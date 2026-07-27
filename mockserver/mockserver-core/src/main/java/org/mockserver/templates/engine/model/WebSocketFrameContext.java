package org.mockserver.templates.engine.model;

/**
 * Per-frame template variable exposed to a matcher-response template under the key {@code "frame"},
 * sibling of {@code request}. It carries the inbound WebSocket frame that triggered the matcher so a
 * templated response can echo or transform the frame's contents (e.g. echo a {@code msgid} back in an
 * ack), while the {@code request} variable keeps binding to the original HTTP upgrade request.
 *
 * <p>Exactly one of {@link #getText()} / {@link #getBinaryBase64()} is non-null for a given frame:
 * a TEXT frame populates {@code text} (and leaves {@code binaryBase64} null); a BINARY frame populates
 * {@code binaryBase64} with the Base64-encoded frame payload (and leaves {@code text} null). When the
 * frame carries text, the template engines' {@code jsonPath} / {@code xPath} helpers operate on that
 * frame text rather than the upgrade request body.
 *
 * <p>Plain JavaBean getters so all three engine syntaxes resolve identically:
 * {@code $frame.text} (Velocity), {@code {{frame.text}}} (Mustache) and {@code frame.text} (JavaScript,
 * where the whole context is serialised to JSON so {@code frame.text} is a plain string and
 * {@code JSON.parse(frame.text)} works natively).
 *
 * <ul>
 *   <li>{@link #getText()} — the TEXT frame payload, or {@code null} for a BINARY frame</li>
 *   <li>{@link #getBinaryBase64()} — the Base64-encoded BINARY frame payload, or {@code null} for a TEXT frame</li>
 *   <li>{@link #getTurnIndex()} — the 0-based turn index in a sequential-turns response, or {@code null} when not sequential</li>
 * </ul>
 */
public class WebSocketFrameContext {

    private final String text;
    private final String binaryBase64;
    private final Integer turnIndex;

    public WebSocketFrameContext(String text, String binaryBase64, Integer turnIndex) {
        this.text = text;
        this.binaryBase64 = binaryBase64;
        this.turnIndex = turnIndex;
    }

    /**
     * The TEXT frame payload, or {@code null} for a BINARY frame.
     */
    public String getText() {
        return text;
    }

    /**
     * The Base64-encoded BINARY frame payload, or {@code null} for a TEXT frame.
     */
    public String getBinaryBase64() {
        return binaryBase64;
    }

    /**
     * The 0-based turn index within a sequential-turns response, or {@code null} when the response is
     * not sequential.
     */
    public Integer getTurnIndex() {
        return turnIndex;
    }
}
