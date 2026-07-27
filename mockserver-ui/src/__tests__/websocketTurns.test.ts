/**
 * Coverage for the WebSocket turns / onExhausted / closeCode fields added to
 * `httpWebSocketResponse` (Step 5 of the WS-gaps feature).
 *
 * Pins three things:
 *  1. buildExpectationJson emits the new fields with the right JSON shape
 *     (turns = array-of-arrays of message objects, empty inner array preserved;
 *     onExhausted omitted at IGNORE; closeCode only when a valid integer).
 *  2. mergeUnmodeledFields round-trips an edit-resave WITHOUT dropping turns /
 *     onExhausted / closeCode OR an unknown future WS sub-field — the historical
 *     bug where the whole action family was replaced wholesale.
 *  3. standardToJava emits the corresponding fluent builder calls.
 */
import { describe, it, expect } from 'vitest';
import {
  buildExpectationJson,
  mergeUnmodeledFields,
  unmodeledFieldNames,
  standardToJava,
  type StandardMatcher,
  type StandardActionPayload,
  type StandardWebSocketState,
  type WebSocketMatcherDraft,
} from '../lib/standardCodegen';

function baseMatcher(overrides?: Partial<StandardMatcher>): StandardMatcher {
  return {
    id: '', method: 'GET', path: '/ws', headers: '', queryString: '', cookies: '',
    pathParams: '', body: '', bodyBinary: false, bodyMatcherType: 'string',
    priority: 0, times: 0, ...overrides,
  };
}

function wsMatcher(overrides?: Partial<WebSocketMatcherDraft>): WebSocketMatcherDraft {
  return {
    frameType: 'TEXT',
    textMatcher: '',
    mode: 'responses',
    responses: '',
    turns: [''],
    onExhausted: 'IGNORE',
    ...overrides,
  };
}

function wsAction(overrides?: Partial<StandardWebSocketState>): StandardActionPayload {
  return {
    type: 'websocket',
    websocket: {
      subprotocol: '',
      messages: '',
      closeConnection: false,
      closeCode: '',
      matchers: [],
      ...overrides,
    },
  };
}

// ---------------------------------------------------------------------------
// 1. Builder emission
// ---------------------------------------------------------------------------

