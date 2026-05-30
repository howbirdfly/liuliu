package com.liuliu.citywalk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "liuliu.redis.community-cache")
public class CommunityCacheProperties {

    private boolean enabled;
    private String keyPrefix = "liuliu:community:";
    private long feedTtlSeconds = 60L;
    private long detailTtlSeconds = 120L;
    private long commentTtlSeconds = 60L;
    private long viewBufferTtlSeconds = 43200L;
    private long viewFlushIntervalMs = 15000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public long getFeedTtlSeconds() {
        return feedTtlSeconds;
    }

    public void setFeedTtlSeconds(long feedTtlSeconds) {
        this.feedTtlSeconds = feedTtlSeconds;
    }

    public long getDetailTtlSeconds() {
        return detailTtlSeconds;
    }

    public void setDetailTtlSeconds(long detailTtlSeconds) {
        this.detailTtlSeconds = detailTtlSeconds;
    }

    public long getCommentTtlSeconds() {
        return commentTtlSeconds;
    }

    public void setCommentTtlSeconds(long commentTtlSeconds) {
        this.commentTtlSeconds = commentTtlSeconds;
    }

    public long getViewBufferTtlSeconds() {
        return viewBufferTtlSeconds;
    }

    public void setViewBufferTtlSeconds(long viewBufferTtlSeconds) {
        this.viewBufferTtlSeconds = viewBufferTtlSeconds;
    }

    public long getViewFlushIntervalMs() {
        return viewFlushIntervalMs;
    }

    public void setViewFlushIntervalMs(long viewFlushIntervalMs) {
        this.viewFlushIntervalMs = viewFlushIntervalMs;
    }
}
