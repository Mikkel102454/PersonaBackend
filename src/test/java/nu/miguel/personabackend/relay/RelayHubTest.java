package nu.miguel.personabackend.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import nu.miguel.persona.editor.protocol.Protocol;
import nu.miguel.personabackend.session.EditorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RelayHubTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EditorProperties properties = new EditorProperties(
            "https://editor.example", "wss://editor.example", Duration.ofMinutes(5), Duration.ofMinutes(1), 3,
            Duration.ofSeconds(45), 2);

    @Test void replaysBoundedMessagesAndRequiresResyncPastTheWindow() throws Exception {
        RelayHub hub = new RelayHub(json, properties);
        var id = java.util.UUID.randomUUID();
        List<WebSocketMessage<?>> firstMessages = new ArrayList<>();
        WebSocketSession first = socket("first", firstMessages);
        hub.register(RelaySocketHandler.Role.BROWSER, id, first, 0);
        firstMessages.clear();

        hub.publish(RelaySocketHandler.Role.PLUGIN, id, 1, new TextMessage("one"));
        hub.publish(RelaySocketHandler.Role.PLUGIN, id, 2, new TextMessage("two"));
        hub.publish(RelaySocketHandler.Role.PLUGIN, id, 3, new TextMessage("three"));
        hub.unregister(RelaySocketHandler.Role.BROWSER, id, first);

        List<WebSocketMessage<?>> replayed = new ArrayList<>();
        hub.register(RelaySocketHandler.Role.BROWSER, id, socket("replay", replayed), 1);
        assertEquals(List.of("two", "three"), replayed.subList(0, 2).stream()
                .map(message -> ((TextMessage) message).getPayload()).toList());
        assertEquals(Protocol.REPLAY_COMPLETE,
                json.readTree(((TextMessage) replayed.get(2)).getPayload()).path("controlType").asText());

        List<WebSocketMessage<?>> missed = new ArrayList<>();
        hub.register(RelaySocketHandler.Role.BROWSER, id, socket("missed", missed), 0);
        assertEquals(1, missed.size());
        assertEquals(Protocol.RESYNC_REQUIRED,
                json.readTree(((TextMessage) missed.getFirst()).getPayload()).path("controlType").asText());
        assertEquals(3, json.readTree(((TextMessage) missed.getFirst()).getPayload()).path("latestSequence").asLong());
    }

    @Test void cleanShutdownClosesEveryConnectedRole() throws Exception {
        RelayHub hub = new RelayHub(json, properties);
        var id = java.util.UUID.randomUUID();
        WebSocketSession plugin = socket("plugin", new ArrayList<>());
        WebSocketSession browser = socket("browser", new ArrayList<>());
        hub.register(RelaySocketHandler.Role.PLUGIN, id, plugin, 0);
        hub.register(RelaySocketHandler.Role.BROWSER, id, browser, 0);

        hub.closeAll();

        verify(plugin).close(argThat(status -> status.getCode() == CloseStatus.GOING_AWAY.getCode()));
        verify(browser).close(argThat(status -> status.getCode() == CloseStatus.GOING_AWAY.getCode()));
    }

    @Test void routesSignedEnvelopeBetweenBackendInstancesWithoutUnboundedLocalQueue() throws Exception {
        InMemoryRelayCoordination.Bus bus = new InMemoryRelayCoordination.Bus();
        RelayHub pluginNode = new RelayHub(json, properties, new InMemoryRelayCoordination(bus),
                new RelayCapacityProperties(1_000, 65_536, Duration.ofSeconds(30)));
        RelayHub browserNode = new RelayHub(json, properties, new InMemoryRelayCoordination(bus),
                new RelayCapacityProperties(1_000, 65_536, Duration.ofSeconds(30)));
        var id = java.util.UUID.randomUUID();
        List<WebSocketMessage<?>> pluginMessages = new ArrayList<>(), browserMessages = new ArrayList<>();
        pluginNode.register(RelaySocketHandler.Role.PLUGIN, id, socket("plugin-node", pluginMessages), 0);
        browserNode.register(RelaySocketHandler.Role.BROWSER, id, socket("browser-node", browserMessages), 0);
        pluginMessages.clear(); browserMessages.clear();

        pluginNode.publish(RelaySocketHandler.Role.PLUGIN, id, 1, new TextMessage("signed-envelope"));

        assertEquals(List.of("signed-envelope"), browserMessages.stream()
                .map(message -> ((TextMessage) message).getPayload()).toList());
        assertTrue(pluginMessages.isEmpty());
    }

    @Test void sharesBoundedConnectionPresenceAcrossBackendInstances() throws Exception {
        InMemoryRelayCoordination.Bus bus = new InMemoryRelayCoordination.Bus();
        RelayCapacityProperties capacity = new RelayCapacityProperties(1_000, 65_536, Duration.ofMillis(80));
        RelayHub pluginNode = new RelayHub(json, properties, new InMemoryRelayCoordination(bus), capacity);
        RelayHub pageNode = new RelayHub(json, properties, new InMemoryRelayCoordination(bus), capacity);
        var id = java.util.UUID.randomUUID();
        WebSocketSession plugin = socket("plugin-presence", new ArrayList<>());

        assertFalse(pageNode.connected(RelaySocketHandler.Role.PLUGIN, id));
        pluginNode.register(RelaySocketHandler.Role.PLUGIN, id, plugin, 0);
        assertTrue(pageNode.connected(RelaySocketHandler.Role.PLUGIN, id));
        pluginNode.unregister(RelaySocketHandler.Role.PLUGIN, id, plugin);
        assertFalse(pageNode.connected(RelaySocketHandler.Role.PLUGIN, id));

        InMemoryRelayCoordination first = new InMemoryRelayCoordination(bus);
        InMemoryRelayCoordination second = new InMemoryRelayCoordination(bus);
        first.connected(RelaySocketHandler.Role.PLUGIN, id, Duration.ofMillis(20));
        assertTrue(second.isConnected(RelaySocketHandler.Role.PLUGIN, id));
        Thread.sleep(40);
        assertFalse(second.isConnected(RelaySocketHandler.Role.PLUGIN, id));
    }

    @Test void pluginDisconnectImmediatelyClosesLocalAndRemoteBrowserSockets() throws Exception {
        InMemoryRelayCoordination.Bus bus = new InMemoryRelayCoordination.Bus();
        RelayCapacityProperties capacity = new RelayCapacityProperties(1_000, 65_536, Duration.ofSeconds(30));
        RelayHub pluginNode = new RelayHub(json, properties, new InMemoryRelayCoordination(bus), capacity);
        RelayHub remoteBrowserNode = new RelayHub(json, properties, new InMemoryRelayCoordination(bus), capacity);
        var remoteId = java.util.UUID.randomUUID();
        WebSocketSession plugin = socket("plugin-disconnect", new ArrayList<>());
        WebSocketSession remoteBrowser = socket("remote-browser", new ArrayList<>());
        pluginNode.register(RelaySocketHandler.Role.PLUGIN, remoteId, plugin, 0);
        remoteBrowserNode.register(RelaySocketHandler.Role.BROWSER, remoteId, remoteBrowser, 0);

        pluginNode.unregister(RelaySocketHandler.Role.PLUGIN, remoteId, plugin);

        verify(remoteBrowser).close(argThat(status -> status.getCode() == CloseStatus.SERVICE_RESTARTED.getCode()));
        assertFalse(remoteBrowserNode.connected(RelaySocketHandler.Role.BROWSER, remoteId));

        RelayHub localHub = new RelayHub(json, properties, new InMemoryRelayCoordination(bus), capacity);
        var localId = java.util.UUID.randomUUID();
        WebSocketSession localPlugin = socket("local-plugin", new ArrayList<>());
        WebSocketSession localBrowser = socket("local-browser", new ArrayList<>());
        localHub.register(RelaySocketHandler.Role.PLUGIN, localId, localPlugin, 0);
        localHub.register(RelaySocketHandler.Role.BROWSER, localId, localBrowser, 0);
        localHub.unregister(RelaySocketHandler.Role.PLUGIN, localId, localPlugin);
        verify(localBrowser).close(argThat(status -> status.getCode() == CloseStatus.SERVICE_RESTARTED.getCode()));
    }

    private WebSocketSession socket(String id, List<WebSocketMessage<?>> messages) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.getUri()).thenReturn(URI.create("wss://editor.example/ws"));
        when(socket.isOpen()).thenReturn(true);
        doAnswer(invocation -> { messages.add(invocation.getArgument(0)); return null; })
                .when(socket).sendMessage(any(WebSocketMessage.class));
        return socket;
    }
}
