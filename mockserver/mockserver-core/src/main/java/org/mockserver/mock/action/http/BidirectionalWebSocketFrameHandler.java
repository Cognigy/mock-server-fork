package org.mockserver.mock.action.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.*;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.model.WebSocketFrameType;
import org.mockserver.model.WebSocketMessage;
import org.mockserver.model.WebSocketMessageMatcher;
import org.mockserver.model.WebSocketOnExhausted;
import org.mockserver.templates.engine.model.WebSocketFrameContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.PatternSyntaxException;

/**
 * Installed after a WebSocket handshake when the HttpWebSocketResponse has matchers.
 * Evaluates incoming frames against the matcher list in order; first match sends its responses.
 *
 * <p><b>Inbound breakpoints (A1e):</b> when an INBOUND_STREAM breakpoint matcher is registered and
 * inbound stream ID is configured, incoming WebSocket frames are parked in the
 * {@link StreamFrameBreakpointRegistry} before matcher evaluation. The frame bytes are copied
 * to {@code byte[]} at park time and the original {@link WebSocketFrame} is released immediately
 * (this handler uses {@code super(false)} — no auto-release, so we manage the lifecycle). On
 * resume, a new frame is reconstructed from the captured/modified bytes.
 *
 * <p><b>Backpressure:</b> while a frame is parked, {@code autoRead} is set to {@code false}
 * on the channel, preventing further inbound frames from being read. On resume,
 * {@code autoRead} is restored and {@code ctx.read()} is called.
 */
