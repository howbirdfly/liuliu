package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.EmailVerificationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

@Mapper
public interface EmailVerificationMapper {

    EmailVerificationEntity findLatestValid(@Param("email") String email);

    EmailVerificationEntity findLatest(@Param("email") String email);

    int insertRecord(@Param("email") String email,
                     @Param("codeHash") String codeHash,
                     @Param("expiresAt") Timestamp expiresAt);

    int markUsed(@Param("id") Long id);
}
