package com.fix.simulator.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Broadcasts JSON events to all connected admin WebSocket clients.
 *
 * <p>Any object passed to {@link #publish(Object)} is serialised to JSON via Jackson
 * and sent to every connected browser.  Disconnected clients are silently removed.
 *
 * <p>Thread-safe; {@code publish} may be called from any thread.
 */
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final Set<WsContext> clients = new CopyOnWriteArraySet<>();
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public void subscribe(WsContext ctx) {
        clients.add(ctx);
    }

    public void unsubscribe(WsContext ctx) {
        clients.remove(ctx);
    }

    /**
     * Serialise {@code event} to JSON and push to all connected browser clients.
     * Clients that fail to receive are automatically pruned.
     */
    public void publish(Object event) {
        if (clients.isEmpty()) return;
        try {
            String json = mapper.writeValueAsString(event);
            clients.removeIf(ctx -> {
                try {
                    ctx.send(json);
                    return false;
                } catch (Exception e) {
                    return true; // disconnected – remove
                }
            });
        } catch (Exception e) {
            log.warn("EventBus: failed to serialise event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }

    public int subscriberCount() {
        return clients.size();
    }
}
