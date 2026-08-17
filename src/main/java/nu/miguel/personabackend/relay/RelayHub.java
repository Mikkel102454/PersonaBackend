package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import nu.miguel.persona.editor.protocol.Protocol;
import nu.miguel.persona.editor.protocol.RelayControlMessage;
import nu.miguel.personabackend.session.EditorProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public final class RelayHub {
    private static final int MAX_REPLAY_BYTES = 4 * 1_024 * 1_024;
    private static final String PLUGIN_DISCONNECTED = "__PERSONA_PLUGIN_DISCONNECTED__";
    private final ObjectMapper json;
    private final int replayCapacity;
    private final Map<UUID, SocketBinding> plugins = new ConcurrentHashMap<>();
    private final Map<UUID, SocketBinding> browsers = new ConcurrentHashMap<>();
    private final Map<UUID, ReplayWindow> pluginReplay = new ConcurrentHashMap<>();
    private final Map<UUID, ReplayWindow> browserReplay = new ConcurrentHashMap<>();
    private final RelayCoordination coordination;
    private final RelayCapacityProperties capacity;
    private final RelayCircuitBreaker coordinationBreaker=new RelayCircuitBreaker(5,java.time.Duration.ofSeconds(30));

    public RelayHub(ObjectMapper json, EditorProperties properties) {
        this(json, properties, new InMemoryRelayCoordination(), RelayCapacityProperties.defaults());
    }

    @Autowired
    public RelayHub(ObjectMapper json, EditorProperties properties, RelayCoordination coordination,
                    RelayCapacityProperties capacity) {
        this.json = json;
        this.replayCapacity = properties.replayCapacity();
        this.coordination = coordination; this.capacity = capacity;
        coordination.listen(this::deliverRemote);
    }

    public void register(RelaySocketHandler.Role role, UUID id, WebSocketSession socket, long afterSequence)
            throws IOException {
        Map<UUID, SocketBinding> own = sockets(role);
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(socket,
                capacity.sendTimeLimitMillis(), capacity.sendBufferBytes(),
                ConcurrentWebSocketSessionDecorator.OverflowStrategy.TERMINATE);
        SocketBinding binding = new SocketBinding(socket, outbound);
        SocketBinding replaced = own.put(id, binding);
        if (replaced != null && replaced.original() != socket && replaced.outbound().isOpen())
            replaced.outbound().close(CloseStatus.NORMAL.withReason("Replaced by reconnect"));
        coordination.connected(role, id, capacity.presenceTtl());
        replay(id, outbound, peerReplay(role), afterSequence);
    }

    public void publish(RelaySocketHandler.Role role, UUID id, long sequence, TextMessage message) throws IOException {
        coordination.connected(role, id, capacity.presenceTtl());
        replay(role).computeIfAbsent(id, ignored -> new ReplayWindow(replayCapacity)).add(sequence, message);
        SocketBinding peer = peerSockets(role).get(id);
        if (peer != null && peer.outbound().isOpen()) send(peer.outbound(), message);
        else coordinationBreaker.execute(()->coordination.forward(role,id,message.getPayload()));
    }

    public void unregister(RelaySocketHandler.Role role, UUID id, WebSocketSession socket) {
        SocketBinding current = sockets(role).get(id);
        if (current != null && current.original() == socket && sockets(role).remove(id, current)) {
            coordination.disconnected(role, id);
            if (role == RelaySocketHandler.Role.PLUGIN) notifyPluginDisconnected(id);
        }
    }

    public void closeRole(RelaySocketHandler.Role role, UUID id, CloseStatus status) {
        SocketBinding socket = sockets(role).remove(id);
        close(socket == null ? null : socket.outbound(), status);
        coordination.disconnected(role, id);
        if (role == RelaySocketHandler.Role.PLUGIN && socket != null) notifyPluginDisconnected(id);
    }

    public void closeSession(UUID id, CloseStatus status) {
        SocketBinding plugin = plugins.remove(id), browser = browsers.remove(id);
        close(plugin == null ? null : plugin.outbound(), status);
        close(browser == null ? null : browser.outbound(), status);
        coordination.disconnected(RelaySocketHandler.Role.PLUGIN, id);
        coordination.disconnected(RelaySocketHandler.Role.BROWSER, id);
        pluginReplay.remove(id);
        browserReplay.remove(id);
    }

    public boolean connected(RelaySocketHandler.Role role, UUID id) {
        SocketBinding local = sockets(role).get(id);
        return local != null && local.outbound().isOpen() || coordination.isConnected(role, id);
    }

    @PreDestroy
    public void closeAll() {
        Set<UUID> ids = new HashSet<>(plugins.keySet());
        ids.addAll(browsers.keySet());
        ids.forEach(id -> closeSession(id, CloseStatus.GOING_AWAY.withReason("Relay shutting down")));
    }

    private void replay(UUID id, WebSocketSession socket, Map<UUID, ReplayWindow> windows, long after) throws IOException {
        ReplayWindow window = windows.get(id);
        ReplayResult result = window == null ? new ReplayResult(after > 0, 0, List.of()) : window.after(after);
        if (result.missed()) {
            control(socket, id, Protocol.RESYNC_REQUIRED, result.latest());
            return;
        }
        for (TextMessage message : result.messages()) send(socket, message);
        control(socket, id, Protocol.REPLAY_COMPLETE, result.latest());
    }
    private void deliverRemote(RelayCoordination.Forwarded message) {
        try {
            RelaySocketHandler.Role source = RelaySocketHandler.Role.valueOf(message.sourceRole());
            SocketBinding peer = peerSockets(source).get(message.sessionId());
            if (source == RelaySocketHandler.Role.PLUGIN && PLUGIN_DISCONNECTED.equals(message.payload())) {
                if (peer != null) {
                    peerSockets(source).remove(message.sessionId(), peer);
                    close(peer.outbound(), CloseStatus.SERVICE_RESTARTED.withReason("Persona server disconnected"));
                }
                return;
            }
            if (peer != null && peer.outbound().isOpen()) send(peer.outbound(), new TextMessage(message.payload()));
        } catch (IllegalArgumentException | IOException ignored) { /* Invalid/closed cross-instance delivery is dropped. */ }
    }

    private void notifyPluginDisconnected(UUID id) {
        SocketBinding browser = browsers.remove(id);
        close(browser == null ? null : browser.outbound(),
                CloseStatus.SERVICE_RESTARTED.withReason("Persona server disconnected"));
        coordination.disconnected(RelaySocketHandler.Role.BROWSER, id);
        coordinationBreaker.execute(() -> coordination.forward(RelaySocketHandler.Role.PLUGIN, id, PLUGIN_DISCONNECTED));
    }

    private void control(WebSocketSession socket, UUID id, String type, long latest) throws IOException {
        send(socket, new TextMessage(json.writeValueAsString(
                new RelayControlMessage(Protocol.VERSION, id, type, latest))));
    }

    private static void send(WebSocketSession socket, WebSocketMessage<?> message) throws IOException {
        synchronized (socket) { if (socket.isOpen()) socket.sendMessage(message); }
    }
    private static void close(WebSocketSession socket, CloseStatus status) {
        if (socket == null || !socket.isOpen()) return;
        try { socket.close(status); } catch (IOException ignored) {}
    }
    private Map<UUID, SocketBinding> sockets(RelaySocketHandler.Role role) { return role == RelaySocketHandler.Role.PLUGIN ? plugins : browsers; }
    private Map<UUID, SocketBinding> peerSockets(RelaySocketHandler.Role role) { return role == RelaySocketHandler.Role.PLUGIN ? browsers : plugins; }
    private Map<UUID, ReplayWindow> replay(RelaySocketHandler.Role role) { return role == RelaySocketHandler.Role.PLUGIN ? pluginReplay : browserReplay; }
    private Map<UUID, ReplayWindow> peerReplay(RelaySocketHandler.Role role) { return role == RelaySocketHandler.Role.PLUGIN ? browserReplay : pluginReplay; }

    private static final class ReplayWindow {
        private final int capacity;
        private final ArrayDeque<StoredMessage> messages = new ArrayDeque<>();
        private long latest;
        private int bytes;
        private ReplayWindow(int capacity) { this.capacity = capacity; }
        synchronized void add(long sequence, TextMessage message) {
            latest = sequence;
            messages.addLast(new StoredMessage(sequence, message));
            bytes += message.getPayloadLength();
            while (messages.size() > capacity || bytes > MAX_REPLAY_BYTES)
                bytes -= messages.removeFirst().message().getPayloadLength();
        }
        synchronized ReplayResult after(long sequence) {
            long oldest = messages.isEmpty() ? latest + 1 : messages.getFirst().sequence();
            boolean missed = sequence < oldest - 1 || sequence > latest;
            List<TextMessage> replay = missed ? List.of() : messages.stream()
                    .filter(item -> item.sequence() > sequence).map(StoredMessage::message).toList();
            return new ReplayResult(missed, latest, replay);
        }
    }
    private record StoredMessage(long sequence, TextMessage message) {}
    private record ReplayResult(boolean missed, long latest, List<TextMessage> messages) {}
    private record SocketBinding(WebSocketSession original, WebSocketSession outbound) {}
}
