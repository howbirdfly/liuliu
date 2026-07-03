package com.liuliu.citywalk.service.agent;

public record LlmOptions(
        Double temperature,
        String toolChoice
) {

    public static LlmOptions ofTemperature(Double temperature) {
        return new LlmOptions(temperature, null);
    }
}
