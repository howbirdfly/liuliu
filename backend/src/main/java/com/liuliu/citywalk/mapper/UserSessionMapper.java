package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.UserSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSessionMapper {

    int insertSession(@Param("userId") Long userId,
                      @Param("accessToken") String accessToken,
                      @Param("refreshToken") String refreshToken,
                      @Param("expiresInSeconds") long expiresInSeconds,
                      @Param("clientType") String clientType);

    UserSessionEntity findValidByAccessToken(@Param("accessToken") String accessToken);
}
