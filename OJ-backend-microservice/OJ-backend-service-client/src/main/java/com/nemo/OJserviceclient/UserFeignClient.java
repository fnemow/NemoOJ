package com.nemo.OJserviceclient;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.IService;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.security.BrowserSessionSupport;
import com.nemo.OJbackendmodel.model.dto.user.UserQueryRequest;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.enums.UserRoleEnum;
import com.nemo.OJbackendmodel.model.vo.LoginUserVO;
import com.nemo.OJbackendmodel.model.vo.UserVO;
import org.springframework.beans.BeanUtils;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;


import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.List;

import static com.nemo.OJbackendcommon.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务
 *
 * @author <a href="https://github.com/liyupi">程序员鱼皮</a>
 * @from <a href="https://yupi.icu">编程导航知识星球</a>
 */
@FeignClient(name = "OJ-backend-user-service", path = "/api/user/inner")
public interface UserFeignClient{

    /**
     * 根据 id 获取用户信息
     *
     * @param userId
     * @return
     */
    @GetMapping("/get/id")
    User getById(@RequestParam("userId") long userId);

    /**
     * 根据 id 获取用户列表
     *
     * @param idList
     * @return
     */
    @GetMapping("/get/ids")
    List<User> listByIds(@RequestParam("idList") Collection<Long> idList);

    @GetMapping("/membership/is-vip")
    boolean isVip(@RequestParam("userId") long userId);

    /**
     * 获取当前登录用户
     *
     * @param request
     * @return
     */
    default User getLoginUser(HttpServletRequest request) {
        // 先判断是否已登录
        Object userObj = BrowserSessionSupport.loginState(request);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取当前登录用户，未登录返回 null（不抛异常）。
     * <p>
     * 公开接口需要区分"匿名访问"与"已登录访问"，但又不能因为未登录就报错，
     * 因此需要这个方法。
     */
    default User getLoginUserPermitNull(HttpServletRequest request) {
        Object userObj = BrowserSessionSupport.loginState(request);
        return (User) userObj;
    }

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    default boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    /**
     * 获取脱敏的用户信息
     *
     * @param user
     * @return
     */
    default UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

}
