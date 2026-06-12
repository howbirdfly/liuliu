package com.liuliu.citywalk.mapper.entity;

import java.sql.Timestamp;

public class AgentUserMemoryEntity {

    private Long userId;
    private String preferredCities;
    private String preferredAreas;
    private String walkStyles;
    private String preferredDuration;
    private String mobilityLevel;
    private String avoidTags;
    private String recentSuggestedAreas;
    private String summary;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPreferredCities() {
        return preferredCities;
    }

    public void setPreferredCities(String preferredCities) {
        this.preferredCities = preferredCities;
    }

    public String getPreferredAreas() {
        return preferredAreas;
    }

    public void setPreferredAreas(String preferredAreas) {
        this.preferredAreas = preferredAreas;
    }

    public String getWalkStyles() {
        return walkStyles;
    }

    public void setWalkStyles(String walkStyles) {
        this.walkStyles = walkStyles;
    }

    public String getPreferredDuration() {
        return preferredDuration;
    }

    public void setPreferredDuration(String preferredDuration) {
        this.preferredDuration = preferredDuration;
    }

    public String getMobilityLevel() {
        return mobilityLevel;
    }

    public void setMobilityLevel(String mobilityLevel) {
        this.mobilityLevel = mobilityLevel;
    }

    public String getAvoidTags() {
        return avoidTags;
    }

    public void setAvoidTags(String avoidTags) {
        this.avoidTags = avoidTags;
    }

    public String getRecentSuggestedAreas() {
        return recentSuggestedAreas;
    }

    public void setRecentSuggestedAreas(String recentSuggestedAreas) {
        this.recentSuggestedAreas = recentSuggestedAreas;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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
