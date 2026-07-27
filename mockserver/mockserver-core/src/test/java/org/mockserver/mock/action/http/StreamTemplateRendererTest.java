package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpTemplate;
import org.mockserver.templates.engine.model.WebSocketFrameContext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * T1.2: shared streaming-payload template rendering.
 * <p>
 * Verifies the {@link StreamTemplateRenderer} seam used by the SSE, WebSocket and gRPC server-stream
 * handlers: a payload with no {@code templateType} is returned verbatim (static, byte-for-byte
 * unchanged), while a payload with a {@code VELOCITY} / {@code MUSTACHE} / {@code JAVASCRIPT} template
 * type is rendered against the triggering request (request fields available via the same helpers the
 * response-template path uses).
 */
public class StreamTemplateRendererTest {

    private final StreamTemplateRenderer renderer =
        new StreamTemplateRenderer(new MockServerLogger(StreamTemplateRendererTest.class), configuration());

    @Test
    public void shouldReturnPayloadUnchangedWhenNoTemplateType() {
        String payload = "{\"greeting\": \"Hello $jsonPath.find(\\\"$.name\\\")\"}";

        assertThat(renderer.render(null, payload, request().withBody("{\"name\":\"Alice\"}")), is(payload));
    }

    @Test
    public void shouldReturnNullPayloadUnchanged() {
        assertThat(renderer.render(HttpTemplate.TemplateType.VELOCITY, null, request()), is((String) null));
    }

