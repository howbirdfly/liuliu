package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WalkRecordMapper {

    int insert(WalkRecordEntity entity);

    List<WalkRecordEntity> findMyActive(@Param("userId") Long userId, @Param("limit") int limit);

    WalkRecordEntity findLatestMyActive(@Param("userId") Long userId);

    List<WalkRecordEntity> findPublicActive(@Param("limit") int limit);

    WalkRecordEntity findActiveById(@Param("id") Long id);

    WalkRecordEntity findPublicActiveById(@Param("id") Long id);

    int updateEditableFields(@Param("id") Long id,
                             @Param("themeTitle") String themeTitle,
                             @Param("themeSnapshot") String themeSnapshot,
                             @Param("noteText") String noteText,
                             @Param("isPublic") Boolean isPublic);

    int softDeleteById(@Param("id") Long id);

    List<String> listTagsByWalkId(@Param("walkId") Long walkId);

    int deleteTagsByWalkId(@Param("walkId") Long walkId);

    int insertTags(@Param("walkId") Long walkId, @Param("tags") List<String> tags);
}
