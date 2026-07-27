package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;

import static org.hamcrest.core.IsSame.sameInstance;
public class WebSocketMessageMatcherTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(webSocketMessageMatcher(), is(webSocketMessageMatcher()));
        assertThat(webSocketMessageMatcher(), not(sameInstance(webSocketMessageMatcher())));
    }

    @Test
    public void shouldHaveDefaultFrameTypeAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher();
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.ANY));
        assertThat(matcher.getTextMatcher(), is(nullValue()));
        assertThat(matcher.getResponses(), is(nullValue()));
    }

    @Test
    public void shouldSetTextAndFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello");

        assertThat(matcher.getTextMatcher().getValue(), is("hello"));
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.TEXT));
    }

    @Test
    public void shouldSetTextRegex() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("hello.*");

        assertThat(matcher.getTextMatcher().getValue(), is("hello.*"));
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.TEXT));
    }

    @Test
    public void shouldSetTextMatcher() {
        NottableString nottable = NottableString.string("test");
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextMatcher(nottable);

        assertThat(matcher.getTextMatcher(), is(nottable));
        assertThat(matcher.getFrameType(), is(WebSocketFrameType.TEXT));
    }

    @Test
    public void shouldSetFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.BINARY);

        assertThat(matcher.getFrameType(), is(WebSocketFrameType.BINARY));
    }

    @Test
    public void shouldSetResponsesViaVarargs() {
        WebSocketMessage response1 = webSocketMessage("reply1");
        WebSocketMessage response2 = webSocketMessage("reply2");

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response1, response2);

        assertThat(matcher.getResponses(), hasSize(2));
        assertThat(matcher.getResponses(), containsInAnyOrder(response1, response2));
    }

    @Test
    public void shouldSetResponsesViaList() {
        WebSocketMessage response1 = webSocketMessage("reply1");
        WebSocketMessage response2 = webSocketMessage("reply2");
        List<WebSocketMessage> responses = Arrays.asList(response1, response2);

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(responses);

        assertThat(matcher.getResponses(), hasSize(2));
        assertThat(matcher.getResponses(), containsInAnyOrder(response1, response2));
    }

    @Test
    public void shouldAddSingleResponse() {
        WebSocketMessage response1 = webSocketMessage("reply1");
        WebSocketMessage response2 = webSocketMessage("reply2");

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponse(response1)
            .withResponse(response2);

        assertThat(matcher.getResponses(), hasSize(2));
        assertThat(matcher.getResponses(), containsInAnyOrder(response1, response2));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        WebSocketMessage response = webSocketMessage("reply");
        WebSocketMessageMatcher matcherOne = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);
        WebSocketMessageMatcher matcherTwo = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);

        assertThat(matcherOne, is(matcherTwo));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        WebSocketMessage response = webSocketMessage("reply");
        WebSocketMessageMatcher matcherOne = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);
        WebSocketMessageMatcher matcherTwo = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(response);

        assertThat(matcherOne.hashCode(), is(matcherTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentText() {
        assertThat(
            webSocketMessageMatcher().withText("hello"),
            is(not(webSocketMessageMatcher().withText("world")))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentFrameType() {
        assertThat(
            webSocketMessageMatcher().withFrameType(WebSocketFrameType.TEXT),
            is(not(webSocketMessageMatcher().withFrameType(WebSocketFrameType.BINARY)))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentResponses() {
        assertThat(
            webSocketMessageMatcher().withResponses(webSocketMessage("a")),
            is(not(webSocketMessageMatcher().withResponses(webSocketMessage("b"))))
        );
    }

    @Test
    public void shouldHaveNullDefaultTurnsAndOnExhausted() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher();
        assertThat(matcher.getTurns(), is(nullValue()));
        assertThat(matcher.getOnExhausted(), is(nullValue()));
    }

    @Test
    public void shouldSetTurnsViaList() {
        List<List<WebSocketMessage>> turns = Arrays.asList(
            Arrays.asList(webSocketMessage("turn0-a"), webSocketMessage("turn0-b")),
            Collections.singletonList(webSocketMessage("turn1"))
        );

        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withTurns(turns);

        assertThat(matcher.getTurns(), hasSize(2));
        assertThat(matcher.getTurns().get(0), hasSize(2));
        assertThat(matcher.getTurns().get(1), hasSize(1));
    }

    @Test
    public void shouldAppendSingleTurn() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withTurn(Collections.singletonList(webSocketMessage("turn0")))
            .withTurn(Collections.singletonList(webSocketMessage("turn1")));

        assertThat(matcher.getTurns(), hasSize(2));
        assertThat(matcher.getTurns().get(0), contains(webSocketMessage("turn0")));
        assertThat(matcher.getTurns().get(1), contains(webSocketMessage("turn1")));
    }

    @Test
    public void shouldAllowEmptyTurnForFireAndForget() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("status")
            .withTurn(Collections.emptyList());

        assertThat(matcher.getTurns(), hasSize(1));
        assertThat(matcher.getTurns().get(0), is(empty()));
    }

    @Test
    public void shouldSetOnExhausted() {
        assertThat(
            webSocketMessageMatcher().withOnExhausted(WebSocketOnExhausted.CLOSE).getOnExhausted(),
            is(WebSocketOnExhausted.CLOSE)
        );
        assertThat(
            webSocketMessageMatcher().withOnExhausted(WebSocketOnExhausted.REPEAT).getOnExhausted(),
            is(WebSocketOnExhausted.REPEAT)
        );
    }

    @Test
    public void shouldBeEqualWhenSameTurnsAndOnExhausted() {
        WebSocketMessageMatcher matcherOne = webSocketMessageMatcher()
            .withText("ping")
            .withTurn(Collections.singletonList(webSocketMessage("reply")))
            .withOnExhausted(WebSocketOnExhausted.REPEAT);
        WebSocketMessageMatcher matcherTwo = webSocketMessageMatcher()
            .withText("ping")
            .withTurn(Collections.singletonList(webSocketMessage("reply")))
            .withOnExhausted(WebSocketOnExhausted.REPEAT);

        assertThat(matcherOne, is(matcherTwo));
        assertThat(matcherOne.hashCode(), is(matcherTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentTurns() {
        assertThat(
            webSocketMessageMatcher().withTurn(Collections.singletonList(webSocketMessage("a"))),
            is(not(webSocketMessageMatcher().withTurn(Collections.singletonList(webSocketMessage("b")))))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentOnExhausted() {
        assertThat(
            webSocketMessageMatcher().withText("ping").withOnExhausted(WebSocketOnExhausted.CLOSE),
            is(not(webSocketMessageMatcher().withText("ping").withOnExhausted(WebSocketOnExhausted.REPEAT)))
        );
    }

    @Test
    public void shouldResetCachedHashCodeWhenTurnsMutated() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher().withText("ping");
        int before = matcher.hashCode();
        matcher.withTurn(Collections.singletonList(webSocketMessage("reply")));
        assertThat(matcher.hashCode(), is(not(before)));
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(webSocketMessageMatcher().withText("hello").equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(webSocketMessageMatcher().withText("hello").equals("hello"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher().withText("hello");
        assertThat(matcher, is(matcher));
    }
}
