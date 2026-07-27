package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;

import static org.hamcrest.core.IsSame.sameInstance;
public class HttpWebSocketResponseTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(webSocketResponse(), is(webSocketResponse()));
        assertThat(webSocketResponse(), not(sameInstance(webSocketResponse())));
    }

    @Test
    public void shouldBuildWithAllFields() {
        WebSocketMessage message = webSocketMessage("hello");
        HttpWebSocketResponse response = webSocketResponse()
            .withSubprotocol("graphql-ws")
            .withMessage(message)
            .withCloseConnection(true);

        assertThat(response.getSubprotocol(), is("graphql-ws"));
        assertThat(response.getMessages(), hasSize(1));
        assertThat(response.getMessages().get(0), is(message));
        assertThat(response.getCloseConnection(), is(true));
    }

    @Test
    public void shouldHaveNullDefaultValues() {
        HttpWebSocketResponse response = webSocketResponse();

        assertThat(response.getSubprotocol(), is(nullValue()));
        assertThat(response.getMessages(), is(nullValue()));
        assertThat(response.getCloseConnection(), is(nullValue()));
    }

    @Test
    public void shouldReturnType() {
        assertThat(webSocketResponse().getType(), is(Action.Type.WEBSOCKET_RESPONSE));
    }

    @Test
    public void shouldReturnSubprotocol() {
        assertThat(webSocketResponse().withSubprotocol("graphql-ws").getSubprotocol(), is("graphql-ws"));
    }

    @Test
    public void shouldReturnCloseConnection() {
        assertThat(webSocketResponse().withCloseConnection(true).getCloseConnection(), is(true));
        assertThat(webSocketResponse().withCloseConnection(false).getCloseConnection(), is(false));
    }

    @Test
    public void shouldHaveNullDefaultCloseCode() {
        assertThat(webSocketResponse().getCloseCode(), is(nullValue()));
    }

    @Test
    public void shouldReturnCloseCode() {
        assertThat(webSocketResponse().withCloseCode(4001).getCloseCode(), is(4001));
    }

    @Test
    public void shouldBeEqualWhenSameCloseCode() {
        HttpWebSocketResponse responseOne = webSocketResponse().withCloseCode(4001);
        HttpWebSocketResponse responseTwo = webSocketResponse().withCloseCode(4001);

        assertThat(responseOne, is(responseTwo));
        assertThat(responseOne.hashCode(), is(responseTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentCloseCode() {
        assertThat(
            webSocketResponse().withCloseCode(1000),
            is(not(webSocketResponse().withCloseCode(4001)))
        );
    }

    @Test
    public void shouldAddSingleMessage() {
        WebSocketMessage messageOne = webSocketMessage("msg1");
        WebSocketMessage messageTwo = webSocketMessage("msg2");

        HttpWebSocketResponse response = webSocketResponse()
            .withMessage(messageOne)
            .withMessage(messageTwo);

        assertThat(response.getMessages(), hasSize(2));
        assertThat(response.getMessages(), containsInAnyOrder(messageOne, messageTwo));
    }

    @Test
    public void shouldAddMultipleMessagesViaVarargs() {
        WebSocketMessage messageOne = webSocketMessage("msg1");
        WebSocketMessage messageTwo = webSocketMessage("msg2");

        HttpWebSocketResponse response = webSocketResponse()
            .withMessages(messageOne, messageTwo);

        assertThat(response.getMessages(), hasSize(2));
        assertThat(response.getMessages(), containsInAnyOrder(messageOne, messageTwo));
    }

    @Test
    public void shouldAddMultipleMessagesViaList() {
        WebSocketMessage messageOne = webSocketMessage("msg1");
        WebSocketMessage messageTwo = webSocketMessage("msg2");
        List<WebSocketMessage> messages = Arrays.asList(messageOne, messageTwo);

        HttpWebSocketResponse response = webSocketResponse()
            .withMessages(messages);

        assertThat(response.getMessages(), hasSize(2));
        assertThat(response.getMessages(), containsInAnyOrder(messageOne, messageTwo));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        WebSocketMessage message = webSocketMessage("hello");
        HttpWebSocketResponse responseOne = webSocketResponse()
            .withSubprotocol("graphql-ws")
            .withMessages(message)
            .withCloseConnection(true);
        HttpWebSocketResponse responseTwo = webSocketResponse()
            .withSubprotocol("graphql-ws")
            .withMessages(message)
            .withCloseConnection(true);

        assertThat(responseOne, is(responseTwo));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        WebSocketMessage message = webSocketMessage("hello");
        HttpWebSocketResponse responseOne = webSocketResponse()
            .withSubprotocol("graphql-ws")
            .withMessages(message)
            .withCloseConnection(true);
        HttpWebSocketResponse responseTwo = webSocketResponse()
            .withSubprotocol("graphql-ws")
            .withMessages(message)
            .withCloseConnection(true);

        assertThat(responseOne.hashCode(), is(responseTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentSubprotocol() {
        assertThat(
            webSocketResponse().withSubprotocol("graphql-ws"),
            is(not(webSocketResponse().withSubprotocol("other")))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentMessages() {
        assertThat(
            webSocketResponse().withMessages(webSocketMessage("msg1")),
            is(not(webSocketResponse().withMessages(webSocketMessage("msg2"))))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentCloseConnection() {
        assertThat(
            webSocketResponse().withCloseConnection(true),
            is(not(webSocketResponse().withCloseConnection(false)))
        );
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(webSocketResponse().withSubprotocol("graphql-ws").equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(webSocketResponse().withSubprotocol("graphql-ws").equals("response"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        HttpWebSocketResponse response = webSocketResponse().withSubprotocol("graphql-ws");

        assertThat(response, is(response));
    }

    @Test
    public void shouldSupportDelay() {
        HttpWebSocketResponse response = webSocketResponse()
            .withDelay(TimeUnit.SECONDS, 3);

        assertThat(response.getDelay(), is(new Delay(TimeUnit.SECONDS, 3)));
    }

    @Test
    public void shouldSupportDelayObject() {
        HttpWebSocketResponse response = webSocketResponse()
            .withDelay(Delay.milliseconds(500));

        assertThat(response.getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 500)));
    }

    @Test
    public void shouldHaveNullDefaultMatchers() {
        HttpWebSocketResponse response = webSocketResponse();
        assertThat(response.getMatchers(), is(nullValue()));
    }

    @Test
    public void shouldAddSingleMatcher() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher().withText("ping");
        HttpWebSocketResponse response = webSocketResponse()
            .withMatcher(matcher);

        assertThat(response.getMatchers(), hasSize(1));
        assertThat(response.getMatchers().get(0), is(matcher));
    }

    @Test
    public void shouldAddMultipleMatchersViaVarargs() {
        WebSocketMessageMatcher matcher1 = webSocketMessageMatcher().withText("ping");
        WebSocketMessageMatcher matcher2 = webSocketMessageMatcher().withText("hello");

        HttpWebSocketResponse response = webSocketResponse()
            .withMatchers(matcher1, matcher2);

        assertThat(response.getMatchers(), hasSize(2));
        assertThat(response.getMatchers(), containsInAnyOrder(matcher1, matcher2));
    }

    @Test
    public void shouldAddMultipleMatchersViaList() {
        WebSocketMessageMatcher matcher1 = webSocketMessageMatcher().withText("ping");
        WebSocketMessageMatcher matcher2 = webSocketMessageMatcher().withText("hello");
        List<WebSocketMessageMatcher> matchers = Arrays.asList(matcher1, matcher2);

        HttpWebSocketResponse response = webSocketResponse()
            .withMatchers(matchers);

        assertThat(response.getMatchers(), hasSize(2));
        assertThat(response.getMatchers(), containsInAnyOrder(matcher1, matcher2));
    }

    @Test
    public void shouldAddMatcherIncrementally() {
        WebSocketMessageMatcher matcher1 = webSocketMessageMatcher().withText("ping");
        WebSocketMessageMatcher matcher2 = webSocketMessageMatcher().withText("hello");

        HttpWebSocketResponse response = webSocketResponse()
            .withMatcher(matcher1)
            .withMatcher(matcher2);

        assertThat(response.getMatchers(), hasSize(2));
    }

    @Test
    public void shouldBeEqualWhenSameMatchers() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(webSocketMessage("pong"));

        HttpWebSocketResponse responseOne = webSocketResponse()
            .withMatcher(matcher);
        HttpWebSocketResponse responseTwo = webSocketResponse()
            .withMatcher(matcher);

        assertThat(responseOne, is(responseTwo));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentMatchers() {
        assertThat(
            webSocketResponse().withMatcher(webSocketMessageMatcher().withText("ping")),
            is(not(webSocketResponse().withMatcher(webSocketMessageMatcher().withText("hello"))))
        );
    }

    @Test
    public void shouldBuildWithMatchersAndMessages() {
        WebSocketMessage message = webSocketMessage("initial");
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(webSocketMessage("pong"));

        HttpWebSocketResponse response = webSocketResponse()
            .withMessage(message)
            .withMatcher(matcher)
            .withCloseConnection(false);

        assertThat(response.getMessages(), hasSize(1));
        assertThat(response.getMatchers(), hasSize(1));
        assertThat(response.getCloseConnection(), is(false));
    }
}
