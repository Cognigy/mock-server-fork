package org.mockserver.model;

/**
 * Controls what a sequential {@link WebSocketMessageMatcher} (one configured with
 * {@link WebSocketMessageMatcher#withTurns turns}) does once every turn has been emitted
 * on a connection.
 * <ul>
 *     <li>{@link #REPEAT} — wrap back to turn 0 and emit turns again from the start.</li>
 *     <li>{@link #CLOSE} — close the connection once the final turn's frames have been sent.</li>
 *     <li>{@link #IGNORE} — silently consume further matching frames without replying (the default).</li>
 * </ul>
 */
public enum WebSocketOnExhausted {
    REPEAT, CLOSE, IGNORE
}
