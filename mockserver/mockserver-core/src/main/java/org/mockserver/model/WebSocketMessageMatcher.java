package org.mockserver.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class WebSocketMessageMatcher extends ObjectWithJsonToString {
    private int hashCode;
    private WebSocketFrameType frameType;
    private NottableString textMatcher;
    private List<WebSocketMessage> responses;
    private List<List<WebSocketMessage>> turns;
    private WebSocketOnExhausted onExhausted;

    public static WebSocketMessageMatcher webSocketMessageMatcher() {
        return new WebSocketMessageMatcher().withFrameType(WebSocketFrameType.ANY);
    }

    public WebSocketFrameType getFrameType() {
        return frameType;
    }

    public WebSocketMessageMatcher withFrameType(WebSocketFrameType frameType) {
        this.frameType = frameType;
        this.hashCode = 0;
        return this;
    }

    public NottableString getTextMatcher() {
        return textMatcher;
    }

    public WebSocketMessageMatcher withText(String text) {
        this.textMatcher = NottableString.string(text);
        this.frameType = WebSocketFrameType.TEXT;
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withTextMatcher(NottableString textMatcher) {
        this.textMatcher = textMatcher;
        if (textMatcher != null) {
            this.frameType = WebSocketFrameType.TEXT;
        }
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withTextRegex(String regex) {
        this.textMatcher = NottableString.string(regex);
        this.frameType = WebSocketFrameType.TEXT;
        this.hashCode = 0;
        return this;
    }

    public List<WebSocketMessage> getResponses() {
        return responses;
    }

    public WebSocketMessageMatcher withResponses(WebSocketMessage... responses) {
        this.responses = new ArrayList<>(Arrays.asList(responses));
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withResponses(List<WebSocketMessage> responses) {
        this.responses = responses;
        this.hashCode = 0;
        return this;
    }

    public WebSocketMessageMatcher withResponse(WebSocketMessage response) {
        if (this.responses == null) {
            this.responses = new ArrayList<>();
        }
        this.responses.add(response);
        this.hashCode = 0;
        return this;
    }

    public List<List<WebSocketMessage>> getTurns() {
        return turns;
    }

    /**
     * Configure this matcher as <b>sequential</b>: successive matches against the same connection emit
     * successive turns rather than the same {@link #getResponses() responses} every time. Each turn is a
     * list of {@link WebSocketMessage} frames sent as that turn's reply; an <em>empty</em> inner list is a
     * legal turn meaning "matched, reply with nothing" (fire-and-forget). What happens once every turn has
     * been emitted is governed by {@link #withOnExhausted(WebSocketOnExhausted) onExhausted} (defaults to
     * {@link WebSocketOnExhausted#IGNORE}).
     * <p>
     * {@code turns} and {@link #withResponses(List) responses} are mutually exclusive — setting turns makes
     * the matcher sequential and the flat {@code responses} list is unused.
     */
    public WebSocketMessageMatcher withTurns(List<List<WebSocketMessage>> turns) {
        this.turns = turns;
        this.hashCode = 0;
        return this;
    }

    /**
     * Append a single turn (a list of frames to send as that turn's reply) to this matcher's sequence.
     * An empty list is a legal turn meaning "matched, reply with nothing". See
     * {@link #withTurns(List)} for the full sequential semantics.
     */
    public WebSocketMessageMatcher withTurn(List<WebSocketMessage> turn) {
        if (this.turns == null) {
            this.turns = new ArrayList<>();
        }
        this.turns.add(turn);
        this.hashCode = 0;
        return this;
    }

    public WebSocketOnExhausted getOnExhausted() {
        return onExhausted;
    }

    /**
     * Set what a sequential matcher does once every {@link #withTurns(List) turn} has been emitted on a
     * connection. Defaults (when {@code null}) to {@link WebSocketOnExhausted#IGNORE}: matches after the
     * last turn are consumed silently. {@link WebSocketOnExhausted#REPEAT} wraps back to turn 0;
     * {@link WebSocketOnExhausted#CLOSE} closes the connection after the final turn's frames are sent.
     */
    public WebSocketMessageMatcher withOnExhausted(WebSocketOnExhausted onExhausted) {
        this.onExhausted = onExhausted;
        this.hashCode = 0;
        return this;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        WebSocketMessageMatcher that = (WebSocketMessageMatcher) o;
        return frameType == that.frameType &&
            Objects.equals(textMatcher, that.textMatcher) &&
            Objects.equals(responses, that.responses) &&
            Objects.equals(turns, that.turns) &&
            onExhausted == that.onExhausted;
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            hashCode = Objects.hash(frameType, textMatcher, responses, turns, onExhausted);
        }
        return hashCode;
    }
}