    @Test
    public void shouldRenderVelocityTemplateAgainstRequest() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.VELOCITY,
            "{\"greeting\": \"Hello $jsonPath.find(\"$.name\")\"}",
            request().withBody("{\"name\": \"Alice\"}"));

        assertThat(rendered, is("{\"greeting\": \"Hello Alice\"}"));
    }

    @Test
    public void shouldRenderMustacheTemplateAgainstRequest() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.MUSTACHE,
            "{\"greeting\": \"Hello {{#jsonPath}}$.name{{/jsonPath}}{{jsonPathResult}}\"}",
            request().withBody("{\"name\": \"Carol\"}"));

        assertThat(rendered, is("{\"greeting\": \"Hello Carol\"}"));
    }

    @Test
    public void shouldRenderJavaScriptTemplateAsTextAgainstRequest() {
        // JavaScript streaming templates return a text fragment: a returned string is emitted verbatim.
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return 'Hello ' + request.body;",
            request().withBody("Bob"));

        assertThat(rendered, is("Hello Bob"));
    }

    @Test
    public void shouldJsonStringifyNonStringJavaScriptReturnValue() {
        // A JavaScript template returning a non-string value is JSON.stringify'd.
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return { greeting: 'Hi ' + request.body };",
            request().withBody("Dave"));

        assertThat(rendered, is("{\"greeting\":\"Hi Dave\"}"));
    }

    // ----- frame-aware WebSocket matcher-response templating (4-arg render) -----

    // The inbound frame the matcher fired on: a TEXT frame carrying JSON, turnIndex 0.
    private static final WebSocketFrameContext SESSION_NEW_FRAME =
        new WebSocketFrameContext("{\"type\":\"session:new\",\"msgid\":\"m-1\"}", null, 0);

    // ----- null frame via 4-arg is byte-identical to the 3-arg path -----

    @Test
    public void shouldRenderVelocityIdenticallyWhenFrameIsNull() {
        String template = "{\"greeting\": \"Hello $jsonPath.find(\"$.name\")\"}";
        assertThat(
            renderer.render(HttpTemplate.TemplateType.VELOCITY, template, request().withBody("{\"name\": \"Alice\"}"), null),
            is(renderer.render(HttpTemplate.TemplateType.VELOCITY, template, request().withBody("{\"name\": \"Alice\"}"))));
    }

    @Test
    public void shouldRenderMustacheIdenticallyWhenFrameIsNull() {
        String template = "{\"greeting\": \"Hello {{#jsonPath}}$.name{{/jsonPath}}{{jsonPathResult}}\"}";
        assertThat(
            renderer.render(HttpTemplate.TemplateType.MUSTACHE, template, request().withBody("{\"name\": \"Carol\"}"), null),
            is(renderer.render(HttpTemplate.TemplateType.MUSTACHE, template, request().withBody("{\"name\": \"Carol\"}"))));
    }

    @Test
    public void shouldRenderJavaScriptIdenticallyWhenFrameIsNull() {
        String template = "return 'Hello ' + request.body;";
        assertThat(
            renderer.render(HttpTemplate.TemplateType.JAVASCRIPT, template, request().withBody("Bob"), null),
            is(renderer.render(HttpTemplate.TemplateType.JAVASCRIPT, template, request().withBody("Bob"))));
    }

    @Test
    public void shouldReturnNullPayloadUnchangedWithFrame() {
        assertThat(renderer.render(HttpTemplate.TemplateType.VELOCITY, null, request(), SESSION_NEW_FRAME), is((String) null));
    }

    // ----- frame.text interpolation into the output -----

    @Test
    public void shouldInterpolateFrameTextIntoVelocity() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.VELOCITY,
            "id=$frame.text",
            request(),
            new WebSocketFrameContext("hello-world", null, null));

        assertThat(rendered, is("id=hello-world"));
    }

    @Test
    public void shouldInterpolateFrameTextIntoMustache() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.MUSTACHE,
            "id={{frame.text}}",
            request(),
            new WebSocketFrameContext("hello-world", null, null));

        assertThat(rendered, is("id=hello-world"));
    }

    @Test
    public void shouldInterpolateFrameTextIntoJavaScript() {
        // frame.text is a plain string, and because it is itself JSON, JSON.parse(frame.text) works.
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return 'type=' + JSON.parse(frame.text).type;",
            request(),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("type=session:new"));
    }

    // ----- $jsonPath extracts from the frame body (not the upgrade request body) -----

    @Test
    public void shouldEchoMsgidFromFrameJsonBodyVelocity() {
        // acceptance shape: frame {"type":"session:new","msgid":"m-1"} -> {"type":"ack","msgid":"m-1"}
        String rendered = renderer.render(
            HttpTemplate.TemplateType.VELOCITY,
            "{\"type\":\"ack\",\"msgid\":\"$jsonPath.find(\"$.msgid\")\"}",
            request().withBody("{\"msgid\":\"UPGRADE-BODY\"}"),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("{\"type\":\"ack\",\"msgid\":\"m-1\"}"));
    }

    @Test
    public void shouldEchoMsgidFromFrameJsonBodyMustache() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.MUSTACHE,
            "{\"type\":\"ack\",\"msgid\":\"{{#jsonPath}}$.msgid{{/jsonPath}}{{jsonPathResult}}\"}",
            request().withBody("{\"msgid\":\"UPGRADE-BODY\"}"),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("{\"type\":\"ack\",\"msgid\":\"m-1\"}"));
    }

    @Test
    public void shouldEchoMsgidFromFrameJsonBodyJavaScript() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return '{\"type\":\"ack\",\"msgid\":\"' + jsonPath('$.msgid') + '\"}';",
            request().withBody("{\"msgid\":\"UPGRADE-BODY\"}"),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("{\"type\":\"ack\",\"msgid\":\"m-1\"}"));
    }

    // ----- request context still resolves the upgrade request alongside frame data in one template -----

    @Test
    public void shouldResolveUpgradeRequestAndFrameTogetherVelocity() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.VELOCITY,
            "{\"path\":\"$request.path\",\"msgid\":\"$jsonPath.find(\"$.msgid\")\"}",
            request().withPath("/voice").withBody("{\"msgid\":\"UPGRADE-BODY\"}"),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("{\"path\":\"/voice\",\"msgid\":\"m-1\"}"));
    }

    @Test
    public void shouldResolveUpgradeRequestAndFrameTogetherMustache() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.MUSTACHE,
            "{\"path\":\"{{request.path}}\",\"msgid\":\"{{#jsonPath}}$.msgid{{/jsonPath}}{{jsonPathResult}}\"}",
            request().withPath("/voice").withBody("{\"msgid\":\"UPGRADE-BODY\"}"),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("{\"path\":\"/voice\",\"msgid\":\"m-1\"}"));
    }

    @Test
    public void shouldResolveUpgradeRequestAndFrameTogetherJavaScript() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return '{\"path\":\"' + request.path + '\",\"msgid\":\"' + jsonPath('$.msgid') + '\"}';",
            request().withPath("/voice").withBody("{\"msgid\":\"UPGRADE-BODY\"}"),
            SESSION_NEW_FRAME);

        assertThat(rendered, is("{\"path\":\"/voice\",\"msgid\":\"m-1\"}"));
    }

    // ----- turnIndex is accessible as frame.turnIndex -----

    @Test
    public void shouldExposeTurnIndexVelocity() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.VELOCITY,
            "turn=$frame.turnIndex",
            request(),
            new WebSocketFrameContext("hello", null, 3));

        assertThat(rendered, is("turn=3"));
    }

    @Test
    public void shouldExposeTurnIndexMustache() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.MUSTACHE,
            "turn={{frame.turnIndex}}",
            request(),
            new WebSocketFrameContext("hello", null, 3));

        assertThat(rendered, is("turn=3"));
    }

    @Test
    public void shouldExposeTurnIndexJavaScript() {
        String rendered = renderer.render(
            HttpTemplate.TemplateType.JAVASCRIPT,
            "return 'turn=' + frame.turnIndex;",
            request(),
            new WebSocketFrameContext("hello", null, 3));

        assertThat(rendered, is("turn=3"));
    }
}
