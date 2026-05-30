package com.liuliu.citywalk.service;

import com.liuliu.citywalk.model.dto.response.CommunityCommentResponse;
import com.liuliu.citywalk.model.dto.response.CommunityWalkResponse;

import java.util.List;

public interface CommunityCacheService {

    boolean isEnabled();

    List<CommunityWalkResponse> getFeed(String feedType, int page, int pageSize);

    void putFeed(String feedType, int page, int pageSize, List<CommunityWalkResponse> value);

    void evictAllFeeds();

    CommunityWalkResponse getWalkDetail(Long walkId);

    void putWalkDetail(Long walkId, CommunityWalkResponse value);

    void evictWalkDetail(Long walkId);

    List<CommunityCommentResponse> getComments(Long walkId);

    void putComments(Long walkId, List<CommunityCommentResponse> value);

    void evictComments(Long walkId);

    long incrementBufferedViewCount(Long walkId);

    long getBufferedViewCount(Long walkId);
}
