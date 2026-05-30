package com.liuliu.citywalk.service.agent;

public interface LlmClient {

    String provider();

    String model();

    LlmResponse createResponse(LlmRequest request);
}
