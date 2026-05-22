package com.liuliu.citywalk.mapper;

import com.liuliu.citywalk.mapper.entity.UserNotificationEntity;
import com.liuliu.citywalk.mapper.entity.UserNotificationQueryRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface UserNotificationMapper {

    @Insert("""
            insert into user_notifications (
                recipient_user_id,
                actor_user_id,
                type,
                walk_id,
                comment_id,
                is_read,
                created_at,
                updated_at
            )
            values (
                #{recipientUserId},
                #{actorUserId},
                #{type},
                #{walkId},
                #{commentId},
                #{isRead},
                now(),
                now()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(UserNotificationEntity entity);

    @Select("""
            select
                n.id as id,
                n.recipient_user_id as recipientUserId,
                n.actor_user_id as actorUserId,
                n.type as type,
                n.walk_id as walkId,
                n.comment_id as commentId,
                n.is_read as isRead,
                n.created_at as createdAt,
                u.nickname as actorNickname,
                u.avatar_url as actorAvatar,
                wr.theme_title as walkTitle,
                c.content as commentContent
            from user_notifications n
            left join users u on u.id = n.actor_user_id
            left join walk_records wr on wr.id = n.walk_id
            left join walk_record_comments c on c.id = n.comment_id
            where n.recipient_user_id = #{userId}
            order by n.created_at desc, n.id desc
            limit #{limit} offset #{offset}
            """)
    List<UserNotificationQueryRow> findByRecipientUserId(@Param("userId") Long userId,
                                                         @Param("limit") int limit,
                                                         @Param("offset") int offset);

    @Select("""
            select
                n.id as id,
                n.recipient_user_id as recipientUserId,
                n.actor_user_id as actorUserId,
                n.type as type,
                n.walk_id as walkId,
                n.comment_id as commentId,
                n.is_read as isRead,
                n.created_at as createdAt,
                u.nickname as actorNickname,
                u.avatar_url as actorAvatar,
                wr.theme_title as walkTitle,
                c.content as commentContent
            from user_notifications n
            left join users u on u.id = n.actor_user_id
            left join walk_records wr on wr.id = n.walk_id
            left join walk_record_comments c on c.id = n.comment_id
            where n.recipient_user_id = #{userId}
              and n.id > #{afterId}
            order by n.id asc
            limit #{limit}
            """)
    List<UserNotificationQueryRow> findByRecipientUserIdAfterId(@Param("userId") Long userId,
                                                                @Param("afterId") Long afterId,
                                                                @Param("limit") int limit);

    @Select("""
            select
                n.id as id,
                n.recipient_user_id as recipientUserId,
                n.actor_user_id as actorUserId,
                n.type as type,
                n.walk_id as walkId,
                n.comment_id as commentId,
                n.is_read as isRead,
                n.created_at as createdAt,
                u.nickname as actorNickname,
                u.avatar_url as actorAvatar,
                wr.theme_title as walkTitle,
                c.content as commentContent
            from user_notifications n
            left join users u on u.id = n.actor_user_id
            left join walk_records wr on wr.id = n.walk_id
            left join walk_record_comments c on c.id = n.comment_id
            where n.id = #{notificationId}
            limit 1
            """)
    UserNotificationQueryRow findById(@Param("notificationId") Long notificationId);

    @Select("""
            select count(1)
            from user_notifications
            where recipient_user_id = #{userId}
              and is_read = 0
            """)
    Integer countUnreadByRecipientUserId(@Param("userId") Long userId);

    @Update("""
            update user_notifications
            set is_read = 1,
                updated_at = now()
            where id = #{notificationId}
              and recipient_user_id = #{userId}
              and is_read = 0
            """)
    int markRead(@Param("notificationId") Long notificationId, @Param("userId") Long userId);

    @Update("""
            update user_notifications
            set is_read = 1,
                updated_at = now()
            where recipient_user_id = #{userId}
              and is_read = 0
            """)
    int markAllRead(@Param("userId") Long userId);
}
