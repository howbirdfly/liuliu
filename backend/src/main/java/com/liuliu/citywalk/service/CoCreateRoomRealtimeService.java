package com.liuliu.citywalk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomResponse;
import com.liuliu.citywalk.model.dto.response.CoCreateRoomSocketEventResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CoCreateRoomRealtimeService {

    private static final Logger log = LoggerFactory.getLogger(CoCreateRoomRealtimeService.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Set<WebSocketSession>> sessionsByRoomCode = new ConcurrentHashMap<>();

    public CoCreateRoomRealtimeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
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
        broadcast(roomCode, new CoCreateRoomSocketEventResponse("room_snapshot", roomCode, room));
    }

    public void broadcastRoomClosed(String roomCode) {
        if (roomCode == null || roomCode.isBlank()) {
            return;
        }
        broadcast(roomCode, new CoCreateRoomSocketEventResponse("room_closed", roomCode, null));
    }

    public void sendRoomSnapshot(String roomCode, WebSocketSession session, CoCreateRoomResponse room) {
        if (session == null || room == null || !session.isOpen()) {
            return;
        }
        send(session, new CoCreateRoomSocketEventResponse("room_snapshot", roomCode, room));
    }

    private void broadcast(String roomCode, CoCreateRoomSocketEventResponse payload) {
        Set<WebSocketSession> sessions = sessionsByRoomCode.get(roomCode);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions) {
            send(session, payload);
        }
    }

    private void send(WebSocketSession session, CoCreateRoomSocketEventResponse payload) {
        if (session == null || payload == null || !session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
        } catch (Exception error) {
            log.warn("Failed to push co-create room websocket event: {}", error.getMessage());
        }
    }
}
