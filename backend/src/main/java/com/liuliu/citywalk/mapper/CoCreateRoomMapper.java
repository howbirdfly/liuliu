package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.CoCreateRoomEntity;
import com.liuliu.citywalk.mapper.entity.CoCreateRoomMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

@Mapper
public interface CoCreateRoomMapper {

    int insertRoom(CoCreateRoomEntity entity);

    CoCreateRoomEntity findActiveRoomByCode(@Param("roomCode") String roomCode);

    CoCreateRoomEntity findActiveRoomById(@Param("roomId") Long roomId);

    int updateRoomTheme(@Param("roomId") Long roomId, @Param("themeSnapshot") String themeSnapshot);

    int changeOwner(@Param("roomId") Long roomId, @Param("ownerUserId") Long ownerUserId);

    int deleteMembersByRoomId(@Param("roomId") Long roomId);

    int deleteRoomById(@Param("roomId") Long roomId);

    Integer countMembers(@Param("roomId") Long roomId);

    CoCreateRoomMemberEntity findMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    List<CoCreateRoomMemberEntity> listMembers(@Param("roomId") Long roomId);

    int upsertMember(CoCreateRoomMemberEntity entity);

    int updateMemberState(@Param("roomId") Long roomId,
                          @Param("userId") Long userId,
                          @Param("routePoints") String routePoints,
                          @Param("currentPosition") String currentPosition,
                          @Param("completedMissions") String completedMissions,
                          @Param("isTracking") Boolean isTracking);

    int deleteMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

    int deleteInactiveMembers(@Param("roomId") Long roomId, @Param("cutoff") Timestamp cutoff);
}
