package com.liuliu.citywalk.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomChatMessageResponse;
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
import java.util.UUID;

@Component
public class CoCreateRoomWebSocketHandler extends TextWebSocketHandler {

    private static final String ROOM_CODE_KEY = "roomCode";
    private static final String USER_ID_KEY = "userId";
    private static final String USER_NICKNAME_KEY = "nickname";
    private static final String USER_AVATAR_KEY = "avatarUrl";
    private static final int MAX_CHAT_LENGTH = 500;

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
        session.getAttributes().put(USER_ID_KEY, user.id());
        session.getAttributes().put(USER_NICKNAME_KEY, safeNickname(user.nickName()));
        session.getAttributes().put(USER_AVATAR_KEY, safeAvatar(user.avatarUrl()));
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
            String type = payload.path("type").asText("");
            if ("ping".equalsIgnoreCase(type)) {
                coCreateRoomRealtimeService.sendPong(value, session);
                return;
            }
            if ("chat_message".equalsIgnoreCase(type)) {
                handleChatMessage(value, session, payload);
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

    private void handleChatMessage(String roomCode, WebSocketSession session, JsonNode payload) {
        Long userId = readLongAttribute(session, USER_ID_KEY);
        String nickname = readStringAttribute(session, USER_NICKNAME_KEY);
        String avatarUrl = readStringAttribute(session, USER_AVATAR_KEY);
        if (userId == null || userId <= 0 || nickname == null || nickname.isBlank()) {
            return;
        }

        String content = normalizeChatContent(payload.path("content").asText(""));
        if (content.isBlank()) {
            return;
        }

        coCreateRoomRealtimeService.broadcastChatMessage(
                roomCode,
                new CoCreateRoomChatMessageResponse(
                        UUID.randomUUID().toString(),
                        userId,
                        nickname,
                        avatarUrl,
                        content,
                        System.currentTimeMillis()
                ),
                null
        );
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

    private String normalizeChatContent(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= MAX_CHAT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_CHAT_LENGTH);
    }

    private Long readLongAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof Long number ? number : null;
    }

    private String readStringAttribute(WebSocketSession session, String key) {
        Object value = session.getAttributes().get(key);
        return value instanceof String text ? text : null;
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

    private String safeNickname(String nickname) {
        return nickname == null || nickname.isBlank() ? "漫步同伴" : nickname.trim();
    }

    private String safeAvatar(String avatarUrl) {
        return avatarUrl == null ? "" : avatarUrl.trim();
    }
}