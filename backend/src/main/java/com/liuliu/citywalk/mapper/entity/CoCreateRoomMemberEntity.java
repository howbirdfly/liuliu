package com.liuliu.citywalk.mapper.entity;

import java.sql.Timestamp;

public class CoCreateRoomMemberEntity {

    private Long id;
    private Long roomId;
    private Long userId;
    private String nickname;
    private String avatarUrl;
    private String trackColor;
    private String routePoints;
    private String currentPosition;
    private String completedMissions;
    private Boolean isTracking;
    private Timestamp lastActiveAt;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getTrackColor() {
        return trackColor;
    }

    public void setTrackColor(String trackColor) {
        this.trackColor = trackColor;
    }

    public String getRoutePoints() {
        return routePoints;
    }

    public void setRoutePoints(String routePoints) {
        this.routePoints = routePoints;
    }

    public String getCurrentPosition() {
        return currentPosition;
    }

    public void setCurrentPosition(String currentPosition) {
        this.currentPosition = currentPosition;
    }

    public String getCompletedMissions() {
        return completedMissions;
    }

    public void setCompletedMissions(String completedMissions) {
        this.completedMissions = completedMissions;
    }

    public Boolean getIsTracking() {
        return isTracking;
    }

    public void setIsTracking(Boolean isTracking) {
        this.isTracking = isTracking;
    }

    public Timestamp getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(Timestamp lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }
}
