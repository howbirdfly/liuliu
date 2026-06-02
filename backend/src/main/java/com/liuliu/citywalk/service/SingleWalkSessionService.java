package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.request.SingleWalkSessionRequest;
import com.liuliu.citywalk.model.dto.response.SingleWalkSessionResponse;

public interface SingleWalkSessionService {

    boolean isEnabled();

    SingleWalkSessionResponse loadSession(Long userId);

    void saveSession(Long userId, SingleWalkSessionRequest request);

    void clearSession(Long userId);
}
