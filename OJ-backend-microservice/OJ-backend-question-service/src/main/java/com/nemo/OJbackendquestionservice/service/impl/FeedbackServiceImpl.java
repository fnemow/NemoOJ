package com.nemo.OJbackendquestionservice.service.impl;

import com.nemo.OJbackendquestionservice.budget.AiDailyBudget;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.exception.ThrowUtils;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackAddRequest;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackHandleRequest;
import com.nemo.OJbackendmodel.model.dto.feedback.FeedbackQueryRequest;
import com.nemo.OJbackendmodel.model.entity.Feedback;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.vo.FeedbackVO;
import com.nemo.OJbackendquestionservice.mapper.FeedbackMapper;
import com.nemo.OJbackendquestionservice.service.FeedbackService;
import com.nemo.OJbackendquestionservice.service.ProblemLifecycleService;
import com.nemo.OJserviceclient.UserFeignClient;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FeedbackServiceImpl extends ServiceImpl<FeedbackMapper, Feedback> implements FeedbackService {
    private static final Set<String> TYPES = Set.of("PROBLEM", "BUG", "SUGGESTION", "OTHER");
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "RESOLVED");

    private final ProblemLifecycleService lifecycleService;
    private final UserFeignClient userFeignClient;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired private com.nemo.OJbackendquestionservice.service.AiAgentAvailability agentAvailability;
    @Value("${ai.feedback.enabled:false}") private boolean feedbackAgentEnabled;
    @Value("${ai.model.enabled:false}") private boolean modelEnabled;
    @Value("${ai.model.id:}") private String modelId;
    @Value("${ai.budget.daily-usd:0}") private BigDecimal dailyBudget;
    @Value("${ai.budget.max-task-reservation-usd:0}") private BigDecimal taskReservation;
    @Value("${ai.limits.tasks-per-minute:5}") private int tasksPerMinute;

    public FeedbackServiceImpl(ProblemLifecycleService lifecycleService, UserFeignClient userFeignClient,
                               JdbcTemplate jdbc, ObjectMapper json) {
        this.lifecycleService = lifecycleService;
        this.userFeignClient = userFeignClient;
        this.jdbc=jdbc; this.json=json;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createFeedback(FeedbackAddRequest request, User user) {
        if (request == null || user == null) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        String type = normalize(request.getType());
        String title = StringUtils.trim(request.getTitle());
        String content = StringUtils.trim(request.getContent());
        if (!TYPES.contains(type) || StringUtils.isBlank(title) || title.length() > 120
                || StringUtils.isBlank(content) || content.length() > 4000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "反馈内容格式不正确");
        }
        if ("PROBLEM".equals(type) && request.getProblemNo() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目问题必须关联题号");
        }
        if (request.getProblemNo() != null && lifecycleService.findByNumber(request.getProblemNo()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "关联题号不存在");
        }
        Feedback feedback = new Feedback();
        feedback.setUserId(user.getId());
        feedback.setType(type);
        feedback.setProblemNo(request.getProblemNo());
        feedback.setTitle(title);
        feedback.setContent(content);
        feedback.setStatus("PENDING");
        feedback.setRowVersion(0L);
        ThrowUtils.throwIf(!save(feedback), ErrorCode.OPERATION_ERROR);
        enqueueAiFeedback(feedback);
        return feedback.getId();
    }

    private void enqueueAiFeedback(Feedback feedback) {
        if(!feedbackAgentEnabled || !modelEnabled || modelId==null || modelId.isBlank() || !agentAvailability.isAvailable() || dailyBudget==null
                || taskReservation==null || dailyBudget.signum()<=0 || taskReservation.signum()<=0) return;
        try {
            jdbc.queryForObject("SELECT id FROM `user` WHERE id=? AND isDelete=0 FOR UPDATE",Long.class,feedback.getUserId());
            Integer active=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND status IN ('QUEUED','RUNNING','WAITING_REVIEW')",Integer.class,feedback.getUserId());
            Integer recent=jdbc.queryForObject("SELECT COUNT(*) FROM ai_task WHERE ownerUserId=? AND createTime>=DATE_SUB(NOW(),INTERVAL 1 MINUTE)",Integer.class,feedback.getUserId());
            if((active!=null&&active>=1)||(recent!=null&&recent>=tasksPerMinute)) return;
            if(!AiDailyBudget.canReserve(jdbc,LocalDate.now(ZoneId.of("Asia/Shanghai")),taskReservation,dailyBudget)) return;
            String taskId=UUID.randomUUID().toString(), key="feedback-"+feedback.getId();
            String request=json.writeValueAsString(Map.of("rowVersion",feedback.getRowVersion()));
            jdbc.update("INSERT INTO ai_task(id,type,ownerUserId,resourceId,idempotencyKey,status,modelId,promptVersion,requestJson) VALUES (?,'FEEDBACK',?,?,?,'QUEUED',?,'feedback-faq-v1',?)",
                    taskId,feedback.getUserId(),Long.toString(feedback.getId()),key,modelId,request);
            jdbc.update("INSERT INTO ai_usage(taskId,userId,usageDate,category,state,quotaExempt,reservedCost) VALUES (?,?,?,'FEEDBACK','RESERVED',1,?)",
                    taskId,feedback.getUserId(),LocalDate.now(ZoneId.of("Asia/Shanghai")),taskReservation);
            jdbc.update("INSERT INTO ai_outbox(taskId,eventType,status) VALUES (?,'AI_TASK','PENDING')",taskId);
            jdbc.update("UPDATE feedback SET agentTaskId=? WHERE id=?",taskId,feedback.getId());
        } catch(Exception e) { log.warn("Could not enqueue optional AI feedback task, feedbackId={}",feedback.getId()); }
    }

    @Override
    public Page<FeedbackVO> listFeedback(FeedbackQueryRequest request, Long forcedUserId) {
        if (request == null || request.getCurrent() < 1 || request.getPageSize() < 1 || request.getPageSize() > 20) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String status = normalize(request.getStatus());
        String type = normalize(request.getType());
        if (StringUtils.isNotBlank(status) && !STATUSES.contains(status)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if (StringUtils.isNotBlank(type) && !TYPES.contains(type)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        QueryWrapper<Feedback> wrapper = new QueryWrapper<>();
        wrapper.eq(forcedUserId != null, "userId", forcedUserId)
                .eq(forcedUserId == null && request.getUserId() != null, "userId", request.getUserId())
                .eq(StringUtils.isNotBlank(status), "status", status)
                .eq(StringUtils.isNotBlank(type), "type", type)
                .eq(request.getProblemNo() != null, "problemNo", request.getProblemNo())
                .orderByDesc("createTime");
        Page<Feedback> page = page(new Page<>(request.getCurrent(), request.getPageSize()), wrapper);
        Page<FeedbackVO> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(toVOs(page.getRecords()));
        return result;
    }

    @Override
    public FeedbackVO getFeedback(long id, User viewer, boolean adminOnly) {
        Feedback feedback = getById(id);
        ThrowUtils.throwIf(feedback == null, ErrorCode.NOT_FOUND_ERROR);
        boolean admin = userFeignClient.isAdmin(viewer);
        if (adminOnly && !admin) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        if (!admin && !feedback.getUserId().equals(viewer.getId())) throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        return toVO(feedback, userFeignClient.getById(feedback.getUserId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FeedbackVO handleFeedback(FeedbackHandleRequest request, User admin) {
        if (request == null || request.getId() == null || request.getExpectedRowVersion() == null || admin == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String status = normalize(request.getStatus());
        String reply = StringUtils.trimToNull(request.getAdminReply());
        if (!STATUSES.contains(status) || "PENDING".equals(status)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "管理员只能标记处理中或已解决");
        }
        if (reply != null && reply.length() > 4000) throw new BusinessException(ErrorCode.PARAMS_ERROR, "回复不能超过 4000 字");
        if ("RESOLVED".equals(status) && reply == null) throw new BusinessException(ErrorCode.PARAMS_ERROR, "解决反馈时必须填写回复");
        Feedback old = getById(request.getId());
        ThrowUtils.throwIf(old == null, ErrorCode.NOT_FOUND_ERROR);
        UpdateWrapper<Feedback> update = new UpdateWrapper<Feedback>()
                .eq("id", request.getId()).eq("rowVersion", request.getExpectedRowVersion())
                .set("status", status).set("handledBy", admin.getId())
                .set("agentStatus", "TAKEN_OVER").set("replySource", "ADMIN")
                .set("rowVersion", request.getExpectedRowVersion() + 1);
        if (reply != null) update.set("adminReply", reply);
        if ("RESOLVED".equals(status)) update.set("handledAt", new Date());
        else update.set("handledAt", null);
        if (!update(update)) throw new BusinessException(ErrorCode.OPERATION_ERROR, "反馈已被其他管理员修改", "EDIT_CONFLICT");
        return getFeedback(request.getId(), admin, true);
    }

    private List<FeedbackVO> toVOs(List<Feedback> records) {
        if (records.isEmpty()) return List.of();
        Map<Long, User> users = userFeignClient.listByIds(records.stream().map(Feedback::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return records.stream().map(item -> toVO(item, users.get(item.getUserId()))).toList();
    }

    private FeedbackVO toVO(Feedback source, User user) {
        FeedbackVO vo = new FeedbackVO();
        BeanUtils.copyProperties(source, vo);
        if (user != null) {
            vo.setUserAccount(user.getUserAccount());
            vo.setUserName(user.getUserName());
        }
        return vo;
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }
}
