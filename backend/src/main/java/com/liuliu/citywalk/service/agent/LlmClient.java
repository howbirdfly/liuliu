package com.liuliu.citywalk.service.agent;

public interface LlmClient {

    String provider();

    String model();

    LlmResponse createResponse(LlmRequest request);

    default LlmResponse createStreamingResponse(LlmRequest request, LlmStreamListener listener) {
        LlmResponse response = createResponse(request);
        if (listener != null && response != null && response.content() != null && !response.content().isEmpty()) {
            listener.onContentDelta(response.content());
        }
        return response;
    }

    interface LlmStreamListener {
        void onContentDelta(String delta);
    }
}
