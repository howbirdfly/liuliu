package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

    UserEntity findByOpenid(@Param("openid") String openid);

    UserEntity findById(@Param("id") Long id);

    int insertWebUser(@Param("openid") String openid,
                      @Param("nickname") String nickname,
                      @Param("avatarUrl") String avatarUrl,
                      @Param("bio") String bio);

    int insertDebugUser(@Param("id") Long id,
                        @Param("openid") String openid,
                        @Param("nickname") String nickname,
                        @Param("avatarUrl") String avatarUrl,
                        @Param("bio") String bio,
                        @Param("source") String source);

    int updateProfile(@Param("id") Long id,
                      @Param("nickname") String nickname,
                      @Param("avatarUrl") String avatarUrl,
                      @Param("bio") String bio);

    int deleteById(@Param("id") Long id);

    Integer countById(@Param("id") Long id);
}
