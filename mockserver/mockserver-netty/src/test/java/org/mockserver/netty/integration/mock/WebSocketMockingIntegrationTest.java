package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpTemplate;
import org.mockserver.netty.MockServer;

import java.io.*;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;
import static org.mockserver.model.WebSocketOnExhausted.CLOSE;
import static org.mockserver.model.WebSocketOnExhausted.IGNORE;
import static org.mockserver.model.WebSocketOnExhausted.REPEAT;
import static org.mockserver.stop.Stop.stopQuietly;

public class WebSocketMockingIntegrationTest {

    private static int mockServerPort;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    private void createExpectation(String json) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            output.write(("PUT /mockserver/expectation HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Connection: close\r\n" +
                "Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.flush();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
        }
    }

    private List<String> connectWebSocket(String path, int expectedMessages) throws Exception {
        List<String> receivedMessages = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(expectedMessages);
        CompletableFuture<Boolean> handshakeComplete = new CompletableFuture<>();

        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            URI uri = new URI("ws://localhost:" + mockServerPort + path);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), Integer.MAX_VALUE
            );

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                            new HttpClientCodec(),
                            new HttpObjectAggregator(65536),
                            new SimpleChannelInboundHandler<Object>() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    handshaker.handshake(ctx.channel());
                                }

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                    if (!handshaker.isHandshakeComplete()) {
                                        try {
                                            handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                                            handshakeComplete.complete(true);
                                        } catch (Exception e) {
                                            handshakeComplete.completeExceptionally(e);
                                        }
                                        return;
                                    }

                                    if (msg instanceof TextWebSocketFrame) {
                                        receivedMessages.add(((TextWebSocketFrame) msg).text());
                                        latch.countDown();
                                    } else if (msg instanceof CloseWebSocketFrame) {
                                        ctx.close();
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    handshakeComplete.completeExceptionally(cause);
                                    ctx.close();
                                }
                            }
                        );
                    }
                });

            Channel channel = bootstrap.connect("localhost", mockServerPort).sync().channel();
            handshakeComplete.get(5, TimeUnit.SECONDS);
            latch.await(5, TimeUnit.SECONDS);
            channel.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }

        return receivedMessages;
    }

    /**
     * Interactive WebSocket client for the bidirectional / matcher-driven tests: it completes the
     * handshake, then lets the test drive the conversation by {@link #send(String) sending} frames and
     * asserting on what comes back.
     *
     * <p>Received TEXT frames are queued (drained by {@link #awaitMessages(int, long)} /
     * {@link #assertNoMessage(long)}); the status code of a server-initiated CLOSE is captured for
     * {@link #awaitClose(long)}. Unlike {@code WebSocketControlFrameHandler} on the server (which echoes a
     * client-initiated CLOSE), here the SERVER initiates the close, so this client simply records the
     * inbound {@link CloseWebSocketFrame}'s status rather than echoing it.
     */
    private final class WsClient implements AutoCloseable {

        private final NioEventLoopGroup group = new NioEventLoopGroup(1);
        private final Channel channel;
        private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeStatus = new CompletableFuture<>();

        private WsClient(String path) throws Exception {
            URI uri = new URI("ws://localhost:" + mockServerPort + path);
            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders(), Integer.MAX_VALUE
            );
            CompletableFuture<Boolean> handshakeComplete = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(
                            new HttpClientCodec(),
                            new HttpObjectAggregator(65536),
                            new SimpleChannelInboundHandler<Object>() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    handshaker.handshake(ctx.channel());
                                }

                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                                    if (!handshaker.isHandshakeComplete()) {
                                        try {
                                            handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
                                            handshakeComplete.complete(true);
                                        } catch (Exception e) {
                                            handshakeComplete.completeExceptionally(e);
                                        }
                                        return;
                                    }
                                    if (msg instanceof TextWebSocketFrame) {
                                        messages.add(((TextWebSocketFrame) msg).text());
                                    } else if (msg instanceof CloseWebSocketFrame) {
                                        // SERVER-initiated close: capture the status code, do not echo.
                                        closeStatus.complete(((CloseWebSocketFrame) msg).statusCode());
                                    }
                                }

                                @Override
                                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                    handshakeComplete.completeExceptionally(cause);
                                    ctx.close();
                                }
                            }
                        );
                    }
                });

            channel = bootstrap.connect("localhost", mockServerPort).sync().channel();
            handshakeComplete.get(5, TimeUnit.SECONDS);
        }

        private void send(String text) {
            channel.writeAndFlush(new TextWebSocketFrame(text));
        }

        /**
         * Wait for exactly {@code n} TEXT frames, returning them in arrival order. Fails (rather than
         * returning a short list) if fewer than {@code n} arrive within {@code timeoutMillis}.
         */
        private List<String> awaitMessages(int n, long timeoutMillis) throws InterruptedException {
            List<String> received = new ArrayList<>();
            long deadline = System.currentTimeMillis() + timeoutMillis;
            for (int i = 0; i < n; i++) {
                long remaining = deadline - System.currentTimeMillis();
                String message = messages.poll(Math.max(0, remaining), TimeUnit.MILLISECONDS);
                if (message == null) {
                    fail("timed out after " + timeoutMillis + "ms waiting for message " + (i + 1) + " of "
                        + n + "; received so far: " + received);
                }
                received.add(message);
            }
            return received;
        }

        /**
         * Assert that no TEXT frame arrives within {@code withinMillis} — the bounded negative check used to
         * prove a fire-and-forget (empty) turn produces no reply.
         */
        private void assertNoMessage(long withinMillis) throws InterruptedException {
            String message = messages.poll(withinMillis, TimeUnit.MILLISECONDS);
            if (message != null) {
                fail("expected no message within " + withinMillis + "ms but received: " + message);
            }
        }

        private int awaitClose(long timeoutMillis) throws Exception {
            return closeStatus.get(timeoutMillis, TimeUnit.MILLISECONDS);
        }

        @Override
        public void close() {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    public void shouldReturnWebSocketMessagesViaRestApi() throws Exception {
        createExpectation("{\n" +
            "  \"httpRequest\": {\n" +
            "    \"method\": \"GET\",\n" +
            "    \"path\": \"/ws\"\n" +
            "  },\n" +
            "  \"httpWebSocketResponse\": {\n" +
            "    \"messages\": [\n" +
            "      {\"text\": \"hello from mock\"},\n" +
            "      {\"text\": \"second message\"}\n" +
            "    ],\n" +
            "    \"closeConnection\": true\n" +
            "  }\n" +
            "}");

        List<String> messages = connectWebSocket("/ws", 2);

        assertThat(messages.size(), is(2));
        assertThat(messages.get(0), is("hello from mock"));
        assertThat(messages.get(1), is("second message"));
    }

    @Test
    public void shouldReturnSingleWebSocketMessage() throws Exception {
        createExpectation("{\n" +
            "  \"httpRequest\": {\n" +
            "    \"method\": \"GET\",\n" +
            "    \"path\": \"/ws-single\"\n" +
            "  },\n" +
            "  \"httpWebSocketResponse\": {\n" +
            "    \"messages\": [\n" +
            "      {\"text\": \"only message\"}\n" +
            "    ],\n" +
            "    \"closeConnection\": true\n" +
            "  }\n" +
            "}");

        List<String> messages = connectWebSocket("/ws-single", 1);

        assertThat(messages.size(), is(1));
        assertThat(messages.get(0), is("only message"));
    }

    @Test
    public void shouldHandleWebSocketWithSubprotocol() throws Exception {
        createExpectation("{\n" +
            "  \"httpRequest\": {\n" +
            "    \"method\": \"GET\",\n" +
            "    \"path\": \"/ws-proto\"\n" +
            "  },\n" +
            "  \"httpWebSocketResponse\": {\n" +
            "    \"subprotocol\": \"graphql-ws\",\n" +
            "    \"messages\": [\n" +
            "      {\"text\": \"{\\\"type\\\": \\\"connection_ack\\\"}\"}\n" +
            "    ],\n" +
            "    \"closeConnection\": true\n" +
            "  }\n" +
            "}");

        List<String> messages = connectWebSocket("/ws-proto", 1);

        assertThat(messages.size(), is(1));
        assertThat(messages.get(0), containsString("connection_ack"));
    }

    @Test
    public void shouldHandleWebSocketWithDelayBetweenMessages() throws Exception {
        createExpectation("{\n" +
            "  \"httpRequest\": {\n" +
            "    \"method\": \"GET\",\n" +
            "    \"path\": \"/ws-delay\"\n" +
            "  },\n" +
            "  \"httpWebSocketResponse\": {\n" +
            "    \"messages\": [\n" +
            "      {\"text\": \"fast\"},\n" +
            "      {\"text\": \"slow\", \"delay\": {\"timeUnit\": \"MILLISECONDS\", \"value\": 200}}\n" +
            "    ],\n" +
            "    \"closeConnection\": true\n" +
            "  }\n" +
            "}");

        long start = System.currentTimeMillis();
        List<String> messages = connectWebSocket("/ws-delay", 2);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(messages.size(), is(2));
        assertThat(messages.get(0), is("fast"));
        assertThat(messages.get(1), is("slow"));
        assertThat(elapsed, greaterThanOrEqualTo(150L));
    }

    @Test
    public void shouldReturnWebSocketMessagesViaJavaApi() throws Exception {
        mockServerClient.when(
            org.mockserver.model.HttpRequest.request()
                .withMethod("GET")
                .withPath("/ws-java")
        ).respondWithWebSocket(
            org.mockserver.model.HttpWebSocketResponse.webSocketResponse()
                .withMessage(org.mockserver.model.WebSocketMessage.webSocketMessage("from java api"))
                .withCloseConnection(true)
        );

        List<String> messages = connectWebSocket("/ws-java", 1);

        assertThat(messages.size(), is(1));
        assertThat(messages.get(0), is("from java api"));
    }

    @Test
    public void shouldReturnSseResponseViaJavaApi() throws Exception {
        mockServerClient.when(
            org.mockserver.model.HttpRequest.request()
                .withMethod("GET")
                .withPath("/sse-java")
        ).respondWithSse(
            org.mockserver.model.HttpSseResponse.sseResponse()
                .withEvent(org.mockserver.model.SseEvent.sseEvent()
                    .withEvent("message")
                    .withData("hello from java")
                    .withId("1"))
                .withCloseConnection(true)
        );

        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse-java HTTP/1.1\r\nHost: localhost:" + mockServerPort + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = socket.getInputStream().read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            String response = baos.toString(StandardCharsets.UTF_8.name());

            assertThat(response, containsString("HTTP/1.1 200"));
            assertThat(response, containsString("text/event-stream"));
            assertThat(response, containsString("hello from java"));
        }
    }

    // ----- R1: frame-aware matcher-response templating (echo the inbound frame's msgid) -----

    @Test
    public void shouldEchoInboundMsgidWithVelocityTemplate() throws Exception {
        // matcher matches a session:new frame; the VELOCITY response echoes the frame's msgid via $jsonPath
        mockServerClient.when(
            request().withMethod("GET").withPath("/ws-echo-velocity")
        ).respondWithWebSocket(
            webSocketResponse()
                .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
                .withMatcher(webSocketMessageMatcher()
                    .withTextRegex(".*\"type\":\"session:new\".*")
                    .withResponse(webSocketMessage(
                        "{\"type\":\"ack\",\"msgid\":\"$jsonPath.find(\"$.msgid\")\"}")))
        );

        try (WsClient client = new WsClient("/ws-echo-velocity")) {
            client.send("{\"type\":\"session:new\",\"msgid\":\"m-1\"}");
            List<String> reply = client.awaitMessages(1, 5000);
            assertThat(reply.get(0), is("{\"type\":\"ack\",\"msgid\":\"m-1\"}"));
        }
    }

    /**
     * JavaScript-dialect twin of {@link #shouldEchoInboundMsgidWithVelocityTemplate()}.
     *
     * <p><b>Environmental note:</b> the GraalJS {@code js} language cannot initialise on this machine's
     * host JDK (GraalVM CE — it works on plain Temurin/OpenJDK, and in CI/Docker). If this test fails with
     * {@code A language with id 'js' is not installed} that is the known environmental limitation, not a
     * product defect; the test is deliberately left enabled so CI and Docker validate it.
     */
    @Test
    public void shouldEchoInboundMsgidWithJavaScriptTemplate() throws Exception {
        mockServerClient.when(
            request().withMethod("GET").withPath("/ws-echo-javascript")
        ).respondWithWebSocket(
            webSocketResponse()
                .withTemplateType(HttpTemplate.TemplateType.JAVASCRIPT)
                .withMatcher(webSocketMessageMatcher()
                    .withTextRegex(".*\"type\":\"session:new\".*")
                    .withResponse(webSocketMessage(
                        "return '{\"type\":\"ack\",\"msgid\":\"' + jsonPath('$.msgid') + '\"}';")))
        );

        try (WsClient client = new WsClient("/ws-echo-javascript")) {
            client.send("{\"type\":\"session:new\",\"msgid\":\"m-1\"}");
            // generous timeout: first GraalJS call warms up the engine
            List<String> reply = client.awaitMessages(1, 10000);
            assertThat(reply.get(0), is("{\"type\":\"ack\",\"msgid\":\"m-1\"}"));
        }
    }

    // ----- Jambonz-style multi-turn conversation in a single expectation -----

    @Test
    public void shouldDriveJambonzConversationWithTurnsAndClose() throws Exception {
        // ONE expectation, three ordered matchers:
        //  1. session:new -> single turn of TWO frames (echoed ack + a verb array), IGNORE
        //  2. verb:hook   -> TWO turns (each an ack echoing THAT frame's msgid), CLOSE after the last
        //  3. verb:status -> single EMPTY turn (matched-but-no-reply), IGNORE
        mockServerClient.when(
            request().withMethod("GET").withPath("/ws-jambonz")
        ).respondWithWebSocket(
            webSocketResponse()
                .withTemplateType(HttpTemplate.TemplateType.VELOCITY)
                .withCloseCode(1000)
                .withMatchers(
                    webSocketMessageMatcher()
                        .withTextRegex(".*\"type\":\"session:new\".*")
                        .withTurn(Arrays.asList(
                            webSocketMessage("{\"type\":\"ack\",\"msgid\":\"$jsonPath.find(\"$.msgid\")\"}"),
                            webSocketMessage("[{\"verb\":\"say\",\"text\":\"hello\"}]")))
                        .withOnExhausted(IGNORE),
                    webSocketMessageMatcher()
                        .withTextRegex(".*\"type\":\"verb:hook\".*")
                        .withTurn(Collections.singletonList(
                            webSocketMessage("{\"type\":\"ack\",\"msgid\":\"$jsonPath.find(\"$.msgid\")\"}")))
                        .withTurn(Collections.singletonList(
                            webSocketMessage("{\"type\":\"ack\",\"msgid\":\"$jsonPath.find(\"$.msgid\")\"}")))
                        .withOnExhausted(CLOSE),
                    webSocketMessageMatcher()
                        .withTextRegex(".*\"type\":\"verb:status\".*")
                        .withTurn(Collections.emptyList())
                        .withOnExhausted(IGNORE)
                )
        );

        try (WsClient client = new WsClient("/ws-jambonz")) {
            // session:new -> turn of two frames, ack echoes s-1
            client.send("{\"type\":\"session:new\",\"msgid\":\"s-1\"}");
            List<String> sessionReply = client.awaitMessages(2, 5000);
            assertThat(sessionReply.get(0), is("{\"type\":\"ack\",\"msgid\":\"s-1\"}"));
            assertThat(sessionReply.get(1), is("[{\"verb\":\"say\",\"text\":\"hello\"}]"));

            // verb:status -> empty turn: matched but no reply (bounded negative check)
            client.send("{\"type\":\"verb:status\",\"msgid\":\"st-1\"}");
            client.assertNoMessage(700);

            // verb:hook #1 -> turn-1 ack echoing h-1
            client.send("{\"type\":\"verb:hook\",\"msgid\":\"h-1\"}");
            assertThat(client.awaitMessages(1, 5000).get(0), is("{\"type\":\"ack\",\"msgid\":\"h-1\"}"));

            // verb:hook #2 -> turn-2 ack echoing h-2, then CLOSE with code 1000
            client.send("{\"type\":\"verb:hook\",\"msgid\":\"h-2\"}");
            assertThat(client.awaitMessages(1, 5000).get(0), is("{\"type\":\"ack\",\"msgid\":\"h-2\"}"));

            assertThat(client.awaitClose(5000), is(1000));
            // no further frames after the close
            client.assertNoMessage(300);
        }
    }

    // ----- R4: configurable close code -----

    @Test
    public void shouldCloseWithConfiguredCloseCode() throws Exception {
        mockServerClient.when(
            request().withMethod("GET").withPath("/ws-close-4001")
        ).respondWithWebSocket(
            webSocketResponse()
                .withMessage(webSocketMessage("bye"))
                .withCloseConnection(true)
                .withCloseCode(4001)
        );

        try (WsClient client = new WsClient("/ws-close-4001")) {
            assertThat(client.awaitMessages(1, 5000).get(0), is("bye"));
            assertThat(client.awaitClose(5000), is(4001));
        }
    }

    // ----- R3: onExhausted REPEAT wraps back to turn 0 -----

    @Test
    public void shouldRepeatTurnsWhenExhaustedWithRepeat() throws Exception {
        mockServerClient.when(
            request().withMethod("GET").withPath("/ws-repeat")
        ).respondWithWebSocket(
            webSocketResponse()
                .withMatcher(webSocketMessageMatcher()
                    .withTextRegex(".*ping.*")
                    .withTurn(Collections.singletonList(webSocketMessage("pong")))
                    .withOnExhausted(REPEAT))
        );

        try (WsClient client = new WsClient("/ws-repeat")) {
            client.send("ping");
            assertThat(client.awaitMessages(1, 5000).get(0), is("pong"));
            // second match is past the single turn: REPEAT wraps to turn 0 and answers again
            client.send("ping");
            assertThat(client.awaitMessages(1, 5000).get(0), is("pong"));
        }
    }

    // ----- R3: per-connection turn cursors are isolated -----

    @Test
    public void shouldIsolateTurnCursorsPerConnection() throws Exception {
        mockServerClient.when(
            request().withMethod("GET").withPath("/ws-isolation")
        ).respondWithWebSocket(
            webSocketResponse()
                .withMatcher(webSocketMessageMatcher()
                    .withTextRegex(".*next.*")
                    .withTurn(Collections.singletonList(webSocketMessage("turn-0")))
                    .withTurn(Collections.singletonList(webSocketMessage("turn-1")))
                    .withOnExhausted(IGNORE))
        );

        try (WsClient a = new WsClient("/ws-isolation");
             WsClient b = new WsClient("/ws-isolation")) {
            // both connections start at turn 0 independently
            a.send("next");
            assertThat(a.awaitMessages(1, 5000).get(0), is("turn-0"));
            b.send("next");
            assertThat(b.awaitMessages(1, 5000).get(0), is("turn-0"));

            // and each advances its own cursor to turn 1
            a.send("next");
            assertThat(a.awaitMessages(1, 5000).get(0), is("turn-1"));
            b.send("next");
            assertThat(b.awaitMessages(1, 5000).get(0), is("turn-1"));
        }
    }
}