describe('buildExpectationJson — WebSocket turns / onExhausted / closeCode', () => {
  it('emits turns as an array-of-arrays of {text} and preserves an empty turn', () => {
    const action = wsAction({
      matchers: [
        wsMatcher({
          textMatcher: '{"type":"verb:hook"}',
          mode: 'turns',
          turns: ['{"a":1}\n{"a":2}', '', '{"b":3}'],
          onExhausted: 'CLOSE',
        }),
      ],
    });
    const ws = buildExpectationJson(baseMatcher(), action)['httpWebSocketResponse'] as Record<string, unknown>;
    const m0 = (ws['matchers'] as Record<string, unknown>[])[0]!;

    expect(m0['textMatcher']).toBe('{"type":"verb:hook"}');
    expect(m0['turns']).toEqual([
      [{ text: '{"a":1}' }, { text: '{"a":2}' }],
      [], // empty turn = reply with nothing
      [{ text: '{"b":3}' }],
    ]);
    expect(m0['onExhausted']).toBe('CLOSE');
    // A turns matcher must never also carry responses.
    expect('responses' in m0).toBe(false);
  });

  it('omits onExhausted when it is the IGNORE default', () => {
    const action = wsAction({
      matchers: [wsMatcher({ mode: 'turns', turns: ['{"x":1}'], onExhausted: 'IGNORE' })],
    });
    const ws = buildExpectationJson(baseMatcher(), action)['httpWebSocketResponse'] as Record<string, unknown>;
    const m0 = (ws['matchers'] as Record<string, unknown>[])[0]!;
    expect('onExhausted' in m0).toBe(false);
    expect(m0['turns']).toEqual([[{ text: '{"x":1}' }]]);
  });

  it('emits responses (not turns) in responses mode', () => {
    const action = wsAction({
      matchers: [wsMatcher({ mode: 'responses', responses: '{"type":"pong"}', turns: ['ignored'] })],
    });
    const ws = buildExpectationJson(baseMatcher(), action)['httpWebSocketResponse'] as Record<string, unknown>;
    const m0 = (ws['matchers'] as Record<string, unknown>[])[0]!;
    expect(m0['responses']).toEqual([{ text: '{"type":"pong"}' }]);
    expect('turns' in m0).toBe(false);
    expect('onExhausted' in m0).toBe(false);
  });

  it('emits closeCode only for a valid integer', () => {
    const withCode = buildExpectationJson(baseMatcher(), wsAction({ closeCode: '4001' }))['httpWebSocketResponse'] as Record<string, unknown>;
    expect(withCode['closeCode']).toBe(4001);

    const withoutCode = buildExpectationJson(baseMatcher(), wsAction({ closeCode: '' }))['httpWebSocketResponse'] as Record<string, unknown>;
    expect('closeCode' in withoutCode).toBe(false);

    const blank = buildExpectationJson(baseMatcher(), wsAction({ closeCode: '   ' }))['httpWebSocketResponse'] as Record<string, unknown>;
    expect('closeCode' in blank).toBe(false);
  });

  it('drops an empty turns list rather than emitting turns: []', () => {
    const action = wsAction({ matchers: [wsMatcher({ mode: 'turns', turns: [] })] });
    const ws = buildExpectationJson(baseMatcher(), action)['httpWebSocketResponse'] as Record<string, unknown>;
    const m0 = (ws['matchers'] as Record<string, unknown>[])[0]!;
    expect('turns' in m0).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// 2. Edit-resave round-trip through mergeUnmodeledFields (the critical fix)
// ---------------------------------------------------------------------------

describe('mergeUnmodeledFields — WebSocket edit-resave preserves new + unknown fields', () => {
  it('preserves turns, onExhausted, closeCode AND an unknown future WS sub-field', () => {
    // An expectation authored via raw REST: turns + onExhausted + closeCode plus a
    // sub-field the form does not model yet.
    const original = {
      httpRequest: { path: '/ws' },
      httpWebSocketResponse: {
        subprotocol: 'voiceGateway',
        closeConnection: true,
        closeCode: 4001,
        templateType: 'JAVASCRIPT', // top-level, unmodeled by the WS form
        futureField: 1,             // a field the server grows in future
        matchers: [
          {
            frameType: 'TEXT',
            textMatcher: '{"type":"verb:hook"}',
            onExhausted: 'CLOSE',
            turns: [[{ text: '{"ack":1}' }], []],
          },
        ],
      },
      id: 'jambonz',
    };

    // The form loads and re-emits the WS action (turns / onExhausted / closeCode
    // are all modeled now). `templateType` and `futureField` are NOT emitted.
    const formJson = buildExpectationJson(
      baseMatcher({ id: 'jambonz', path: '/ws' }),
      wsAction({
        subprotocol: 'voiceGateway',
        closeConnection: true,
        closeCode: '4001',
        matchers: [
          wsMatcher({
            textMatcher: '{"type":"verb:hook"}',
            mode: 'turns',
            turns: ['{"ack":1}', ''],
            onExhausted: 'CLOSE',
          }),
        ],
      }),
    );

    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });
    const ws = merged['httpWebSocketResponse'] as Record<string, unknown>;

    // Modeled fields survive with the form's (unchanged) values.
    expect(ws['closeCode']).toBe(4001);
    const m0 = (ws['matchers'] as Record<string, unknown>[])[0]!;
    expect(m0['onExhausted']).toBe('CLOSE');
    expect(m0['turns']).toEqual([[{ text: '{"ack":1}' }], []]);

    // Unmodeled top-level WS sub-fields are re-grafted — NOT silently dropped.
    expect(ws['templateType']).toBe('JAVASCRIPT');
    expect(ws['futureField']).toBe(1);
  });

  it('does not mutate the form JSON when re-grafting unmodeled WS sub-fields', () => {
    const original = {
      httpRequest: { path: '/ws' },
      httpWebSocketResponse: { closeConnection: true, futureField: 7 },
      id: 'x',
    };
    const formJson = buildExpectationJson(baseMatcher({ id: 'x', path: '/ws' }), wsAction({ closeConnection: true }));
    mergeUnmodeledFields(original, formJson, { actionModeled: true });
    // formJson's WS action must be untouched (merge is pure).
    expect('futureField' in (formJson['httpWebSocketResponse'] as Record<string, unknown>)).toBe(false);
  });

  it('still clears matchers the user removed (form owns modeled sub-fields)', () => {
    const original = {
      httpRequest: { path: '/ws' },
      httpWebSocketResponse: {
        closeConnection: true,
        matchers: [{ frameType: 'TEXT', responses: [{ text: 'hi' }] }],
      },
      id: 'x',
    };
    // Form re-emits with no matchers (user deleted them).
    const formJson = buildExpectationJson(baseMatcher({ id: 'x', path: '/ws' }), wsAction({ closeConnection: true, matchers: [] }));
    const merged = mergeUnmodeledFields(original, formJson, { actionModeled: true });
    const ws = merged['httpWebSocketResponse'] as Record<string, unknown>;
    expect('matchers' in ws).toBe(false);
  });

  it('unmodeledFieldNames reports preserved WS sub-fields (honest indicator)', () => {
    const original = {
      httpRequest: { path: '/ws' },
      httpWebSocketResponse: {
        closeConnection: true,
        closeCode: 1000,       // modeled → not reported
        templateType: 'VELOCITY', // unmodeled → reported
        futureField: 1,        // unmodeled → reported
      },
      id: 'x',
    };
    const names = unmodeledFieldNames(original, { actionModeled: true });
    expect(names).toContain('httpWebSocketResponse.templateType');
    expect(names).toContain('httpWebSocketResponse.futureField');
    expect(names).not.toContain('httpWebSocketResponse.closeCode');
    expect(names).not.toContain('httpWebSocketResponse.closeConnection');
  });
});

// ---------------------------------------------------------------------------
// 3. Java codegen
// ---------------------------------------------------------------------------

describe('standardToJava — WebSocket turns / onExhausted / closeCode', () => {
  it('emits withCloseCode, withTurn and withOnExhausted', () => {
    const java = standardToJava(
      baseMatcher({ path: '/ws' }),
      wsAction({
        closeCode: '4001',
        matchers: [
          wsMatcher({
            textMatcher: '{"type":"verb:hook"}',
            mode: 'turns',
            turns: ['{"a":1}\n{"b":2}', ''],
            onExhausted: 'CLOSE',
          }),
        ],
      }),
    );
    expect(java).toContain('.withCloseCode(4001)');
    expect(java).toContain('.withTurn(Arrays.asList(webSocketMessage("{\\"a\\":1}"), webSocketMessage("{\\"b\\":2}")))');
    expect(java).toContain('.withTurn(Collections.emptyList())'); // empty turn
    expect(java).toContain('.withOnExhausted(WebSocketOnExhausted.CLOSE)');
    expect(java).toContain('import org.mockserver.model.WebSocketOnExhausted;');
    expect(java).toContain('import java.util.Arrays;');
    expect(java).toContain('import java.util.Collections;');
  });

  it('does not emit turns builders for a plain responses matcher', () => {
    const java = standardToJava(
      baseMatcher({ path: '/ws' }),
      wsAction({ matchers: [wsMatcher({ mode: 'responses', responses: '{"type":"pong"}' })] }),
    );
    expect(java).toContain('.withResponse(webSocketMessage("{\\"type\\":\\"pong\\"}"))');
    expect(java).not.toContain('.withTurn(');
    expect(java).not.toContain('WebSocketOnExhausted');
  });
});
