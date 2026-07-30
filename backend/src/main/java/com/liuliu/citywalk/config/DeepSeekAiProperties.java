package com.liuliu.citywalk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "liuliu.ai.deepseek")
public class DeepSeekAiProperties {

    private int connectTimeoutMs = 10000;
    private int requestTimeoutMs = 60000;
    private int transientFailureMaxRetries = 2;
    private long transientFailureInitialBackoffMs = 800L;
    private long transientFailureMaxBackoffMs = 2500L;
    private int promptAutoCompactTokenThreshold = 18000;
    private int promptMicroCompactKeepToolMessages = 4;

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getTransientFailureMaxRetries() {
        return transientFailureMaxRetries;
    }

    public void setTransientFailureMaxRetries(int transientFailureMaxRetries) {
        this.transientFailureMaxRetries = transientFailureMaxRetries;
    }

    public long getTransientFailureInitialBackoffMs() {
        return transientFailureInitialBackoffMs;
    }

    public void setTransientFailureInitialBackoffMs(long transientFailureInitialBackoffMs) {
        this.transientFailureInitialBackoffMs = transientFailureInitialBackoffMs;
    }

    public long getTransientFailureMaxBackoffMs() {
        return transientFailureMaxBackoffMs;
    }

    public void setTransientFailureMaxBackoffMs(long transientFailureMaxBackoffMs) {
        this.transientFailureMaxBackoffMs = transientFailureMaxBackoffMs;
    }

    public int getPromptAutoCompactTokenThreshold() {
        return promptAutoCompactTokenThreshold;
    }

    public void setPromptAutoCompactTokenThreshold(int promptAutoCompactTokenThreshold) {
        this.promptAutoCompactTokenThreshold = promptAutoCompactTokenThreshold;
    }

    public int getPromptMicroCompactKeepToolMessages() {
        return promptMicroCompactKeepToolMessages;
    }

    public void setPromptMicroCompactKeepToolMessages(int promptMicroCompactKeepToolMessages) {
        this.promptMicroCompactKeepToolMessages = promptMicroCompactKeepToolMessages;
    }
}