public class BidirectionalWebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger LOG = LoggerFactory.getLogger(BidirectionalWebSocketFrameHandler.class);

    private final List<WebSocketMessageMatcher> matchers;
    private final FrameSender frameSender;
    private final ConnectionCloser connectionCloser;

    /**
     * Per-connection turn cursors, one slot per matcher (parallel to {@link #matchers}). Only used by
     * matchers configured with {@link WebSocketMessageMatcher#getTurns() turns}. This handler is
     * instantiated once per WebSocket connection and every frame is processed on that connection's single
     * event-loop thread, so no synchronization is required; the state dies with the handler on close.
     */
    private final int[] turnCursors;

    // Inbound breakpoint fields — null when inbound breakpoints are disabled
    private final Configuration configuration;
    private final String inboundStreamId;
    // Pre-computed inbound breakpoint WS dispatch fields (CPX-01)
    private final boolean inboundUseWsDispatch;
    private final String inboundBreakpointClientId;
    private final String inboundBreakpointId;
    private final WebSocketClientRegistry webSocketClientRegistry;

    /**
     * Callback interface for sending response frames to the client.
     *
     * <p>The single abstract method carries the inbound {@link WebSocketFrameContext frameContext} that
     * triggered the match (so a templated response can echo the frame) and an {@code onSent} callback
     * (nullable) invoked exactly once after the frame reaches a terminal outcome — this is how an
     * exhausted {@link WebSocketOnExhausted#CLOSE} matcher closes only after its final turn's frames have
     * all flushed.
     */
    @FunctionalInterface
    public interface FrameSender {
        void send(ChannelHandlerContext ctx, WebSocketMessage message, WebSocketFrameContext frameContext, Runnable onSent);

        /**
         * Legacy 2-arg convenience that sends without frame context or completion callback.
         *
         * @deprecated use {@link #send(ChannelHandlerContext, WebSocketMessage, WebSocketFrameContext, Runnable)}
         */
        @Deprecated
        default void send(ChannelHandlerContext ctx, WebSocketMessage message) {
            send(ctx, message, null, null);
        }
    }

    /**
     * Callback interface for closing the connection when a sequential matcher exhausts its turns with
     * {@link WebSocketOnExhausted#CLOSE}. Supplied by {@code HttpWebSocketResponseActionHandler} so the
     * close honours the configured {@code closeCode}; when {@code null} the handler falls back to
     * {@link ChannelHandlerContext#close()}.
     */
    @FunctionalInterface
    public interface ConnectionCloser {
        void close(ChannelHandlerContext ctx);
    }

    /**
     * Original constructor — no inbound breakpoint support (backward compatible).
     */
    public BidirectionalWebSocketFrameHandler(List<WebSocketMessageMatcher> matchers, FrameSender frameSender) {
        this(matchers, frameSender, null, null, null);
    }

    /**
     * Constructor with inbound breakpoint support (performs its own findMatch for backward compatibility).
     *
     * @deprecated use the constructor that accepts inboundBreakpointClientId and inboundBreakpointId
     */
    public BidirectionalWebSocketFrameHandler(List<WebSocketMessageMatcher> matchers, FrameSender frameSender,
                                              Configuration configuration, String inboundStreamId,
                                              WebSocketClientRegistry webSocketClientRegistry) {
        this(matchers, frameSender, configuration, inboundStreamId, webSocketClientRegistry, null, null);
    }

    /**
     * Constructor with inbound breakpoint support and pre-resolved breakpoint identity, but no
     * {@link ConnectionCloser} (falls back to {@link ChannelHandlerContext#close()} for turn-exhausted
     * {@link WebSocketOnExhausted#CLOSE}).
     */
    public BidirectionalWebSocketFrameHandler(List<WebSocketMessageMatcher> matchers, FrameSender frameSender,
                                              Configuration configuration, String inboundStreamId,
                                              WebSocketClientRegistry webSocketClientRegistry,
                                              String inboundBreakpointClientId, String inboundBreakpointId) {
        this(matchers, frameSender, configuration, inboundStreamId, webSocketClientRegistry,
            inboundBreakpointClientId, inboundBreakpointId, null);
    }

    /**
     * Constructor with inbound breakpoint support, pre-resolved breakpoint identity and a
     * {@link ConnectionCloser}.
     *
     * @param matchers                   the matcher list for bidirectional matching
     * @param frameSender                callback for sending response frames
     * @param configuration              the active server configuration (null to disable inbound breakpoints)
     * @param inboundStreamId            the stream ID for inbound breakpoints (null to disable)
     * @param webSocketClientRegistry    the per-server WS registry for callback dispatch (null to disable WS dispatch)
     * @param inboundBreakpointClientId  the matched inbound breakpoint's owning clientId (from outer caller)
     * @param inboundBreakpointId        the matched inbound breakpoint's id (from outer caller)
     * @param connectionCloser           closes the connection for turn-exhausted CLOSE (null → {@code ctx.close()})
     */
    public BidirectionalWebSocketFrameHandler(List<WebSocketMessageMatcher> matchers, FrameSender frameSender,
                                              Configuration configuration, String inboundStreamId,
                                              WebSocketClientRegistry webSocketClientRegistry,
                                              String inboundBreakpointClientId, String inboundBreakpointId,
                                              ConnectionCloser connectionCloser) {
        super(false); // don't auto-release frames — retain for pass-through if unmatched
        this.matchers = matchers;
        this.frameSender = frameSender;
        this.connectionCloser = connectionCloser;
        this.turnCursors = new int[matchers != null ? matchers.size() : 0];
        this.configuration = configuration;
        this.inboundStreamId = inboundStreamId;
        this.webSocketClientRegistry = webSocketClientRegistry;
        // Use the matched breakpoint's clientId and id passed from the outer caller
        // (avoids re-matching with null request which can pick the wrong breakpoint)
        if (inboundStreamId != null && inboundBreakpointClientId != null && webSocketClientRegistry != null) {
            this.inboundUseWsDispatch = true;
            this.inboundBreakpointClientId = inboundBreakpointClientId;
            this.inboundBreakpointId = inboundBreakpointId;
        } else {
            this.inboundUseWsDispatch = false;
            this.inboundBreakpointClientId = null;
            this.inboundBreakpointId = null;
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // --- Inbound breakpoint interception ---
        if (inboundStreamId != null) {

            // Copy frame bytes BEFORE parking — the frame's ByteBuf is pooled and must be
            // released after this method returns (we use super(false), so WE own the release).
            byte[] frameBytes = copyFrameBytes(frame);
            boolean isBinary = frame instanceof BinaryWebSocketFrame;
            boolean isText = frame instanceof TextWebSocketFrame;
            boolean isPing = frame instanceof PingWebSocketFrame;
            boolean isPong = frame instanceof PongWebSocketFrame;

            // Release the original frame immediately — we have a byte[] copy.
            // This is safe because we NEVER touch the frame after this point.
            frame.release();

            // Use pre-computed WS dispatch decision (CPX-01: per-stream, not per-frame)
            final java.util.concurrent.CompletableFuture<org.mockserver.mock.breakpoint.StreamFrameDecision> decisionFuture;
            if (inboundUseWsDispatch) {
                int seq = StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(inboundStreamId);
                java.util.concurrent.CompletableFuture<org.mockserver.mock.breakpoint.StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        inboundBreakpointClientId, inboundBreakpointId, inboundStreamId, seq, PausedStreamFrame.Direction.INBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.INBOUND_STREAM,
                        frameBytes, "WS-INBOUND", "/", webSocketClientRegistry, configuration, null);
                if (wsFuture != null) {
                    decisionFuture = wsFuture;
                } else {
                    // WS dispatch rejected (cap/not connected) -- process immediately
                    processInboundFrame(ctx, frameBytes, isBinary, isText, isPing, isPong);
                    return;
                }
            } else {
                PausedStreamFrame paused = StreamFrameBreakpointRegistry.getInstance()
                    .pauseFrame(inboundStreamId, frameBytes, "WS-INBOUND", "/",
                        configuration, PausedStreamFrame.Direction.INBOUND);
                if (paused == null) {
                    // Cap reached — process immediately with the copied bytes
                    processInboundFrame(ctx, frameBytes, isBinary, isText, isPing, isPong);
                    return;
                }
                decisionFuture = paused.getDecisionFuture();
            }

            // Apply backpressure: stop reading more inbound frames
            ctx.channel().config().setAutoRead(false);

            // Chain the decision onto the channel's event loop (NEVER block the event loop)
            decisionFuture.thenAccept(decision ->
                ctx.channel().eventLoop().execute(() -> {
                    try {
                        if (!ctx.channel().isActive()) {
                            return;
                        }

                        // Restore autoRead + request next frame
                        ctx.channel().config().setAutoRead(true);
                        ctx.read();

                        switch (decision.getAction()) {
                            case CONTINUE ->
                                processInboundFrame(ctx, frameBytes, isBinary, isText, isPing, isPong);
                            case MODIFY ->
                                processInboundFrame(ctx, decision.getReplacementBody(), isBinary, isText, isPing, isPong);
                            case DROP -> {
                                // Discard — do not process
                            }
                            case INJECT -> {
                                // Process the original frame, then also process the injected frame
                                processInboundFrame(ctx, frameBytes, isBinary, isText, isPing, isPong);
                                processInboundFrame(ctx, decision.getInjectedBody(), isBinary, isText, isPing, isPong);
                            }
                            case CLOSE -> {
                                StreamFrameBreakpointRegistry.getInstance().evictStream(inboundStreamId);
                                ctx.close();
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("error processing inbound breakpoint decision for stream {}", inboundStreamId, e);
                    }
                })
            ).exceptionally(ex -> {
                LOG.debug("inbound breakpoint decision callback failed for stream {}: {}", inboundStreamId, ex.getMessage());
                // Restore autoRead on failure so channel is not stuck
                ctx.channel().eventLoop().execute(() -> {
                    ctx.channel().config().setAutoRead(true);
                    ctx.read();
                });
                return null;
            });
            return;
        }

        // --- Default path (no inbound breakpoints) ---
        for (int i = 0; i < matchers.size(); i++) {
            WebSocketMessageMatcher matcher = matchers.get(i);
            if (matches(matcher, frame)) {
                emitForMatch(ctx, i, matcher, buildFrameContext(frame));
                frame.release();
                return; // first match wins
            }
        }
        // No matcher matched — pass frame through to next handler
        ctx.fireChannelRead(frame);
    }

    /**
     * Process an inbound frame from byte[] (after breakpoint resolution): reconstruct the
     * WebSocketFrame, run matcher evaluation, and either send responses or pass through.
     */
    private void processInboundFrame(ChannelHandlerContext ctx, byte[] frameBytes,
                                     boolean isBinary, boolean isText, boolean isPing, boolean isPong) {
        WebSocketFrame reconstructed = reconstructFrame(frameBytes, isBinary, isText, isPing, isPong);
        try {
            for (int i = 0; i < matchers.size(); i++) {
                WebSocketMessageMatcher matcher = matchers.get(i);
                if (matches(matcher, reconstructed)) {
                    emitForMatch(ctx, i, matcher, buildFrameContext(reconstructed));
                    return; // first match wins
                }
            }
            // No matcher matched — pass frame through (ownership transfers to pipeline)
            ctx.fireChannelRead(reconstructed.retain());
        } finally {
            reconstructed.release();
        }
    }

    /**
     * Shared emission for a matched inbound frame. Classic matchers (no {@code turns}) send every
     * configured response; sequential matchers ({@code turns} present) advance a per-connection cursor:
     * <ul>
     *   <li>cursor still within the sequence — emit that turn's frames (each carrying
     *       {@code turnIndex == cursor}) and advance; if this was the final turn and {@code onExhausted}
     *       is {@link WebSocketOnExhausted#CLOSE}, close once every frame of the turn has flushed
     *       (immediately for an empty final turn);</li>
     *   <li>cursor past the end (exhausted) — {@link WebSocketOnExhausted#REPEAT} wraps to turn 0 and
     *       emits it (the normal rule then applies, leaving the cursor at 1); anything else
     *       ({@link WebSocketOnExhausted#IGNORE} or {@code null}) consumes the frame silently.</li>
     * </ul>
     * In every {@code turns} case the frame is consumed by first-match-wins — there is no fall-through
     * to later matchers or the pipeline.
     */
    private void emitForMatch(ChannelHandlerContext ctx, int matcherIndex,
                              WebSocketMessageMatcher matcher, WebSocketFrameContext frameContext) {
        List<List<WebSocketMessage>> turns = matcher.getTurns();
        if (turns == null) {
            // Classic mode: send every configured response, unchanged apart from the added frame context.
            if (matcher.getResponses() != null) {
                for (WebSocketMessage response : matcher.getResponses()) {
                    frameSender.send(ctx, response, frameContext, null);
                }
            }
            return;
        }

        // Sequential mode: cursor machine.
        int cursor = turnCursors[matcherIndex];
        if (cursor >= turns.size()) {
            // Exhausted.
            if (matcher.getOnExhausted() == WebSocketOnExhausted.REPEAT && !turns.isEmpty()) {
                cursor = 0; // wrap and fall through to the normal emit rule
            } else {
                return; // IGNORE / null (or CLOSE already fired) — consume silently
            }
        }

        List<WebSocketMessage> turn = turns.get(cursor);
        boolean closeAfter = cursor == turns.size() - 1
            && matcher.getOnExhausted() == WebSocketOnExhausted.CLOSE;
        emitTurn(ctx, turn, cursor, closeAfter, frameContext);
        turnCursors[matcherIndex] = cursor + 1;
    }

    /**
     * Emit a single turn's frames. When {@code closeAfter} is set the connection is closed once every
     * frame in the turn has flushed (tracked by an {@link AtomicInteger} countdown over {@code onSent}),
     * or immediately when the turn is empty.
     */
    private void emitTurn(ChannelHandlerContext ctx, List<WebSocketMessage> turn, int turnIndex,
                          boolean closeAfter, WebSocketFrameContext frameContext) {
        WebSocketFrameContext turnContext = withTurnIndex(frameContext, turnIndex);
        if (turn == null || turn.isEmpty()) {
            if (closeAfter) {
                closeConnection(ctx);
            }
            return;
        }
        if (closeAfter) {
            AtomicInteger remaining = new AtomicInteger(turn.size());
            for (WebSocketMessage message : turn) {
                frameSender.send(ctx, message, turnContext, () -> {
                    if (remaining.decrementAndGet() == 0) {
                        closeConnection(ctx);
                    }
                });
            }
        } else {
            for (WebSocketMessage message : turn) {
                frameSender.send(ctx, message, turnContext, null);
            }
        }
    }

    private void closeConnection(ChannelHandlerContext ctx) {
        if (connectionCloser != null) {
            connectionCloser.close(ctx);
        } else {
            ctx.close();
        }
    }

    private static WebSocketFrameContext withTurnIndex(WebSocketFrameContext frameContext, int turnIndex) {
        return new WebSocketFrameContext(
            frameContext != null ? frameContext.getText() : null,
            frameContext != null ? frameContext.getBinaryBase64() : null,
            turnIndex);
    }

    /**
     * Build the per-frame template context from the matched inbound frame: a TEXT frame populates
     * {@code text}; a BINARY frame populates {@code binaryBase64} with the Base64-encoded payload. The
     * frame's reader index is not advanced (a defensive copy is taken for binary content).
     */
    private static WebSocketFrameContext buildFrameContext(WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame textFrame) {
            return new WebSocketFrameContext(textFrame.text(), null, null);
        }
        if (frame instanceof BinaryWebSocketFrame binaryFrame) {
            ByteBuf content = binaryFrame.content();
            byte[] bytes = new byte[content.readableBytes()];
            content.getBytes(content.readerIndex(), bytes);
            return new WebSocketFrameContext(null, Base64.getEncoder().encodeToString(bytes), null);
        }
        return new WebSocketFrameContext(null, null, null);
    }

    /**
     * Copy the readable bytes from a WebSocketFrame's content to a byte[].
     * The original ByteBuf's reader index is not advanced.
     */
    private static byte[] copyFrameBytes(WebSocketFrame frame) {
        byte[] bytes = new byte[frame.content().readableBytes()];
        frame.content().getBytes(frame.content().readerIndex(), bytes);
        return bytes;
    }

    /**
     * Reconstruct a WebSocketFrame from byte[] and type flags.
     * Returns a new frame backed by Unpooled.wrappedBuffer (no pooled ByteBuf).
     */
    private static WebSocketFrame reconstructFrame(byte[] bytes, boolean isBinary, boolean isText,
                                                   boolean isPing, boolean isPong) {
        if (isBinary) {
            return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes));
        } else if (isPing) {
            return new PingWebSocketFrame(Unpooled.wrappedBuffer(bytes));
        } else if (isPong) {
            return new PongWebSocketFrame(Unpooled.wrappedBuffer(bytes));
        } else {
            // Default to text
            return new TextWebSocketFrame(Unpooled.wrappedBuffer(bytes));
        }
    }

    boolean matches(WebSocketMessageMatcher matcher, WebSocketFrame frame) {
        // Check frame type
        WebSocketFrameType expected = matcher.getFrameType();
        if (expected != null && expected != WebSocketFrameType.ANY) {
            if (expected == WebSocketFrameType.TEXT && !(frame instanceof TextWebSocketFrame)) {
                return false;
            }
            if (expected == WebSocketFrameType.BINARY && !(frame instanceof BinaryWebSocketFrame)) {
                return false;
            }
            if (expected == WebSocketFrameType.PING && !(frame instanceof PingWebSocketFrame)) {
                return false;
            }
            if (expected == WebSocketFrameType.PONG && !(frame instanceof PongWebSocketFrame)) {
                return false;
            }
        }
        // Check text content if matcher has a text matcher
        if (matcher.getTextMatcher() != null) {
            String pattern = matcher.getTextMatcher().getValue();
            if (pattern != null && !pattern.isEmpty()) {
                // A text matcher can only be satisfied by a text frame. Previously a non-text
                // frame simply skipped this block and fell through to `return true`, so a
                // matcher configured with text content matched every PING, PONG and BINARY
                // frame as well -- and its responses fired on client keepalive traffic.
                //
                // This applies in BOTH polarities: a negated text matcher does not vacuously
                // match a frame that carries no text either. A matcher expressed in terms of
                // text content simply does not apply to frames that have none, and treating
                // `not("x")` as true for a PING would reopen the same defect for negated
                // matchers -- responses firing on client keepalive traffic.
                if (!(frame instanceof TextWebSocketFrame textFrame)) {
                    return false;
                }
                String text = textFrame.text();
                boolean matched;
                // Try exact match first, then regex
                if (text.equals(pattern)) {
                    matched = true;
                } else {
                    try {
                        matched = text.matches(pattern);
                    } catch (PatternSyntaxException e) {
                        matched = false;
                    }
                }
                // honour the negation flag, as GrpcBidiRuleMatcher does for its equivalent rule
                // matcher — without this a negated text matcher is accepted and silently ignored
                return matcher.getTextMatcher().isNot() != matched;
            }
        }
        return true;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Evict any held inbound frames on channel close to prevent leaks
        if (inboundStreamId != null) {
            StreamFrameBreakpointRegistry.getInstance().evictStream(inboundStreamId);
        }
        super.channelInactive(ctx);
    }
}
