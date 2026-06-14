package com.liuliu.citywalk.service;

public interface AgentToolResultCacheService {

    boolean isEnabled();

    String get(String invocationSignature);

    void put(String invocationSignature, String output);
}
