package com.nemo.OJbackenduserservice.controller;

import com.nemo.OJbackendcommon.annotation.AuthCheck;
import com.nemo.OJbackendcommon.common.BaseResponse;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.common.ResultUtils;
import com.nemo.OJbackendcommon.constant.UserConstant;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackenduserservice.service.RegistrationInvitationService;
import com.nemo.OJbackenduserservice.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/invitations/admin")
public class RegistrationInvitationController {
    private final RegistrationInvitationService invitations;
    private final UserService users;

    public RegistrationInvitationController(RegistrationInvitationService invitations, UserService users) {
        this.invitations = invitations;
        this.users = users;
    }

    public record CreateRequest(int validityHours, String note) {}

    private long admin(HttpServletRequest request) {
        var user = users.getLoginUser(request);
        if (!UserConstant.ADMIN_ROLE.equals(user.getUserRole())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return user.getId();
    }

    @GetMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RegistrationInvitationService.InvitationPage> list(
            @RequestParam(defaultValue = "1") int current, @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        admin(request);
        return ResultUtils.success(invitations.list(current, pageSize));
    }

    @PostMapping
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<RegistrationInvitationService.CreatedInvitation> create(
            @RequestBody CreateRequest body, HttpServletRequest request) {
        long adminId = admin(request);
        if (body == null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(invitations.create(adminId, body.validityHours(), body.note()));
    }

    @PostMapping("/{id}/revoke")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> revoke(@PathVariable long id, HttpServletRequest request) {
        admin(request);
        return ResultUtils.success(invitations.revoke(id));
    }
}
