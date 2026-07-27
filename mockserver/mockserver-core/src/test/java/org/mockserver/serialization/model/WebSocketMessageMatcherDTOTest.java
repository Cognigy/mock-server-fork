package org.mockserver.serialization.model;

import org.junit.Test;
import org.mockserver.model.NottableString;
import org.mockserver.model.WebSocketFrameType;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;
import org.mockserver.model.WebSocketOnExhausted;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;

public class WebSocketMessageMatcherDTOTest {

    @Test
    public void shouldReturnResponseValuesSetInConstructor() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("ping")
            .withResponses(WebSocketMessage.webSocketMessage("pong"));

        WebSocketMessageMatcherDTO dto = new WebSocketMessageMatcherDTO(matcher);

        assertThat(dto.getFrameType(), is(WebSocketFrameType.TEXT));
        assertThat(dto.getTextMatcher().getValue(), is("ping"));
        assertThat(dto.getResponses(), hasSize(1));
        assertThat(dto.getTurns(), is(nullValue()));
        assertThat(dto.getOnExhausted(), is(nullValue()));
    }

    @Test
    public void shouldHandleNullObjectInput() {
        WebSocketMessageMatcherDTO dto = new WebSocketMessageMatcherDTO(null);

        assertThat(dto.getFrameType(), is(nullValue()));
        assertThat(dto.getTextMatcher(), is(nullValue()));
        assertThat(dto.getResponses(), is(nullValue()));
        assertThat(dto.getTurns(), is(nullValue()));
        assertThat(dto.getOnExhausted(), is(nullValue()));
    }

    @Test
    public void shouldMapTurnsAndOnExhaustedFromModel() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("verb:hook")
            .withTurn(Arrays.asList(WebSocketMessage.webSocketMessage("t0a"), WebSocketMessage.webSocketMessage("t0b")))
            .withTurn(Collections.singletonList(WebSocketMessage.webSocketMessage("t1")))
            .withOnExhausted(WebSocketOnExhausted.CLOSE);

        WebSocketMessageMatcherDTO dto = new WebSocketMessageMatcherDTO(matcher);

        assertThat(dto.getTurns(), hasSize(2));
        assertThat(dto.getTurns().get(0), hasSize(2));
        assertThat(dto.getTurns().get(0).get(0).getText(), is("t0a"));
        assertThat(dto.getTurns().get(1), hasSize(1));
        assertThat(dto.getOnExhausted(), is(WebSocketOnExhausted.CLOSE));
    }

    @Test
    public void shouldBuildTurnsAndOnExhaustedBackToModel() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("verb:hook")
            .withTurn(Collections.singletonList(WebSocketMessage.webSocketMessage("t0")))
            .withTurn(Collections.singletonList(WebSocketMessage.webSocketMessage("t1")))
            .withOnExhausted(WebSocketOnExhausted.REPEAT);

        WebSocketMessageMatcher rebuilt = new WebSocketMessageMatcherDTO(matcher).buildObject();

        assertThat(rebuilt.getTurns(), hasSize(2));
        assertThat(rebuilt.getTurns().get(0).get(0).getText(), is("t0"));
        assertThat(rebuilt.getTurns().get(1).get(0).getText(), is("t1"));
        assertThat(rebuilt.getOnExhausted(), is(WebSocketOnExhausted.REPEAT));
        assertThat(rebuilt.getResponses(), is(nullValue()));
    }

    @Test
    public void shouldPreserveEmptyTurnForFireAndForget() {
        WebSocketMessageMatcher matcher = WebSocketMessageMatcher.webSocketMessageMatcher()
            .withText("verb:status")
            .withTurn(Collections.emptyList())
            .withOnExhausted(WebSocketOnExhausted.IGNORE);

        WebSocketMessageMatcherDTO dto = new WebSocketMessageMatcherDTO(matcher);
        assertThat(dto.getTurns(), hasSize(1));
        assertThat(dto.getTurns().get(0), is(empty()));

        WebSocketMessageMatcher rebuilt = dto.buildObject();
        assertThat(rebuilt.getTurns(), hasSize(1));
        assertThat(rebuilt.getTurns().get(0), is(empty()));
    }

    @Test
    public void shouldPreferTurnsWhenBothResponsesAndTurnsSet() {
        WebSocketMessageMatcherDTO dto = new WebSocketMessageMatcherDTO()
            .setFrameType(WebSocketFrameType.TEXT)
            .setTextMatcher(NottableString.string("ping"))
            .setResponses(Collections.singletonList(
                new WebSocketMessageModelDTO(WebSocketMessage.webSocketMessage("from-responses"))))
            .setTurns(Collections.singletonList(Collections.singletonList(
                new WebSocketMessageModelDTO(WebSocketMessage.webSocketMessage("from-turns")))));

        WebSocketMessageMatcher rebuilt = dto.buildObject();

        assertThat(rebuilt.getTurns(), hasSize(1));
        assertThat(rebuilt.getTurns().get(0).get(0).getText(), is("from-turns"));
        assertThat("responses ignored when turns present", rebuilt.getResponses(), is(nullValue()));
    }

    @Test
    public void shouldReturnValuesSetViaSetter() {
        List<List<WebSocketMessageModelDTO>> turns = Collections.singletonList(
            Collections.singletonList(new WebSocketMessageModelDTO(WebSocketMessage.webSocketMessage("t0"))));

        WebSocketMessageMatcherDTO dto = new WebSocketMessageMatcherDTO()
            .setTurns(turns)
            .setOnExhausted(WebSocketOnExhausted.CLOSE);

        assertThat(dto.getTurns(), is(turns));
        assertThat(dto.getOnExhausted(), is(WebSocketOnExhausted.CLOSE));
    }
}
