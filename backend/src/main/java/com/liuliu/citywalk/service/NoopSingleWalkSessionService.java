package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.request.SingleWalkSessionRequest;
import com.liuliu.citywalk.model.dto.response.SingleWalkSessionResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnMissingBean(SingleWalkSessionService.class)
public class NoopSingleWalkSessionService implements SingleWalkSessionService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public SingleWalkSessionResponse loadSession(Long userId) {
        return null;
    }

    @Override
    public void saveSession(Long userId, SingleWalkSessionRequest request) {
    }

    @Override
    public void clearSession(Long userId) {
    }
}
