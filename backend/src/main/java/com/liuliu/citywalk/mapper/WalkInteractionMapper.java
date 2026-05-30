package com.liuliu.citywalk.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WalkInteractionMapper {

    @Select("select count(1) from walk_record_likes where walk_id = #{walkId} and user_id = #{userId}")
    Integer countLike(@Param("walkId") Long walkId, @Param("userId") Long userId);

    @Select("select count(1) from walk_record_favorites where walk_id = #{walkId} and user_id = #{userId}")
    Integer countFavorite(@Param("walkId") Long walkId, @Param("userId") Long userId);

    @Insert("insert into walk_record_likes (walk_id, user_id, created_at) values (#{walkId}, #{userId}, now())")
    int insertLike(@Param("walkId") Long walkId, @Param("userId") Long userId);

    @Delete("delete from walk_record_likes where walk_id = #{walkId} and user_id = #{userId}")
    int deleteLike(@Param("walkId") Long walkId, @Param("userId") Long userId);

    @Insert("insert into walk_record_favorites (walk_id, user_id, created_at) values (#{walkId}, #{userId}, now())")
    int insertFavorite(@Param("walkId") Long walkId, @Param("userId") Long userId);

    @Delete("delete from walk_record_favorites where walk_id = #{walkId} and user_id = #{userId}")
    int deleteFavorite(@Param("walkId") Long walkId, @Param("userId") Long userId);

    @Delete("delete from walk_record_likes where user_id = #{userId}")
    int deleteLikesByUserId(@Param("userId") Long userId);

    @Delete("delete from walk_record_favorites where user_id = #{userId}")
    int deleteFavoritesByUserId(@Param("userId") Long userId);

    @Update("update walk_records set like_count = ifnull(like_count, 0) + 1 where id = #{walkId}")
    int incrementLikeCount(@Param("walkId") Long walkId);

    @Update("update walk_records set like_count = greatest(ifnull(like_count, 0) - 1, 0) where id = #{walkId}")
    int decrementLikeCount(@Param("walkId") Long walkId);

    @Update("update walk_records set favorite_count = ifnull(favorite_count, 0) + 1 where id = #{walkId}")
    int incrementFavoriteCount(@Param("walkId") Long walkId);

    @Update("update walk_records set favorite_count = greatest(ifnull(favorite_count, 0) - 1, 0) where id = #{walkId}")
    int decrementFavoriteCount(@Param("walkId") Long walkId);

    @Update("update walk_records set view_count = ifnull(view_count, 0) + 1 where id = #{walkId}")
    int incrementViewCount(@Param("walkId") Long walkId);

    @Update("update walk_records set view_count = ifnull(view_count, 0) + #{delta} where id = #{walkId}")
    int incrementViewCountByDelta(@Param("walkId") Long walkId, @Param("delta") long delta);

    @Select("select ifnull(like_count, 0) from walk_records where id = #{walkId}")
    Integer findLikeCount(@Param("walkId") Long walkId);

    @Select("select ifnull(favorite_count, 0) from walk_records where id = #{walkId}")
    Integer findFavoriteCount(@Param("walkId") Long walkId);

    @Update("""
            update walk_records wr
            set wr.like_count = (
                select count(1) from walk_record_likes wrl where wrl.walk_id = wr.id
            )
            """)
    int recomputeLikeCounts();

    @Update("""
            update walk_records wr
            set wr.favorite_count = (
                select count(1) from walk_record_favorites wrf where wrf.walk_id = wr.id
            )
            """)
    int recomputeFavoriteCounts();
}
