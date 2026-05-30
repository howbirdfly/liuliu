package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.CommunityCommentResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(
        prefix = "liuliu.redis.community-cache",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class NoopCommunityCacheService implements CommunityCacheService {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public List<CommunityWalkResponse> getFeed(String feedType, int page, int pageSize) {
        return null;
    }

    @Override
    public void putFeed(String feedType, int page, int pageSize, List<CommunityWalkResponse> value) {
    }

    @Override
    public void evictAllFeeds() {
    }

    @Override
    public CommunityWalkResponse getWalkDetail(Long walkId) {
        return null;
    }

    @Override
    public void putWalkDetail(Long walkId, CommunityWalkResponse value) {
    }

    @Override
    public void evictWalkDetail(Long walkId) {
    }

    @Override
    public List<CommunityCommentResponse> getComments(Long walkId) {
        return null;
    }

    @Override
    public void putComments(Long walkId, List<CommunityCommentResponse> value) {
    }

    @Override
    public void evictComments(Long walkId) {
    }

    @Override
    public long incrementBufferedViewCount(Long walkId) {
        return 0L;
    }

    @Override
    public long getBufferedViewCount(Long walkId) {
        return 0L;
    }
}
