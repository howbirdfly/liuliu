package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.CommunityWalkQueryRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CommunityMapper {

    List<CommunityWalkQueryRow> searchPublicWalks(@Param("keyword") String keyword,
                                                  @Param("limit") int limit,
                                                  @Param("offset") int offset);

    List<CommunityWalkQueryRow> listLatestPublicWalks(@Param("limit") int limit,
                                                      @Param("offset") int offset);

    List<CommunityWalkQueryRow> listHotPublicWalks(@Param("limit") int limit,
                                                   @Param("offset") int offset);

    List<CommunityWalkQueryRow> listRecommendedPublicWalks(@Param("limit") int limit,
                                                           @Param("offset") int offset);
}
