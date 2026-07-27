package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.Test;
import org.mockserver.model.WebSocketFrameType;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;
import org.mockserver.model.WebSocketOnExhausted;
import org.mockserver.templates.engine.model.WebSocketFrameContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;

public class BidirectionalWebSocketFrameHandlerTest {

    @Test
    public void shouldMatchTextFrameByExactText() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    /**
     * A negated text matcher was previously accepted by the model and dropped by the DTO, and even
     * when it survived the handler ignored the flag entirely — so "respond to anything that is NOT
     * ping" matched ping and nothing else. GrpcBidiRuleMatcher already honoured its equivalent flag.
     */
    @Test
    public void shouldHonourNegatedTextMatcher() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextMatcher(org.mockserver.model.NottableString.string("hello", true));

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame matching = new TextWebSocketFrame("hello");
        try {
            assertThat("negated matcher must NOT match its own value", handler.matches(matcher, matching), is(false));
        } finally {
            matching.release();
        }

        TextWebSocketFrame other = new TextWebSocketFrame("world");
        try {
            assertThat("negated matcher must match everything else", handler.matches(matcher, other), is(true));
        } finally {
            other.release();
        }
    }

    @Test
    public void shouldNotMatchTextFrameByDifferentText() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("world");
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldMatchTextFrameByRegex() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("hello.*");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("hello world");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchTextFrameWhenRegexDoesNotMatch() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("^ping$");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("pong");
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldMatchAnyFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame textFrame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, textFrame), is(true));
        } finally {
            textFrame.release();
        }
    }

    @Test
    public void shouldNotMatchWrongFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.BINARY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame textFrame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, textFrame), is(false));
        } finally {
            textFrame.release();
        }
    }

    @Test
    public void shouldMatchTextFrameType() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.TEXT);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame textFrame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, textFrame), is(true));
        } finally {
            textFrame.release();
        }
    }

    @Test
    public void shouldHandleInvalidRegexGracefully() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex("[invalid");

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("test");
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldMatchDefaultMatcherWithNoText() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher();

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("anything");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    // --- a text matcher must not match non-text frames ---
    //
    // Reachability note: `withText`/`withTextRegex`/`withTextMatcher` all pin `frameType` to
    // TEXT, and `WebSocketMessageMatcherDTO.buildObject` calls `withText` last, so a matcher
    // built from control-plane JSON always ends up frameType=TEXT and never reaches the text
    // comparison with a non-text frame. The fall-through is therefore reachable only by setting
    // the frame type *after* the text on the Java API -- which is exactly what a user writes to
    // express "match this text on any frame type". These tests use that ordering deliberately;
    // building them the other way round would assert the frame-type guard instead and would stay
    // green with the defect present.

    /**
     * The text-content check used to be guarded by {@code frame instanceof TextWebSocketFrame},
     * so a non-text frame skipped the block entirely and fell through to {@code return true} --
     * the matcher then fired its responses on the client's keepalive PING traffic.
     */
    @Test
    public void shouldNotMatchPingFrameWithATextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat("a text matcher must not be satisfied by a PING frame",
                handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchPongFrameWithATextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        PongWebSocketFrame frame = new PongWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchBinaryFrameWithATextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        BinaryWebSocketFrame frame = new BinaryWebSocketFrame(
            io.netty.buffer.Unpooled.copiedBuffer("hello", java.nio.charset.StandardCharsets.UTF_8));
        try {
            assertThat("a text matcher cannot be satisfied by a binary frame, "
                + "even one whose bytes spell the pattern", handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    /**
     * A text matcher explicitly typed PING is self-contradictory; before the fix it matched every
     * PING regardless of the configured text.
     */
    @Test
    public void shouldNotMatchPingFrameWithATextMatcherTypedPing() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.PING);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldNotMatchNonTextFrameWithATextRegexMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    /**
     * A <em>negated</em> text matcher does not vacuously match a frame carrying no text either.
     *
     * <p>This is the interaction between the non-text guard and the negation support: treating
     * {@code not("hello")} as true for a PING would reopen the very defect the guard closes, with
     * the matcher's responses firing on client keepalive traffic. A matcher expressed in terms of
     * text content does not apply to frames that have none, in either polarity.
     */
    @Test
    public void shouldNotMatchPingFrameWithANegatedTextMatcherWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextMatcher(org.mockserver.model.NottableString.string("hello", true))
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat("a negated text matcher must not vacuously match a PING",
                handler.matches(matcher, frame), is(false));
        } finally {
            frame.release();
        }
    }

    /**
     * A matcher with no text content still matches any frame type -- only a *text* matcher is
     * restricted to text frames.
     */
    @Test
    public void shouldStillMatchPingFrameWithATypeOnlyMatcher() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.PING);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        PingWebSocketFrame frame = new PingWebSocketFrame();
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    /** A text matcher must still match the text frame it was written for. */
    @Test
    public void shouldStillMatchTextFrameWhenFrameTypeIsAny() {
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withFrameType(WebSocketFrameType.ANY);

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> {}
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("hello");
        try {
            assertThat(handler.matches(matcher, frame), is(true));
        } finally {
            frame.release();
        }
    }

    @Test
    public void shouldTrackSentResponses() {
        List<WebSocketMessage> sentMessages = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(webSocketMessage("pong"));

        BidirectionalWebSocketFrameHandler handler = new BidirectionalWebSocketFrameHandler(
            List.of(matcher), (ctx, msg, frameContext, onSent) -> sentMessages.add(msg)
        );

        TextWebSocketFrame frame = new TextWebSocketFrame("ping");
        try {
            // Verify match works correctly
            assertThat(handler.matches(matcher, frame), is(true));
            // Verify responses are configured
            assertThat(matcher.getResponses().size(), is(1));
            assertThat(matcher.getResponses().get(0).getText(), is("pong"));
        } finally {
            frame.release();
        }
    }

    // =====================================================================================
    // Emission behaviour (frame context, classic responses, sequential turns, close).
    // These drive channelRead0 through an EmbeddedChannel and capture each FrameSender call.
    // =====================================================================================

    /** One captured FrameSender invocation. onSent is retained so the test controls when it fires. */
    private static final class Sent {
        final WebSocketMessage message;
        final WebSocketFrameContext frameContext;
        final Runnable onSent;

        Sent(WebSocketMessage message, WebSocketFrameContext frameContext, Runnable onSent) {
            this.message = message;
            this.frameContext = frameContext;
            this.onSent = onSent;
        }
    }

    private static BidirectionalWebSocketFrameHandler handler(List<WebSocketMessageMatcher> matchers,
                                                              List<Sent> captured, AtomicInteger closeCount) {
        BidirectionalWebSocketFrameHandler.FrameSender sender =
            (ctx, message, frameContext, onSent) -> captured.add(new Sent(message, frameContext, onSent));
        BidirectionalWebSocketFrameHandler.ConnectionCloser closer =
            closeCount == null ? null : ctx -> closeCount.incrementAndGet();
        return new BidirectionalWebSocketFrameHandler(matchers, sender, null, null, null, null, null, closer);
    }

    @Test
    public void shouldDeliverFrameContextWithMatchedTextFrame() {
        List<Sent> captured = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("hello")
            .withResponses(webSocketMessage("world"));
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new TextWebSocketFrame("hello"));

        assertThat(captured.size(), is(1));
        assertThat(captured.get(0).message.getText(), is("world"));
        assertThat(captured.get(0).frameContext.getText(), is("hello"));
        assertThat(captured.get(0).frameContext.getBinaryBase64(), is(nullValue()));
        // classic (non-turns) responses carry a null turn index
        assertThat(captured.get(0).frameContext.getTurnIndex(), is(nullValue()));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldDeliverBase64FrameContextWithMatchedBinaryFrame() {
        List<Sent> captured = new ArrayList<>();
        byte[] payload = {1, 2, 3, 4};
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withFrameType(WebSocketFrameType.BINARY)
            .withResponses(webSocketMessage("ack"));
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new BinaryWebSocketFrame(Unpooled.copiedBuffer(payload)));

        assertThat(captured.size(), is(1));
        assertThat(captured.get(0).frameContext.getText(), is(nullValue()));
        assertThat(captured.get(0).frameContext.getBinaryBase64(), is(Base64.getEncoder().encodeToString(payload)));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldSendClassicResponsesInOrderUnchanged() {
        List<Sent> captured = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withText("ping")
            .withResponses(webSocketMessage("a"), webSocketMessage("b"), webSocketMessage("c"));
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new TextWebSocketFrame("ping"));
        channel.writeInbound(new TextWebSocketFrame("ping"));

        // Two matches -> the same three responses each time, in order.
        List<String> texts = new ArrayList<>();
        captured.forEach(s -> texts.add(s.message.getText()));
        assertThat(texts, contains("a", "b", "c", "a", "b", "c"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldAdvanceThroughTurnsOnSuccessiveMatches() {
        List<Sent> captured = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(
                List.of(webSocketMessage("t0")),
                List.of(webSocketMessage("t1a"), webSocketMessage("t1b")),
                List.of(webSocketMessage("t2"))));
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new TextWebSocketFrame("x"));
        assertThat(captured.size(), is(1));
        assertThat(captured.get(0).message.getText(), is("t0"));
        assertThat(captured.get(0).frameContext.getTurnIndex(), is(0));

        channel.writeInbound(new TextWebSocketFrame("x"));
        assertThat(captured.size(), is(3));
        assertThat(captured.get(1).message.getText(), is("t1a"));
        assertThat(captured.get(2).message.getText(), is("t1b"));
        assertThat(captured.get(1).frameContext.getTurnIndex(), is(1));
        assertThat(captured.get(2).frameContext.getTurnIndex(), is(1));

        channel.writeInbound(new TextWebSocketFrame("x"));
        assertThat(captured.size(), is(4));
        assertThat(captured.get(3).message.getText(), is("t2"));
        assertThat(captured.get(3).frameContext.getTurnIndex(), is(2));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldConsumeEmptyTurnSilentlyButStillAdvance() {
        List<Sent> captured = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(
                new ArrayList<>(),                       // empty turn: matched, reply with nothing
                List.of(webSocketMessage("after-empty"))));
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new TextWebSocketFrame("x"));
        assertThat("empty turn emits nothing", captured.size(), is(0));

        channel.writeInbound(new TextWebSocketFrame("x"));
        assertThat("cursor still advanced past the empty turn", captured.size(), is(1));
        assertThat(captured.get(0).message.getText(), is("after-empty"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldWrapToTurnZeroWhenExhaustedWithRepeat() {
        List<Sent> captured = new ArrayList<>();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(
                List.of(webSocketMessage("t0")),
                List.of(webSocketMessage("t1"))))
            .withOnExhausted(WebSocketOnExhausted.REPEAT);
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new TextWebSocketFrame("x")); // t0
        channel.writeInbound(new TextWebSocketFrame("x")); // t1 (exhausts)
        channel.writeInbound(new TextWebSocketFrame("x")); // wraps to t0
        channel.writeInbound(new TextWebSocketFrame("x")); // t1 again

        List<String> texts = new ArrayList<>();
        captured.forEach(s -> texts.add(s.message.getText()));
        assertThat(texts, contains("t0", "t1", "t0", "t1"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldConsumeSilentlyWhenExhaustedWithIgnore() {
        List<Sent> captured = new ArrayList<>();
        // onExhausted null -> defaults to IGNORE
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(List.of(webSocketMessage("only"))));
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, null));

        channel.writeInbound(new TextWebSocketFrame("x")); // emits "only", exhausts
        channel.writeInbound(new TextWebSocketFrame("x")); // exhausted -> silent
        channel.writeInbound(new TextWebSocketFrame("x")); // exhausted -> silent

        assertThat(captured.size(), is(1));
        assertThat(captured.get(0).message.getText(), is("only"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldNotFallThroughToLaterMatchersOnExhaustedIgnore() {
        List<Sent> captured = new ArrayList<>();
        WebSocketMessageMatcher sequential = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(List.of(webSocketMessage("turn"))));
        WebSocketMessageMatcher fallback = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withResponses(webSocketMessage("fallback"));
        EmbeddedChannel channel = new EmbeddedChannel(handler(Arrays.asList(sequential, fallback), captured, null));

        channel.writeInbound(new TextWebSocketFrame("x")); // turn
        channel.writeInbound(new TextWebSocketFrame("x")); // exhausted -> consumed, no fallback

        assertThat(captured.size(), is(1));
        assertThat(captured.get(0).message.getText(), is("turn"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseOnlyAfterAllFramesOfFinalTurnFlushWhenExhaustedWithClose() {
        List<Sent> captured = new ArrayList<>();
        AtomicInteger closeCount = new AtomicInteger();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(List.of(webSocketMessage("f1"), webSocketMessage("f2"))))
            .withOnExhausted(WebSocketOnExhausted.CLOSE);
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, closeCount));

        channel.writeInbound(new TextWebSocketFrame("x"));

        // Both frames of the final turn were emitted, each with a non-null onSent countdown.
        assertThat(captured.size(), is(2));
        assertThat(captured.get(0).onSent, is(org.hamcrest.Matchers.notNullValue()));
        assertThat(captured.get(1).onSent, is(org.hamcrest.Matchers.notNullValue()));

        // Close must NOT fire before every frame's onSent has run.
        assertThat(closeCount.get(), is(0));
        captured.get(0).onSent.run();
        assertThat("close withheld until the last frame flushes", closeCount.get(), is(0));
        captured.get(1).onSent.run();
        assertThat("close fires exactly once after the final frame flushes", closeCount.get(), is(1));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldCloseImmediatelyWhenFinalTurnIsEmptyWithClose() {
        List<Sent> captured = new ArrayList<>();
        AtomicInteger closeCount = new AtomicInteger();
        WebSocketMessageMatcher matcher = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(new ArrayList<>()))
            .withOnExhausted(WebSocketOnExhausted.CLOSE);
        EmbeddedChannel channel = new EmbeddedChannel(handler(List.of(matcher), captured, closeCount));

        channel.writeInbound(new TextWebSocketFrame("x"));

        assertThat("empty turn emits nothing", captured.size(), is(0));
        assertThat("empty final turn closes immediately", closeCount.get(), is(1));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepTurnCursorsIndependentAcrossHandlerInstances() {
        List<Sent> capturedA = new ArrayList<>();
        List<Sent> capturedB = new ArrayList<>();
        WebSocketMessageMatcher matcherA = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(List.of(webSocketMessage("t0")), List.of(webSocketMessage("t1"))));
        WebSocketMessageMatcher matcherB = webSocketMessageMatcher()
            .withTextRegex(".*")
            .withTurns(List.of(List.of(webSocketMessage("t0")), List.of(webSocketMessage("t1"))));
        EmbeddedChannel channelA = new EmbeddedChannel(handler(List.of(matcherA), capturedA, null));
        EmbeddedChannel channelB = new EmbeddedChannel(handler(List.of(matcherB), capturedB, null));

        // advance A twice, leave B untouched
        channelA.writeInbound(new TextWebSocketFrame("x"));
        channelA.writeInbound(new TextWebSocketFrame("x"));
        assertThat(capturedA.size(), is(2));
        assertThat(capturedA.get(0).message.getText(), is("t0"));
        assertThat(capturedA.get(1).message.getText(), is("t1"));

        // B starts fresh at turn 0
        channelB.writeInbound(new TextWebSocketFrame("x"));
        assertThat(capturedB.size(), is(1));
        assertThat(capturedB.get(0).message.getText(), is("t0"));

        channelA.finishAndReleaseAll();
        channelB.finishAndReleaseAll();
    }
}
