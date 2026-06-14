package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.config.CoCreateRoomProperties;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomMemberResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomSocketEventResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomThemeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CoCreateRoomRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(CoCreateRoomRealtimeService.class);

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final CoCreateRoomProperties coCreateRoomProperties;
    private final Map<String, Set<WebSocketSession>> sessionsByRoomCode = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();

    public CoCreateRoomRealtimeService(
            ObjectMapper objectMapper,
            StringRedisTemplate stringRedisTemplate,
            CoCreateRoomProperties coCreateRoomProperties
    ) {
        this.objectMapper = objectMapper;
        this.stringRedisTemplate = stringRedisTemplate;
        this.coCreateRoomProperties = coCreateRoomProperties;
    }

    public void register(String roomCode, WebSocketSession session) {
        sessionsByRoomCode.computeIfAbsent(roomCode, key -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String roomCode, WebSocketSession session) {
        if (roomCode == null || session == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByRoomCode.get(roomCode);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByRoomCode.remove(roomCode);
        }
    }

    public void broadcastRoomSnapshot(String roomCode, CoCreateRoomResponse room) {
        if (roomCode == null || roomCode.isBlank() || room == null) {
            return;
        }
        CoCreateRoomSocketEventResponse payload = new CoCreateRoomSocketEventResponse(
                "room_snapshot",
                roomCode,
                room,
                null,
                null,
                null,
                room.ownerUserId()
        );
        broadcast(roomCode, payload);
        publishClusterEvent(payload);
    }

    public void broadcastRoomClosed(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return;
        }
        CoCreateRoomSocketEventResponse payload = new CoCreateRoomSocketEventResponse(
                "room_closed",
                roomCode,
                null,
                null,
                null,
                null,
                null
        );
        broadcast(roomCode, payload);
        publishClusterEvent(payload);
    }

    public void broadcastMemberJoined(String roomCode, CoCreateRoomMemberResponse member, Long ownerUserId) {
        if (roomCode == null || roomCode.isBlank() || member == null) {
            return;
        }
        CoCreateRoomSocketEventResponse payload = new CoCreateRoomSocketEventResponse(
                "member_joined",
                roomCode,
                null,
                member,
                member.userId(),
                null,
                ownerUserId
        );
        broadcast(roomCode, payload);
        publishClusterEvent(payload);
    }

    public void broadcastMemberLeft(String roomCode, Long memberUserId, Long ownerUserId) {
        if (roomCode == null || roomCode.isBlank() || memberUserId == null || memberUserId <= 0) {
            return;
        }
        CoCreateRoomSocketEventResponse payload = new CoCreateRoomSocketEventResponse(
                "member_left",
                roomCode,
                null,
                null,
                memberUserId,
                null,
                ownerUserId
        );
        broadcast(roomCode, payload);
        publishClusterEvent(payload);
    }

    public void broadcastMemberStateUpdated(String roomCode, CoCreateRoomMemberResponse member, Long ownerUserId) {
        if (roomCode == null || roomCode.isBlank() || member == null) {
            return;
        }
        CoCreateRoomSocketEventResponse payload = new CoCreateRoomSocketEventResponse(
                "member_state_updated",
                roomCode,
                null,
                member,
                member.userId(),
                null,
                ownerUserId
        );
        broadcast(roomCode, payload);
        publishClusterEvent(payload);
    }

    public void broadcastThemeUpdated(String roomCode, CoCreateRoomThemeResponse theme, Long ownerUserId) {
        if (roomCode == null || roomCode.isBlank() || theme == null) {
            return;
        }
        CoCreateRoomSocketEventResponse payload = new CoCreateRoomSocketEventResponse(
                "theme_updated",
                roomCode,
                null,
                null,
                null,
                theme,
                ownerUserId
        );
        broadcast(roomCode, payload);
        publishClusterEvent(payload);
    }

    public void sendRoomSnapshot(String roomCode, WebSocketSession session, CoCreateRoomResponse room) {
        if (session == null || room == null || !session.isOpen()) {
            return;
        }
        send(roomCode, session, new CoCreateRoomSocketEventResponse(
                "room_snapshot",
                roomCode,
                room,
                null,
                null,
                null,
                room.ownerUserId()
        ));
    }

    public void sendPong(String roomCode, WebSocketSession session) {
        if (roomCode == null || roomCode.isBlank() || session == null || !session.isOpen()) {
            return;
        }
        send(roomCode, session, new CoCreateRoomSocketEventResponse(
                "pong",
                roomCode,
                null,
                null,
                null,
                null,
                null
        ));
    }

    public void handleClusterBroadcast(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        try {
            ClusterBroadcastEvent event = objectMapper.readValue(message, ClusterBroadcastEvent.class);
            if (event == null || event.payload() == null) {
                return;
            }
            if (instanceId.equals(event.originInstanceId())) {
                return;
            }
            CoCreateRoomSocketEventResponse payload = event.payload();
            String roomCode = payload.roomCode();
            if (roomCode == null || roomCode.isBlank()) {
                return;
            }
            broadcast(roomCode, payload);
        } catch (Exception error) {
            log.warn("Failed to consume co-create room cluster broadcast: {}", error.getMessage());
        }
    }

    private void broadcast(String roomCode, CoCreateRoomSocketEventResponse payload) {
        Set<WebSocketSession> sessions = sessionsByRoomCode.get(roomCode);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions) {
            send(roomCode, session, payload);
        }
    }

    private void send(String roomCode, WebSocketSession session, CoCreateRoomSocketEventResponse payload) {
        if (session == null || payload == null) {
            return;
        }
        if (!session.isOpen()) {
            unregister(roomCode, session);
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception error) {
            log.warn("Failed to push co-create room websocket event: {}", error.getMessage());
            unregister(roomCode, session);
            try {
                if (session.isOpen()) {
                    session.close();
                }
            } catch (Exception closeError) {
                log.debug("Failed to close co-create room websocket session after send failure: {}", closeError.getMessage());
            }
        }
    }

    private void publishClusterEvent(CoCreateRoomSocketEventResponse payload) {
        if (payload == null || !coCreateRoomProperties.isClusterBroadcastEnabled()) {
            return;
        }
        String channel = coCreateRoomProperties.getClusterBroadcastChannel();
        if (channel == null || channel.isBlank()) {
            return;
        }
        try {
            String message = objectMapper.writeValueAsString(new ClusterBroadcastEvent(instanceId, payload));
            stringRedisTemplate.convertAndSend(channel, message);
        } catch (Exception error) {
            log.warn("Failed to publish co-create room cluster broadcast: {}", error.getMessage());
        }
    }

    private record ClusterBroadcastEvent(
            String originInstanceId,
            CoCreateRoomSocketEventResponse payload
    ) {
    }
}
