package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.UserCredentialEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserCredentialMapper {

    UserCredentialEntity findByEmail(@Param("email") String email);

    int insertCredential(@Param("userId") Long userId,
                         @Param("email") String email,
                         @Param("passwordHash") String passwordHash);

    int updatePassword(@Param("userId") Long userId,
                       @Param("passwordHash") String passwordHash);
}
