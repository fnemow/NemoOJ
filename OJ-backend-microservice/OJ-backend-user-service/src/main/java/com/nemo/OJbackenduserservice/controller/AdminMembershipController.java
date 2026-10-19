package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.annotation.AuthCheck;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackenduserservice.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/membership/admin")
public class AdminMembershipController {
    @Resource private UserService userService;
    @Resource private JdbcTemplate jdbc;

    public record Update(String tier, String validUntil, Long expectedVersion, String reason) {}

    @GetMapping
    @AuthCheck(mustRole = "admin")
    public BaseResponse<List<Map<String, Object>>> list(@RequestParam List<Long> userIds, HttpServletRequest request) {
        admin(request);
        if (userIds == null || userIds.isEmpty() || userIds.size() > 20
                || userIds.stream().anyMatch(id -> id == null || id <= 0)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        return ResultUtils.success(jdbc.query("SELECT u.id,m.tier,UNIX_TIMESTAMP(m.validUntil) AS untilEpoch,m.rowVersion,"
                        + "(m.tier='VIP' AND m.validFrom<=NOW() AND m.validUntil>NOW() AND u.userRole='user') AS vipActive "
                        + "FROM user u LEFT JOIN user_membership m ON m.userId=u.id WHERE u.isDelete=0 AND u.id IN (" + placeholders + ")",
                (rs, row) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("userId", String.valueOf(rs.getLong("id")));
                    item.put("tier", rs.getString("tier") == null ? "FREE" : rs.getString("tier"));
                    item.put("vipActive", rs.getBoolean("vipActive"));
                    Long epoch = rs.getObject("untilEpoch") == null ? null : rs.getLong("untilEpoch");
                    item.put("validUntil", epoch == null ? null : Instant.ofEpochSecond(epoch).toString());
                    item.put("rowVersion", rs.getObject("rowVersion") == null ? -1L : rs.getLong("rowVersion"));
                    return item;
                }, userIds.toArray()));
    }

    @PostMapping("/{userId}")
    @AuthCheck(mustRole = "admin")
    @Transactional
    public BaseResponse<Boolean> update(@PathVariable long userId, @RequestBody Update body, HttpServletRequest request) {
        User actor = admin(request);
        if (userId <= 0 || body == null || body.expectedVersion() == null || body.expectedVersion() < -1
                || !("VIP".equals(body.tier()) || "FREE".equals(body.tier()))
                || body.reason() == null || body.reason().isBlank() || body.reason().length() > 500) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请填写会员类型、到期时间和操作原因");
        }
        Timestamp until = null;
        if ("VIP".equals(body.tier())) {
            try {
                Instant instant = OffsetDateTime.parse(body.validUntil()).toInstant();
                if (!instant.isAfter(Instant.now()) || instant.isAfter(Instant.parse("9999-12-31T00:00:00Z"))) throw new IllegalArgumentException();
                until = Timestamp.from(instant);
            } catch (Exception e) { throw new BusinessException(ErrorCode.PARAMS_ERROR, "VIP 到期时间必须晚于当前时间，并包含时区"); }
        }
        // Lock the existing user even when no membership row exists yet.
        var roles = jdbc.queryForList("SELECT userRole FROM user WHERE id=? AND isDelete=0 FOR UPDATE", String.class, userId);
        if (roles.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        if ("admin".equals(roles.get(0)) || ("VIP".equals(body.tier()) && !"user".equals(roles.get(0)))) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "只能为未封禁的普通用户设置 VIP");
        }
        var versions = jdbc.queryForList("SELECT rowVersion FROM user_membership WHERE userId=? FOR UPDATE", Long.class, userId);
        long version = versions.isEmpty() ? -1 : versions.get(0);
        if (version != body.expectedVersion()) throw new BusinessException(ErrorCode.OPERATION_ERROR, "会员信息已被更新，请关闭窗口并重新打开后操作");
        // Convert through database epoch functions so JDBC and database time zones cannot shift validity.
        Long fromEpoch = until == null ? null : Instant.now().getEpochSecond();
        Long untilEpoch = until == null ? null : until.toInstant().getEpochSecond();
        if (versions.isEmpty()) {
            jdbc.update("INSERT INTO user_membership(userId,tier,validFrom,validUntil,updatedBy,rowVersion) VALUES(?,?,FROM_UNIXTIME(?),FROM_UNIXTIME(?),?,0)",
                    userId, body.tier(), fromEpoch, untilEpoch, String.valueOf(actor.getId()));
        } else {
            jdbc.update("UPDATE user_membership SET tier=?,validFrom=FROM_UNIXTIME(?),validUntil=FROM_UNIXTIME(?),updatedBy=?,rowVersion=rowVersion+1 WHERE userId=?",
                    body.tier(), fromEpoch, untilEpoch, String.valueOf(actor.getId()), userId);
        }
        jdbc.update("INSERT INTO ai_action_audit(actorType,actorId,action,resourceType,resourceId,outcome,detail) VALUES('ADMIN',?,?,'USER',?,'SUCCEEDED',?)",
                String.valueOf(actor.getId()), until == null ? "VIP_REVOKE" : "VIP_GRANT", String.valueOf(userId),
                body.reason().trim() + (until == null ? "" : " | validUntil=" + until.toInstant()));
        return ResultUtils.success(true);
    }

    private User admin(HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        if (user == null || !"admin".equals(user.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return user;
    }
}
