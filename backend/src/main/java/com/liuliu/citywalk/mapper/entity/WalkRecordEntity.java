package com.liuliu.citywalk.mapper.entity;

import java.sql.Timestamp;

public class WalkRecordEntity {

    private Long id;
    private Long userId;
    private String themeTitle;
    private String themeSnapshot;
    private String locationName;
    private String locationContext;
    private String routePoints;
    private String missionsCompleted;
    private String missionReviews;
    private String photoList;
    private String coverImage;
    private String noteText;
    private Boolean isPublic;
    private String walkMode;
    private String generationSource;
    private String status;
    private Timestamp createdAt;
    private Timestamp updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getThemeTitle() {
        return themeTitle;
    }

    public void setThemeTitle(String themeTitle) {
        this.themeTitle = themeTitle;
    }

    public String getThemeSnapshot() {
        return themeSnapshot;
    }

    public void setThemeSnapshot(String themeSnapshot) {
        this.themeSnapshot = themeSnapshot;
    }

    public String getLocationName() {
        return locationName;
    }

    public void setLocationName(String locationName) {
        this.locationName = locationName;
    }

    public String getLocationContext() {
        return locationContext;
    }

    public void setLocationContext(String locationContext) {
        this.locationContext = locationContext;
    }

    public String getRoutePoints() {
        return routePoints;
    }

    public void setRoutePoints(String routePoints) {
        this.routePoints = routePoints;
    }

    public String getMissionsCompleted() {
        return missionsCompleted;
    }

    public void setMissionsCompleted(String missionsCompleted) {
        this.missionsCompleted = missionsCompleted;
    }

    public String getMissionReviews() {
        return missionReviews;
    }

    public void setMissionReviews(String missionReviews) {
        this.missionReviews = missionReviews;
    }

    public String getPhotoList() {
        return photoList;
    }

    public void setPhotoList(String photoList) {
        this.photoList = photoList;
    }

    public String getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(String coverImage) {
        this.coverImage = coverImage;
    }

    public String getNoteText() {
        return noteText;
    }

    public void setNoteText(String noteText) {
        this.noteText = noteText;
    }

    public Boolean getIsPublic() {
        return isPublic;
    }

    public void setIsPublic(Boolean isPublic) {
        this.isPublic = isPublic;
    }

    public String getWalkMode() {
        return walkMode;
    }

    public void setWalkMode(String walkMode) {
        this.walkMode = walkMode;
    }

    public String getGenerationSource() {
        return generationSource;
    }

    public void setGenerationSource(String generationSource) {
        this.generationSource = generationSource;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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
