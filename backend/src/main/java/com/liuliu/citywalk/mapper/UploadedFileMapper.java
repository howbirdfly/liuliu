package com.liuliu.citywalk.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UploadedFileMapper {

    int insertFile(@Param("userId") Long userId,
                   @Param("bizType") String bizType,
                   @Param("fileKey") String fileKey,
                   @Param("fileName") String fileName,
                   @Param("fileUrl") String fileUrl,
                   @Param("contentType") String contentType,
                   @Param("fileSize") Long fileSize);
}
