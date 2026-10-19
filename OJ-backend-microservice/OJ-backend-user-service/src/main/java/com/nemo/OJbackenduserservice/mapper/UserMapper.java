package com.nemo.OJbackenduserservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nemo.OJbackendmodel.model.entity.User;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


/**
 * 用户数据库操作
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
public interface UserMapper extends BaseMapper<User> {

    /**
     * 统计账号占用量，**包含已软删除的记录**。
     * <p>
     * 为什么不能用 BaseMapper 的 selectCount：实体上的 {@code @TableLogic} 会自动追加
     * {@code isDelete = 0}，导致软删除账号被漏掉，应用层于是放行注册，
     * 而数据库唯一约束 uk_user_userAccount 会拒绝——两层判断不一致。
     * 数据库约束不区分 isDelete，这里的口径必须与它一致：账号一经使用即永久占用。
     * <p>
     * 该 SQL 手写，不经过 MyBatis-Plus 的逻辑删除拦截。
     */
    @Select("SELECT COUNT(*) FROM `user` WHERE userAccount = #{userAccount}")
    long countByAccountIncludingDeleted(@Param("userAccount") String userAccount);
}




