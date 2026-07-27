package org.mockserver.mock.action.http;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.breakpoint.PausedStreamFrame;
import org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry;
import org.mockserver.mock.breakpoint.StreamFrameDecision;
import org.mockserver.model.*;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_RESPONSE;

public class HttpWebSocketResponseActionHandler {

    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final Configuration configuration;
    private final WebSocketClientRegistry webSocketClientRegistry;
    private final StreamTemplateRenderer templateRenderer;

    public HttpWebSocketResponseActionHandler(MockServerLogger mockServerLogger, Scheduler scheduler, Configuration configuration, WebSocketClientRegistry webSocketClientRegistry) {
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.configuration = configuration;
        this.webSocketClientRegistry = webSocketClientRegistry;
        this.templateRenderer = new StreamTemplateRenderer(mockServerLogger, configuration);
    }

    public void handle(HttpWebSocketResponse httpWebSocketResponse, ChannelHandlerContext ctx, org.mockserver.model.HttpRequest request) {
        FullHttpRequest nettyRequest = buildNettyRequest(request);
        String host = request.getFirstHeader("Host");
        String uri = request.getPath().getValue();
        String scheme = ctx.pipeline().get(SslHandler.class) != null ? "wss" : "ws";
        String wsUrl = scheme + "://" + (host != null ? host : "localhost") + uri;
        String subprotocol = httpWebSocketResponse.getSubprotocol();

        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
            wsUrl, subprotocol, true, 65536
        );
        WebSocketServerHandshaker handshaker = wsFactory.newHandshaker(nettyRequest);

        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            nettyRequest.release();
            return;
        }

        // Pre-compute breakpoint matchers once per stream/connection (CPX-01: reuse for all frames)
        final org.mockserver.mock.breakpoint.BreakpointMatcher streamBreakpointMatcher = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM);
        final boolean streamBreakpointsActive = streamBreakpointMatcher != null;
        final org.mockserver.mock.breakpoint.BreakpointMatcher inboundBreakpointMatcher = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.INBOUND_STREAM);
        final boolean inboundBreakpointsActive = inboundBreakpointMatcher != null;
        final String streamId;
        final String reqMethod;
        final String reqPath;
        final String inboundStreamId;
        // Pre-compute WS dispatch flag (CPX-01: compute once, reuse for all frames)
        final boolean useWsDispatch;
        final String breakpointClientId;
        String correlationId = (request.getLogCorrelationId() != null
            ? request.getLogCorrelationId() : java.util.UUID.randomUUID().toString());
        if (streamBreakpointsActive) {
            streamId = correlationId + "-ws-stream";
            reqMethod = request.getMethod() != null ? request.getMethod().getValue() : null;
            reqPath = request.getPath() != null ? request.getPath().getValue() : null;
            breakpointClientId = streamBreakpointMatcher.getClientId();
            useWsDispatch = breakpointClientId != null && webSocketClientRegistry != null;
        } else {
            streamId = null;
            reqMethod = null;
            reqPath = null;
            useWsDispatch = false;
            breakpointClientId = null;
        }
        if (inboundBreakpointsActive) {
            inboundStreamId = correlationId + "-ws-inbound";
        } else {
            inboundStreamId = null;
        }

        nettyRequest.retain();
        handshaker.handshake(ctx.channel(), nettyRequest).addListener(future -> {
            try {
                if (future.isSuccess()) {
                    // fire cross-protocol event for WebSocket connect
                    org.mockserver.mock.CrossProtocolEventBus.getInstance().fire(
                        CrossProtocolTrigger.WEBSOCKET_CONNECT,
                        request.getPath() != null ? request.getPath().getValue() : "/"
                    );
                    removePipelineHandlers(ctx);

                    // Answer PING with PONG and echo CLOSE (RFC 6455 §5.5.1/§5.5.2). The manual
                    // handshake installs only the frame codec, so without this every long-lived
                    // mocked session dies as soon as the client starts sending keepalive pings.
                    ctx.pipeline().addLast("webSocketControlFrameHandler",
                        new WebSocketControlFrameHandler(handshaker));

                    // Check if this is a GraphQL subscription WebSocket
                    if (GraphQLSubscriptionHandler.isGraphQLWebSocketProtocol(httpWebSocketResponse.getSubprotocol())
                        && httpWebSocketResponse.getGraphqlSubscriptionFilter() != null) {
                        installGraphQLSubscriptionHandler(ctx, httpWebSocketResponse, handshaker,
                            streamBreakpointsActive, streamId, reqMethod, reqPath, inboundStreamId,
                            useWsDispatch, breakpointClientId,
                            streamBreakpointMatcher != null ? streamBreakpointMatcher.getId() : null,
                            inboundBreakpointMatcher != null ? inboundBreakpointMatcher.getClientId() : null,
                            inboundBreakpointMatcher != null ? inboundBreakpointMatcher.getId() : null);
                    } else {
                        installBidirectionalHandler(ctx, httpWebSocketResponse, request, handshaker,
                            streamBreakpointsActive, streamId, reqMethod, reqPath, inboundStreamId,
                            useWsDispatch, breakpointClientId,
                            streamBreakpointMatcher != null ? streamBreakpointMatcher.getId() : null,
                            inboundBreakpointMatcher != null ? inboundBreakpointMatcher.getClientId() : null,
                            inboundBreakpointMatcher != null ? inboundBreakpointMatcher.getId() : null);

                        List<WebSocketMessage> messages = httpWebSocketResponse.getMessages();
                        if (messages != null && !messages.isEmpty()) {
                            scheduleMessages(messages, 0, ctx, httpWebSocketResponse, request, handshaker,
                                streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId,
                                streamBreakpointMatcher != null ? streamBreakpointMatcher.getId() : null);
                        } else if (httpWebSocketResponse.getMatchers() == null || httpWebSocketResponse.getMatchers().isEmpty()) {
                            finishWebSocket(ctx, httpWebSocketResponse, handshaker, streamBreakpointsActive, streamId);
                        }
                    }
                } else {
                    if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("WebSocket handshake failed for request:{}")
                                .setArguments(request)
                        );
                    }
                }
            } finally {
                nettyRequest.release();
            }
        });
        nettyRequest.release();
    }

    /**
     * A 1000 NORMAL_CLOSURE frame. An empty close frame (no status code) is legal but leaves the
     * client reporting close code 1005 "no status received", which several clients treat as an
     * abnormal termination. Used by the GraphQL subscription path, which always closes with 1000.
     */
    private static CloseWebSocketFrame normalClosure() {
        return new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE);
    }

    /**
     * The close frame the server sends when it closes a mocked WebSocket. When the response carries no
     * {@code closeCode} (the default) this is a 1000 NORMAL_CLOSURE frame — an empty close frame (no
     * status code) is legal but leaves the client reporting close code 1005 "no status received", which
     * several clients treat as an abnormal termination. When a {@code closeCode} is configured it is sent
     * verbatim (with an empty reason) if valid per RFC 6455 ({@link WebSocketCloseStatus#isValidStatusCode(int)},
     * which admits 1000–1003, 1007–1011 and the application range 3000–4999); an invalid code is logged
     * at WARN and falls back to 1000.
     */
    private CloseWebSocketFrame closeFrame(HttpWebSocketResponse httpWebSocketResponse) {
        Integer closeCode = httpWebSocketResponse.getCloseCode();
        if (closeCode == null) {
            return new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE);
        }
        if (!WebSocketCloseStatus.isValidStatusCode(closeCode)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("invalid WebSocket closeCode {} - falling back to 1000 (normal closure)")
                        .setArguments(closeCode)
                );
            }
            return new CloseWebSocketFrame(WebSocketCloseStatus.NORMAL_CLOSURE);
        }
        return new CloseWebSocketFrame(closeCode, "");
    }

    /**
     * The subprotocol actually agreed during the handshake, which decides whether the legacy
     * {@code graphql-ws} vocabulary is spoken.
     *
     * <p>Only the handshaker's selection counts. Falling back to the <em>configured</em>
     * subprotocol would be wrong here: the sole caller is already gated on the configured value
     * being a GraphQL subprotocol, so the fallback would always name one — and a client that
     * requested no subprotocol at all (leaving {@code selectedSubprotocol()} null) would then be
     * answered in the legacy vocabulary it never asked for. When nothing was negotiated the
     * modern graphql-transport-ws vocabulary is used, which is the protocol's own default.
     */
    private static String negotiatedSubprotocol(WebSocketServerHandshaker handshaker) {
        return handshaker != null ? handshaker.selectedSubprotocol() : null;
    }

    private void removePipelineHandlers(ChannelHandlerContext ctx) {
        try {
            ctx.pipeline().remove(MockServerHttpServerCodec.class);
        } catch (Exception ignored) {
        }
        try {
            for (Map.Entry<String, ChannelHandler> entry : ctx.pipeline()) {
                if ("HttpRequestHandler".equals(entry.getValue().getClass().getSimpleName())) {
                    ctx.pipeline().remove(entry.getKey());
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void scheduleMessages(List<WebSocketMessage> messages, int index, ChannelHandlerContext ctx,
                                  HttpWebSocketResponse httpWebSocketResponse, org.mockserver.model.HttpRequest request,
                                  WebSocketServerHandshaker handshaker,
                                  boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath,
                                  boolean useWsDispatch, String breakpointClientId,
                                  String streamBreakpointId) {
        if (index >= messages.size() || !ctx.channel().isActive()) {
            boolean hasMatchers = httpWebSocketResponse.getMatchers() != null && !httpWebSocketResponse.getMatchers().isEmpty();
            if (!hasMatchers) {
                finishWebSocket(ctx, httpWebSocketResponse, handshaker, streamBreakpointsActive, streamId);
            }
            return;
        }

        WebSocketMessage message = messages.get(index);
        Delay delay = message.getDelay();

        Runnable writeMessage = () -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }

                // Extract frame bytes for breakpoint interception
                byte[] frameBytes;
                boolean isBinary;
                if (message.getBinary() != null) {
                    frameBytes = message.getBinary();
                    isBinary = true;
                } else if (message.getText() != null) {
                    // Opt-in templating: render the text payload against the triggering request when a
                    // templateType is set on the response (binary frames are never templated). Rendering
                    // happens before breakpoint dispatch so interception observes the rendered bytes.
                    String text = httpWebSocketResponse.getTemplateType() != null
                        ? templateRenderer.render(httpWebSocketResponse.getTemplateType(), message.getText(), request)
                        : message.getText();
                    frameBytes = text.getBytes(StandardCharsets.UTF_8);
                    isBinary = false;
                } else {
                    scheduleMessages(messages, index + 1, ctx, httpWebSocketResponse, request, handshaker,
                        streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }

                if (!streamBreakpointsActive) {
                    // Default-off fast path: write immediately
                    writeWebSocketFrame(frameBytes, isBinary, ctx, request, messages, index, httpWebSocketResponse,
                        handshaker, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }

                // --- Stream-frame breakpoint path ---
                // Use pre-computed WS dispatch decision (CPX-01: per-stream, not per-frame)
                // WS-callback dispatch (clientId is always present — required since 7b)
                final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;
                int seq = StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(streamId);
                java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                    org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                        breakpointClientId, streamBreakpointId, streamId, seq, PausedStreamFrame.Direction.OUTBOUND,
                        org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                        frameBytes, reqMethod, reqPath, webSocketClientRegistry, configuration, mockServerLogger);
                if (wsFuture != null) {
                    decisionFuture = wsFuture;
                } else {
                    // WS dispatch rejected (cap/not connected) -- write immediately
                    writeWebSocketFrame(frameBytes, isBinary, ctx, request, messages, index, httpWebSocketResponse,
                        handshaker, streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                    return;
                }

                // Frame is parked. Chain the decision callback onto the channel's event loop.
                final boolean finalIsBinary = isBinary;
                final byte[] capturedFrameBytes = frameBytes;
                decisionFuture.thenAccept(decision ->
                    ctx.channel().eventLoop().execute(() -> {
                        if (!ctx.channel().isActive()) {
                            scheduleMessages(messages, index + 1, ctx, httpWebSocketResponse, request, handshaker,
                                streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            return;
                        }
                        switch (decision.getAction()) {
                            case CONTINUE -> writeWebSocketFrame(capturedFrameBytes, finalIsBinary, ctx, request,
                                messages, index, httpWebSocketResponse, handshaker, streamBreakpointsActive, streamId,
                                reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case MODIFY -> writeWebSocketFrame(decision.getReplacementBody(), finalIsBinary, ctx, request,
                                messages, index, httpWebSocketResponse, handshaker, streamBreakpointsActive, streamId,
                                reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case DROP ->
                                scheduleMessages(messages, index + 1, ctx, httpWebSocketResponse, request, handshaker,
                                    streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
                            case INJECT -> {
                                // Write original frame, then inject extra, then proceed
                                WebSocketFrame originalFrame = finalIsBinary
                                    ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(capturedFrameBytes))
                                    : new TextWebSocketFrame(Unpooled.wrappedBuffer(capturedFrameBytes));
                                ctx.writeAndFlush(originalFrame).addListener(future -> {
                                    if (ctx.channel().isActive()) {
                                        WebSocketFrame injectedFrame = finalIsBinary
                                            ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(decision.getInjectedBody()))
                                            : new TextWebSocketFrame(Unpooled.wrappedBuffer(decision.getInjectedBody()));
                                        ctx.writeAndFlush(injectedFrame).addListener(f2 ->
                                            scheduleMessages(messages, index + 1, ctx, httpWebSocketResponse, request,
                                                handshaker, streamBreakpointsActive, streamId, reqMethod, reqPath,
                                                useWsDispatch, breakpointClientId, streamBreakpointId));
                                    } else {
                                        scheduleMessages(messages, index + 1, ctx, httpWebSocketResponse, request,
                                            handshaker, streamBreakpointsActive, streamId, reqMethod, reqPath,
                                            useWsDispatch, breakpointClientId, streamBreakpointId);
                                    }
                                });
                            }
                            case CLOSE -> {
                                StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                                finishWebSocket(ctx, httpWebSocketResponse, handshaker, false, null);
                            }
                        }
                    })
                ).exceptionally(ex -> {
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.DEBUG)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setMessageFormat("stream frame decision callback failed for WebSocket eager stream{}:{}")
                                .setArguments(streamId, ex.getMessage())
                        );
                    }
                    return null;
                });
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception sending WebSocket message {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(e)
                    );
                }
                finishWebSocket(ctx, httpWebSocketResponse, handshaker, streamBreakpointsActive, streamId);
            }
        };

        if (delay != null) {
            scheduler.schedule(writeMessage, false, delay);
        } else {
            writeMessage.run();
        }
    }

    /**
     * Writes a WebSocket frame (byte[]) to the channel and chains to the next message on success.
     * Shared between the default-off fast path and the breakpoint resume path.
     */
    private void writeWebSocketFrame(byte[] frameBytes, boolean isBinary, ChannelHandlerContext ctx,
                                     org.mockserver.model.HttpRequest request, List<WebSocketMessage> messages,
                                     int index, HttpWebSocketResponse httpWebSocketResponse,
                                     WebSocketServerHandshaker handshaker,
                                     boolean streamBreakpointsActive, String streamId, String reqMethod, String reqPath,
                                     boolean useWsDispatch, String breakpointClientId, String streamBreakpointId) {
        WebSocketFrame frame = isBinary
            ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(frameBytes))
            : new TextWebSocketFrame(Unpooled.wrappedBuffer(frameBytes));

        ctx.writeAndFlush(frame).addListener(future -> {
            if (future.isSuccess()) {
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(EXPECTATION_RESPONSE)
                            .setLogLevel(Level.DEBUG)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("sent WebSocket message {} of {} for request:{}")
                            .setArguments(index + 1, messages.size(), request)
                    );
                }
                scheduleMessages(messages, index + 1, ctx, httpWebSocketResponse, request, handshaker,
                    streamBreakpointsActive, streamId, reqMethod, reqPath, useWsDispatch, breakpointClientId, streamBreakpointId);
            } else {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("async write failure for WebSocket message {} for request:{}")
                            .setArguments(index + 1, request)
                            .setThrowable(future.cause())
                    );
                }
                finishWebSocket(ctx, httpWebSocketResponse, handshaker, streamBreakpointsActive, streamId);
            }
        });
    }

    private void finishWebSocket(ChannelHandlerContext ctx, HttpWebSocketResponse httpWebSocketResponse,
                                 WebSocketServerHandshaker handshaker,
                                 boolean streamBreakpointsActive, String streamId) {
        if (streamBreakpointsActive && streamId != null) {
            StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
        }
        if (ctx.channel().isActive()) {
            if (httpWebSocketResponse.getCloseConnection() == null || httpWebSocketResponse.getCloseConnection()) {
                handshaker.close(ctx.channel(), closeFrame(httpWebSocketResponse));
            }
        }
    }

    private void installGraphQLSubscriptionHandler(ChannelHandlerContext ctx, HttpWebSocketResponse httpWebSocketResponse,
                                                      WebSocketServerHandshaker handshaker,
                                                      boolean streamBreakpointsActive, String streamId,
                                                      String reqMethod, String reqPath,
                                                      String inboundStreamId,
                                                      boolean useWsDispatch, String breakpointClientId,
                                                      String streamBreakpointId,
                                                      String inboundBreakpointClientId, String inboundBreakpointIdParam) {
        GraphQLSubscriptionHandler.FrameSender frameSender = (senderCtx, text, delay, onSent) -> {
            if (!senderCtx.channel().isActive()) {
                return;
            }
            Runnable writeAction;
            if (streamBreakpointsActive) {
                writeAction = () -> {
                    byte[] frameBytes = text.getBytes(StandardCharsets.UTF_8);

                    // WS-callback dispatch (clientId is always present — required since 7b)
                    final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;
                    int seq = StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(streamId);
                    java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                        org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                            breakpointClientId, streamBreakpointId, streamId, seq, PausedStreamFrame.Direction.OUTBOUND,
                            org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                            frameBytes, reqMethod, reqPath, webSocketClientRegistry, configuration, mockServerLogger);
                    if (wsFuture != null) {
                        decisionFuture = wsFuture;
                    } else {
                        senderCtx.writeAndFlush(new TextWebSocketFrame(text)).addListener(f -> onSent.run());
                        return;
                    }

                    final byte[] capturedBytes = frameBytes;
                    decisionFuture.thenAccept(decision ->
                        senderCtx.channel().eventLoop().execute(() -> {
                            if (!senderCtx.channel().isActive()) {
                                return;
                            }
                            switch (decision.getAction()) {
                                case CONTINUE -> senderCtx.writeAndFlush(
                                    new TextWebSocketFrame(Unpooled.wrappedBuffer(capturedBytes)))
                                    .addListener(f -> onSent.run());
                                case MODIFY -> senderCtx.writeAndFlush(
                                    new TextWebSocketFrame(Unpooled.wrappedBuffer(decision.getReplacementBody())))
                                    .addListener(f -> onSent.run());
                                // discard the frame but still advance the sequence, otherwise a
                                // dropped frame would strand the subscription with no `complete`
                                case DROP -> onSent.run();
                                case INJECT -> {
                                    senderCtx.writeAndFlush(
                                        new TextWebSocketFrame(Unpooled.wrappedBuffer(capturedBytes)))
                                        .addListener(f -> {
                                            if (senderCtx.channel().isActive()) {
                                                senderCtx.writeAndFlush(
                                                    new TextWebSocketFrame(Unpooled.wrappedBuffer(decision.getInjectedBody())))
                                                    .addListener(f2 -> onSent.run());
                                            } else {
                                                onSent.run();
                                            }
                                        });
                                }
                                // CLOSE deliberately does not advance -- the stream is over
                                case CLOSE -> {
                                    StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                                    if (handshaker != null) {
                                        handshaker.close(senderCtx.channel(), normalClosure());
                                    }
                                }
                            }
                        })
                    ).exceptionally(ex -> {
                        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.DEBUG)
                                    .setMessageFormat("stream frame decision callback failed for GraphQL subscription stream{}:{}")
                                    .setArguments(streamId, ex.getMessage())
                            );
                        }
                        // advance anyway so the subscription still terminates with `complete`
                        senderCtx.channel().eventLoop().execute(onSent);
                        return null;
                    });
                };
            } else {
                writeAction = () -> senderCtx.writeAndFlush(new TextWebSocketFrame(text))
                    .addListener(f -> onSent.run());
            }
            if (delay != null) {
                scheduler.schedule(writeAction, false, delay);
            } else {
                writeAction.run();
            }
        };

        ctx.pipeline().addLast("graphqlSubscriptionHandler",
            new GraphQLSubscriptionHandler(
                httpWebSocketResponse.getGraphqlSubscriptionFilter(),
                httpWebSocketResponse.getMessages(),
                frameSender,
                handshaker,
                configuration,
                inboundStreamId,
                webSocketClientRegistry,
                inboundBreakpointClientId,
                inboundBreakpointIdParam,
                negotiatedSubprotocol(handshaker)
            ));
    }

    private void installBidirectionalHandler(ChannelHandlerContext ctx, HttpWebSocketResponse httpWebSocketResponse,
                                             org.mockserver.model.HttpRequest request,
                                             WebSocketServerHandshaker handshaker,
                                             boolean streamBreakpointsActive, String streamId,
                                             String reqMethod, String reqPath,
                                             String inboundStreamId,
                                             boolean useWsDispatch, String breakpointClientId,
                                             String streamBreakpointId,
                                             String inboundBreakpointClientId, String inboundBreakpointIdParam) {
        List<WebSocketMessageMatcher> matchers = httpWebSocketResponse.getMatchers();
        if (matchers != null && !matchers.isEmpty()) {
            BidirectionalWebSocketFrameHandler.FrameSender frameSender = (senderCtx, message, frameContext, onSent) -> {
                if (!senderCtx.channel().isActive()) {
                    invokeOnSent(onSent);
                    return;
                }
                byte[] frameBytes;
                boolean isBinary;
                if (message.getBinary() != null) {
                    frameBytes = message.getBinary();
                    isBinary = true;
                } else if (message.getText() != null) {
                    // Opt-in templating: render the text payload with the inbound frame bound under
                    // {@code frame} (so a response can echo the frame's contents) when a templateType is
                    // set. Rendering happens before breakpoint dispatch so interception observes the
                    // rendered bytes. A render failure MUST fail loud: never ship the unrendered template
                    // source (e.g. a JAVASCRIPT source string, or a template that references a missing
                    // engine) as the reply — the client would silently mis-handle a garbage frame with no
                    // signal. Match the sibling scheduled-messages path (which closes on failure): log at
                    // ERROR and close with 1011 (internal error) so the client observes an abnormal close
                    // rather than a plausible-looking bad ack. onSent still fires so a pending
                    // onExhausted: CLOSE countdown cannot be stranded.
                    String text = message.getText();
                    if (httpWebSocketResponse.getTemplateType() != null) {
                        try {
                            text = templateRenderer.render(httpWebSocketResponse.getTemplateType(), message.getText(), request, frameContext);
                        } catch (Exception e) {
                            if (mockServerLogger.isEnabledForInstance(Level.ERROR)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.ERROR)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat("exception rendering WebSocket matcher response template with engine {} for request:{} - closing connection, frame NOT sent")
                                        .setArguments(httpWebSocketResponse.getTemplateType(), request)
                                        .setThrowable(e)
                                );
                            }
                            if (handshaker != null && senderCtx.channel().isActive()) {
                                handshaker.close(senderCtx.channel(), new CloseWebSocketFrame(WebSocketCloseStatus.INTERNAL_SERVER_ERROR));
                            }
                            invokeOnSent(onSent);
                            return;
                        }
                    }
                    frameBytes = text.getBytes(StandardCharsets.UTF_8);
                    isBinary = false;
                } else {
                    invokeOnSent(onSent);
                    return;
                }

                Runnable writeAction;
                if (streamBreakpointsActive) {
                    writeAction = () -> {
                        // WS-callback dispatch (clientId is always present — required since 7b)
                        final java.util.concurrent.CompletableFuture<StreamFrameDecision> decisionFuture;
                        int seq = StreamFrameBreakpointRegistry.getInstance().nextSequenceNumber(streamId);
                        java.util.concurrent.CompletableFuture<StreamFrameDecision> wsFuture =
                            org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().dispatchFrame(
                                breakpointClientId, streamBreakpointId, streamId, seq, PausedStreamFrame.Direction.OUTBOUND,
                                org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE_STREAM,
                                frameBytes, reqMethod, reqPath, webSocketClientRegistry, configuration, mockServerLogger);
                        if (wsFuture != null) {
                            decisionFuture = wsFuture;
                        } else {
                            WebSocketFrame frame = isBinary
                                ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(frameBytes))
                                : new TextWebSocketFrame(Unpooled.wrappedBuffer(frameBytes));
                            senderCtx.writeAndFlush(frame).addListener(f -> invokeOnSent(onSent));
                            return;
                        }

                        final boolean finalIsBinary = isBinary;
                        final byte[] capturedBytes = frameBytes;
                        decisionFuture.thenAccept(decision ->
                            senderCtx.channel().eventLoop().execute(() -> {
                                if (!senderCtx.channel().isActive()) {
                                    invokeOnSent(onSent);
                                    return;
                                }
                                switch (decision.getAction()) {
                                    case CONTINUE -> {
                                        WebSocketFrame f = finalIsBinary
                                            ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(capturedBytes))
                                            : new TextWebSocketFrame(Unpooled.wrappedBuffer(capturedBytes));
                                        senderCtx.writeAndFlush(f).addListener(l -> invokeOnSent(onSent));
                                    }
                                    case MODIFY -> {
                                        WebSocketFrame f = finalIsBinary
                                            ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(decision.getReplacementBody()))
                                            : new TextWebSocketFrame(Unpooled.wrappedBuffer(decision.getReplacementBody()));
                                        senderCtx.writeAndFlush(f).addListener(l -> invokeOnSent(onSent));
                                    }
                                    case DROP -> invokeOnSent(onSent); // discard -- do not write, but still complete
                                    case INJECT -> {
                                        WebSocketFrame orig = finalIsBinary
                                            ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(capturedBytes))
                                            : new TextWebSocketFrame(Unpooled.wrappedBuffer(capturedBytes));
                                        senderCtx.writeAndFlush(orig).addListener(f -> {
                                            if (senderCtx.channel().isActive()) {
                                                WebSocketFrame inj = finalIsBinary
                                                    ? new BinaryWebSocketFrame(Unpooled.wrappedBuffer(decision.getInjectedBody()))
                                                    : new TextWebSocketFrame(Unpooled.wrappedBuffer(decision.getInjectedBody()));
                                                senderCtx.writeAndFlush(inj).addListener(f2 -> invokeOnSent(onSent));
                                            } else {
                                                invokeOnSent(onSent);
                                            }
                                        });
                                    }
                                    case CLOSE -> {
                                        StreamFrameBreakpointRegistry.getInstance().evictStream(streamId);
                                        if (handshaker != null) {
                                            handshaker.close(senderCtx.channel(), closeFrame(httpWebSocketResponse));
                                        }
                                        invokeOnSent(onSent);
                                    }
                                }
                            })
                        ).exceptionally(ex -> {
                            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.DEBUG)
                                        .setMessageFormat("stream frame decision callback failed for WebSocket bidi stream{}:{}")
                                        .setArguments(streamId, ex.getMessage())
                                );
                            }
                            senderCtx.channel().eventLoop().execute(() -> invokeOnSent(onSent));
                            return null;
                        });
                    };
                } else {
                    writeAction = () -> {
                        WebSocketFrame frame = isBinary
                            ? new BinaryWebSocketFrame(Unpooled.copiedBuffer(frameBytes))
                            : new TextWebSocketFrame(Unpooled.wrappedBuffer(frameBytes));
                        senderCtx.writeAndFlush(frame).addListener(f -> invokeOnSent(onSent));
                    };
                }

                Delay delay = message.getDelay();
                if (delay != null) {
                    scheduler.schedule(writeAction, false, delay);
                } else {
                    writeAction.run();
                }
            };
            BidirectionalWebSocketFrameHandler.ConnectionCloser connectionCloser =
                c -> handshaker.close(c.channel(), closeFrame(httpWebSocketResponse));
            ctx.pipeline().addLast("bidirectionalWebSocketHandler",
                new BidirectionalWebSocketFrameHandler(matchers, frameSender, configuration, inboundStreamId, webSocketClientRegistry,
                    inboundBreakpointClientId, inboundBreakpointIdParam, connectionCloser));
        }
    }

    /**
     * Invoke the frame-sent completion callback if present. The callback is null for classic
     * (non-sequential) responses and non-final turns; it is non-null only when an exhausted
     * {@link WebSocketOnExhausted#CLOSE} matcher must close after the final turn's frames have flushed,
     * in which case it must run exactly once per frame so the countdown completes.
     */
    private static void invokeOnSent(Runnable onSent) {
        if (onSent != null) {
            onSent.run();
        }
    }

    private FullHttpRequest buildNettyRequest(org.mockserver.model.HttpRequest request) {
        String uri = request.getPath().getValue();
        if (request.getQueryStringParameters() != null && !request.getQueryStringParameters().isEmpty()) {
            StringBuilder qs = new StringBuilder("?");
            boolean first = true;
            for (Parameter param : request.getQueryStringParameters().getEntries()) {
                for (NottableString value : param.getValues()) {
                    if (!first) {
                        qs.append("&");
                    }
                    try {
                        qs.append(URLEncoder.encode(param.getName().getValue(), "UTF-8"))
                            .append("=")
                            .append(URLEncoder.encode(value.getValue(), "UTF-8"));
                    } catch (UnsupportedEncodingException e) {
                        qs.append(param.getName().getValue())
                            .append("=")
                            .append(value.getValue());
                    }
                    first = false;
                }
            }
            uri = uri + qs.toString();
        }
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";

        DefaultFullHttpRequest nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1,
            HttpMethod.valueOf(method),
            uri
        );

        if (request.getHeaderList() != null) {
            for (org.mockserver.model.Header header : request.getHeaderList()) {
                for (NottableString value : header.getValues()) {
                    nettyRequest.headers().add(header.getName().getValue(), value.getValue());
                }
            }
        }

        if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
            nettyRequest.headers().set(HttpHeaderNames.HOST, "localhost");
        }

        return nettyRequest;
    }
}
