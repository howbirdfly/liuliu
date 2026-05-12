package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommunityMapper {

    List<CommunityWalkQueryRow> searchPublicWalks(@Param("keyword") String keyword,
                                                  @Param("currentUserId") Long currentUserId,
                                                  @Param("limit") int limit,
                                                  @Param("offset") int offset);

    List<CommunityWalkQueryRow> listLatestPublicWalks(@Param("currentUserId") Long currentUserId,
                                                      @Param("limit") int limit,
                                                      @Param("offset") int offset);

    List<CommunityWalkQueryRow> listHotPublicWalks(@Param("currentUserId") Long currentUserId,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);

    List<CommunityWalkQueryRow> listRecommendedPublicWalks(@Param("currentUserId") Long currentUserId,
                                                           @Param("limit") int limit,
                                                           @Param("offset") int offset);

    List<CommunityWalkQueryRow> listLikedWalks(@Param("currentUserId") Long currentUserId,
                                               @Param("limit") int limit,
                                               @Param("offset") int offset);

    List<CommunityWalkQueryRow> listFavoritedWalks(@Param("currentUserId") Long currentUserId,
                                                   @Param("limit") int limit,
                                                   @Param("offset") int offset);
}
