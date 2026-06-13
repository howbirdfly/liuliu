package com.liuliu.citywalk.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomResponse;
import com.liuliu.citywalk.service.CoCreateRoomRealtimeService;
import com.liuliu.citywalk.service.CoCreateRoomService;
import com.liuliu.citywalk.service.UserSessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Component
public class CoCreateRoomWebSocketHandler extends TextWebSocketHandler {

    private static final String ROOM_CODE_KEY = "roomCode";

    private final ObjectMapper objectMapper;
    private final UserSessionService userSessionService;
    private final CoCreateRoomService coCreateRoomService;
    private final CoCreateRoomRealtimeService coCreateRoomRealtimeService;

    public CoCreateRoomWebSocketHandler(
            ObjectMapper objectMapper,
            UserSessionService userSessionService,
            CoCreateRoomService coCreateRoomService,
            CoCreateRoomRealtimeService coCreateRoomRealtimeService
    ) {
        this.objectMapper = objectMapper;
        this.userSessionService = userSessionService;
        this.coCreateRoomService = coCreateRoomService;
        this.coCreateRoomRealtimeService = coCreateRoomRealtimeService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> query = parseQuery(session.getUri());
        String token = trimToNull(query.get("token"));
        String roomCode = normalizeRoomCode(query.get("roomCode"));
        UserSessionService.StoredUser user = userSessionService.resolveUserByToken(token);
        if (roomCode == null || user == null || user.isGuest()) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("login_required"));
            return;
        }

        CoCreateRoomResponse room;
        try {
            room = coCreateRoomService.getRoom("Bearer " + token, roomCode);
        } catch (Exception error) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("room_membership_required"));
            return;
        }

        session.getAttributes().put(ROOM_CODE_KEY, room.roomCode());
        coCreateRoomRealtimeService.register(room.roomCode(), session);
        coCreateRoomRealtimeService.sendRoomSnapshot(room.roomCode(), session, room);
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        Object roomCode = session.getAttributes().get(ROOM_CODE_KEY);
        if (!(roomCode instanceof String value)) {
            return;
        }
        try {
            JsonNode payload = objectMapper.readTree(message.getPayload());
            if ("ping".equalsIgnoreCase(payload.path("type").asText())) {
                coCreateRoomRealtimeService.sendPong(value, session);
            }
        } catch (Exception ignored) {
            // Ignore malformed client messages and keep the server push channel alive.
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object roomCode = session.getAttributes().get(ROOM_CODE_KEY);
        if (roomCode instanceof String value) {
            coCreateRoomRealtimeService.unregister(value, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        Object roomCode = session.getAttributes().get(ROOM_CODE_KEY);
        if (roomCode instanceof String value) {
            coCreateRoomRealtimeService.unregister(value, session);
        }
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private Map<String, String> parseQuery(URI uri) {
        Map<String, String> result = new HashMap<>();
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return result;
        }
        String[] pairs = uri.getQuery().split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            String[] segments = pair.split("=", 2);
            String key = URLDecoder.decode(segments[0], StandardCharsets.UTF_8);
            String value = segments.length > 1 ? URLDecoder.decode(segments[1], StandardCharsets.UTF_8) : "";
            result.put(key, value);
        }
        return result;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    private String normalizeRoomCode(String roomCode) {
        String normalized = trimToNull(roomCode);
        if (normalized == null) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
