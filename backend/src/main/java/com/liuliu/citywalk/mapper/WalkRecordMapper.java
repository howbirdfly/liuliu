package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.WalkRecordEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WalkRecordMapper {

    int insert(WalkRecordEntity entity);

    List<WalkRecordEntity> findMyActive(@Param("userId") Long userId, @Param("limit") int limit);

    List<WalkRecordEntity> findPublicActive(@Param("limit") int limit);

    WalkRecordEntity findActiveById(@Param("id") Long id);

    WalkRecordEntity findPublicActiveById(@Param("id") Long id);
}
