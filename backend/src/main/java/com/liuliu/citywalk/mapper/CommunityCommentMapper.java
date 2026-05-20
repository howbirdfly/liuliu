package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.CommunityCommentEntity;
import com.liuliu.citywalk.mapper.entity.CommunityCommentQueryRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CommunityCommentMapper {

    @Select("""
            select
                c.id as id,
                c.walk_id as walkId,
                c.parent_id as parentId,
                c.user_id as userId,
                c.content as content,
                c.status as status,
                c.created_at as createdAt,
                u.nickname as authorNickname,
                u.avatar_url as authorAvatar
            from walk_record_comments c
            left join users u on u.id = c.user_id
            where c.walk_id = #{walkId}
              and c.status in ('active', 'deleted')
            order by c.created_at asc, c.id asc
            """)
    List<CommunityCommentQueryRow> findVisibleByWalkId(@Param("walkId") Long walkId);

    @Select("""
            select
                c.id as id,
                c.walk_id as walkId,
                c.parent_id as parentId,
                c.user_id as userId,
                c.content as content,
                c.status as status,
                c.created_at as createdAt,
                u.nickname as authorNickname,
                u.avatar_url as authorAvatar
            from walk_record_comments c
            left join users u on u.id = c.user_id
            where c.id = #{commentId}
              and c.status = 'active'
            limit 1
            """)
    CommunityCommentQueryRow findActiveById(@Param("commentId") Long commentId);

    @Select("""
            select
                c.id as id,
                c.walk_id as walkId,
                c.parent_id as parentId,
                c.user_id as userId,
                c.content as content,
                c.status as status,
                c.created_at as createdAt,
                u.nickname as authorNickname,
                u.avatar_url as authorAvatar
            from walk_record_comments c
            left join users u on u.id = c.user_id
            where c.id = #{commentId}
            limit 1
            """)
    CommunityCommentQueryRow findById(@Param("commentId") Long commentId);

    @Insert("""
            insert into walk_record_comments (walk_id, parent_id, user_id, content, status, created_at, updated_at)
            values (#{walkId}, #{parentId}, #{userId}, #{content}, #{status}, now(), now())
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CommunityCommentEntity entity);

    @org.apache.ibatis.annotations.Update("""
            update walk_record_comments
            set status = 'deleted',
                updated_at = now()
            where id = #{commentId}
              and status = 'active'
            """)
    int softDelete(@Param("commentId") Long commentId);
}
