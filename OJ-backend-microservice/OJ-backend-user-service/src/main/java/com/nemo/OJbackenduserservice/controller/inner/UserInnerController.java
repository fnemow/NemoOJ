package com.nemo.OJbackenduserservice.controller.inner;

import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackenduserservice.service.UserService;
import com.nemo.OJserviceclient.UserFeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.Resource;
import java.util.Collection;
import java.util.List;

/**
 * 用户内部服务
 *
 * @author Shier 2023/9/6 15:39
 */
@RestController
@RequestMapping("/inner")
public class UserInnerController implements UserFeignClient {

    @Resource
    private UserService userService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 根据 id 获取用户信息
     *
     * @param userId
     * @return
     */
    @Override
    @GetMapping("/get/id")
    public User getById(@RequestParam("userId") long userId) {
        return withoutCredential(userService.getById(userId));
    }


    /**
     * 根据 id 获取用户列表
     *
     * @param idList
     * @return
     */
    @Override
    @GetMapping("/get/ids")
    public List<User> listByIds(@RequestParam("idList") Collection<Long> idList) {
        List<User> users = userService.listByIds(idList);
        if (users != null) {
            users.forEach(this::withoutCredential);
        }
        return users;
    }

    @Override
    @GetMapping("/membership/is-vip")
    public boolean isVip(@RequestParam("userId") long userId) {
        return !jdbcTemplate.queryForList("SELECT userId FROM user_membership WHERE userId=? AND tier='VIP' "
                + "AND validFrom<=NOW() AND validUntil>NOW()", Long.class, userId).isEmpty();
    }

    /**
     * 内部接口不返回密码散列。
     * <p>
     * 调用方（题目/判题服务）只需要角色、昵称、头像等展示与鉴权字段，
     * 没有任何一处依赖 userPassword。少传一份散列，就少一处可能落到对方日志、
     * 堆转储或异常报文里的敏感数据。
     */
    private User withoutCredential(User user) {
        if (user != null) {
            user.setUserPassword(null);
        }
        return user;
    }
}
