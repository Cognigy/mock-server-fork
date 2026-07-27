package org.mockserver.serialization.model;

import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.List;

public class WebSocketMessageMatcherDTO extends ObjectWithReflectiveEqualsHashCodeToString implements DTO<WebSocketMessageMatcher> {
    private WebSocketFrameType frameType;
    // held as a NottableString, not a String: collapsing to String drops the negation flag. Re-parsing
    // a leading '!' in buildObject() recovers it for most values but silently inverts any literal
    // value that itself begins with '!'.
    private NottableString textMatcher;
    private List<WebSocketMessageModelDTO> responses;
    // presence of turns makes the matcher sequential; mutually exclusive with responses (buildObject prefers
    // turns, and the JSON schema enforces the exclusion with "not": {"required": ["responses", "turns"]})
    private List<List<WebSocketMessageModelDTO>> turns;
    private WebSocketOnExhausted onExhausted;

    public WebSocketMessageMatcherDTO(WebSocketMessageMatcher matcher) {
        if (matcher != null) {
            frameType = matcher.getFrameType();
            textMatcher = matcher.getTextMatcher();
            if (matcher.getResponses() != null) {
                responses = new ArrayList<>();
                matcher.getResponses().forEach(response -> responses.add(new WebSocketMessageModelDTO(response)));
            }
            if (matcher.getTurns() != null) {
                turns = new ArrayList<>();
                matcher.getTurns().forEach(turn -> {
                    List<WebSocketMessageModelDTO> turnDTO = new ArrayList<>();
                    turn.forEach(message -> turnDTO.add(new WebSocketMessageModelDTO(message)));
                    turns.add(turnDTO);
                });
            }
            onExhausted = matcher.getOnExhausted();
        }
    }

    public WebSocketMessageMatcherDTO() {
    }

    public WebSocketMessageMatcher buildObject() {
        WebSocketMessageMatcher matcher = new WebSocketMessageMatcher();
        if (frameType != null) {
            matcher.withFrameType(frameType);
        } else {
            matcher.withFrameType(WebSocketFrameType.ANY);
        }
        if (textMatcher != null) {
            matcher.withTextMatcher(textMatcher);
        }
        // turns and responses are mutually exclusive; when both are supplied prefer turns (the sequential
        // form) and ignore responses. The JSON schema rejects the combination up-front, so this is a
        // defensive tie-break for objects built directly rather than deserialised through the schema.
        if (turns != null) {
            List<List<WebSocketMessage>> builtTurns = new ArrayList<>();
            turns.forEach(turnDTO -> {
                List<WebSocketMessage> turn = new ArrayList<>();
                if (turnDTO != null) {
                    turnDTO.forEach(dto -> turn.add(dto.buildObject()));
                }
                builtTurns.add(turn);
            });
            matcher.withTurns(builtTurns);
        } else if (responses != null) {
            List<WebSocketMessage> messages = new ArrayList<>();
            responses.forEach(dto -> messages.add(dto.buildObject()));
            matcher.withResponses(messages);
        }
        if (onExhausted != null) {
            matcher.withOnExhausted(onExhausted);
        }
        return matcher;
    }

    public WebSocketFrameType getFrameType() {
        return frameType;
    }

    public WebSocketMessageMatcherDTO setFrameType(WebSocketFrameType frameType) {
        this.frameType = frameType;
        return this;
    }

    public NottableString getTextMatcher() {
        return textMatcher;
    }

    public WebSocketMessageMatcherDTO setTextMatcher(NottableString textMatcher) {
        this.textMatcher = textMatcher;
        return this;
    }

    public List<WebSocketMessageModelDTO> getResponses() {
        return responses;
    }

    public WebSocketMessageMatcherDTO setResponses(List<WebSocketMessageModelDTO> responses) {
        this.responses = responses;
        return this;
    }

    public List<List<WebSocketMessageModelDTO>> getTurns() {
        return turns;
    }

    public WebSocketMessageMatcherDTO setTurns(List<List<WebSocketMessageModelDTO>> turns) {
        this.turns = turns;
        return this;
    }

    public WebSocketOnExhausted getOnExhausted() {
        return onExhausted;
    }

    public WebSocketMessageMatcherDTO setOnExhausted(WebSocketOnExhausted onExhausted) {
        this.onExhausted = onExhausted;
        return this;
    }
}
