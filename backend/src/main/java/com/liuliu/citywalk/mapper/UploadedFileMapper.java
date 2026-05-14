package com.liuliu.citywalk.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UploadedFileMapper {

    int insertFile(@Param("userId") Long userId,
                   @Param("bizType") String bizType,
                   @Param("fileKey") String fileKey,
                   @Param("fileName") String fileName,
                   @Param("fileUrl") String fileUrl,
                   @Param("contentType") String contentType,
                   @Param("fileSize") Long fileSize);

    int deleteByUserId(@Param("userId") Long userId);

    List<String> listFileKeysByUserId(@Param("userId") Long userId);
}
