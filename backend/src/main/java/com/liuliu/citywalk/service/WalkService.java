package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.request.CreateWalkRequest;
import com.liuliu.citywalk.model.dto.response.WalkResponse;
import com.liuliu.citywalk.repository.WalkRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WalkService {

    private final WalkRepository walkRepository;

    public WalkService(WalkRepository walkRepository) {
        this.walkRepository = walkRepository;
    }

    public WalkResponse create(Long userId, CreateWalkRequest request) {
        return walkRepository.create(userId, request);
    }

    public List<WalkResponse> listMyWalks(Long userId, int limit) {
        return walkRepository.listMyWalks(userId, limit);
    }

    public List<WalkResponse> listPublicWalks(int limit) {
        return walkRepository.listPublicWalks(limit);
    }

    public WalkResponse getDetail(Long id) {
        return walkRepository.findById(String.valueOf(id)).orElse(null);
    }
}
