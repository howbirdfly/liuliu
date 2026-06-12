package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.AgentUserMemoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AgentUserMemoryMapper {

    AgentUserMemoryEntity findByUserId(@Param("userId") Long userId);

    int upsertMemory(@Param("userId") Long userId,
                     @Param("preferredCities") String preferredCities,
                     @Param("preferredAreas") String preferredAreas,
                     @Param("walkStyles") String walkStyles,
                     @Param("preferredDuration") String preferredDuration,
                     @Param("mobilityLevel") String mobilityLevel,
                     @Param("avoidTags") String avoidTags,
                     @Param("recentSuggestedAreas") String recentSuggestedAreas,
                     @Param("summary") String summary);
}
