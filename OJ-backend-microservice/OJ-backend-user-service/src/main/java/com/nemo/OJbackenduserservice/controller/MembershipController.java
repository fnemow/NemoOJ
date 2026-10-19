package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackenduserservice.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/membership")
public class MembershipController {
    @Resource private UserService userService;
    @Resource private JdbcTemplate jdbcTemplate;

    @GetMapping("/my")
    public BaseResponse<Map<String, Object>> mine(HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        if ("ban".equals(user.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        var rows = jdbcTemplate.query("SELECT tier,UNIX_TIMESTAMP(validFrom) AS fromEpoch,UNIX_TIMESTAMP(validUntil) AS untilEpoch FROM user_membership WHERE userId=?",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("tier", rs.getString("tier"));
                    item.put("validFrom", rs.getObject("fromEpoch") == null ? null : new Date(rs.getLong("fromEpoch") * 1000));
                    item.put("validUntil", rs.getObject("untilEpoch") == null ? null : new Date(rs.getLong("untilEpoch") * 1000));
                    return item;
                }, user.getId());
        Map<String, Object> result = rows.isEmpty()
                ? new LinkedHashMap<>(Map.of("tier", "FREE", "validFrom", "", "validUntil", ""))
                : new LinkedHashMap<>(rows.get(0));
        boolean vip = "VIP".equals(result.get("tier")) && result.get("validFrom") instanceof Date from
                && result.get("validUntil") instanceof Date until && !from.after(new Date()) && until.after(new Date());
        result.put("vipActive", vip);
        result.put("userRole", user.getUserRole());
        return ResultUtils.success(result);
    }
}
