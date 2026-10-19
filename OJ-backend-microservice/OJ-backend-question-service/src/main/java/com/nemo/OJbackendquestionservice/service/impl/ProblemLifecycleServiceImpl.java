package com.nemo.OJbackendquestionservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.exception.ThrowUtils;
import com.nemo.OJbackendmodel.model.dto.question.AdminProblemQueryRequest;
import com.nemo.OJbackendmodel.model.entity.ProblemIdentity;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.vo.AdminProblemSummaryVO;
import com.nemo.OJbackendquestionservice.mapper.ProblemIdentityMapper;
import com.nemo.OJbackendquestionservice.service.ProblemLifecycleService;
import com.nemo.OJbackendquestionservice.service.QuestionService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProblemLifecycleServiceImpl implements ProblemLifecycleService {
    public static final String DRAFT = "DRAFT";
    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";

    private final ProblemIdentityMapper identityMapper;
    private final QuestionService questionService;
    private final JdbcTemplate jdbcTemplate;

    public ProblemLifecycleServiceImpl(ProblemIdentityMapper identityMapper, QuestionService questionService,
                                       JdbcTemplate jdbcTemplate) {
        this.identityMapper = identityMapper;
        this.questionService = questionService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ProblemIdentity createIdentity(Question rootQuestion) {
        ProblemIdentity identity = new ProblemIdentity();
        identity.setQuestionId(rootQuestion.getId());
        identity.setStatus(DRAFT);
        identity.setLifecycleVersion(0L);
        ThrowUtils.throwIf(identityMapper.insert(identity) != 1, ErrorCode.OPERATION_ERROR);
        rootQuestion.setProblemNo(identity.getProblemNo());
        ThrowUtils.throwIf(!questionService.updateById(rootQuestion), ErrorCode.OPERATION_ERROR);
        return identity;
    }

    @Override
    public ProblemIdentity findByNumber(long problemNo) {
        if (problemNo <= 0) return null;
        return identityMapper.selectById(problemNo);
    }

    @Override
    public Question resolveOnline(long key) {
        if (key <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Question requested = questionService.getById(key);
        Long problemNo = requested == null ? key : requested.getProblemNo();
        ProblemIdentity identity = problemNo == null ? null : identityMapper.selectById(problemNo);
        if (identity == null || !ONLINE.equals(identity.getStatus()) || identity.getCurrentQuestionId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        Question current = questionService.getById(identity.getCurrentQuestionId());
        if (current == null || !Boolean.TRUE.equals(current.getIsPublished())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }
        return current;
    }

    @Override
    public Page<AdminProblemSummaryVO> listAdminProblems(AdminProblemQueryRequest request) {
        if (request == null || request.getCurrent() < 1 || request.getPageSize() < 1 || request.getPageSize() > 100) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        String status = normalizeStatus(request.getStatus(), true);
        StringBuilder where = new StringBuilder(" WHERE root.isDelete=0 ");
        List<Object> args = new ArrayList<>();
        if (request.getProblemNo() != null) { where.append(" AND p.problemNo=? "); args.add(request.getProblemNo()); }
        if (StringUtils.isNotBlank(request.getTitle())) {
            where.append(" AND COALESCE(cur.title,root.title) LIKE ? "); args.add("%" + request.getTitle().trim() + "%");
        }
        if (status != null) { where.append(" AND p.status=? "); args.add(status); }
        String from = " FROM question_public_number p " +
                "JOIN question root ON root.id=p.questionId " +
                "LEFT JOIN question cur ON cur.id=p.currentQuestionId ";
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*)" + from + where, Long.class, args.toArray());
        String sql = "SELECT p.problemNo,p.questionId AS rootQuestionId,p.currentQuestionId," +
                "COALESCE(cur.title,root.title) AS title,cur.problemVersion AS currentVersion,cur.rowVersion AS currentRowVersion," +
                "p.status,p.lifecycleVersion,p.offlineReason,p.updateTime," +
                "(SELECT d.id FROM question d WHERE d.problemNo=p.problemNo AND d.isPublished=0 AND d.isDelete=0 " +
                " ORDER BY d.problemVersion DESC,d.updateTime DESC LIMIT 1) AS draftId," +
                "(SELECT d.problemVersion FROM question d WHERE d.problemNo=p.problemNo AND d.isPublished=0 AND d.isDelete=0 " +
                " ORDER BY d.problemVersion DESC,d.updateTime DESC LIMIT 1) AS draftVersion" +
                from + where + " ORDER BY p.problemNo ASC LIMIT ?,?";
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add((request.getCurrent() - 1) * request.getPageSize());
        pageArgs.add(request.getPageSize());
        List<AdminProblemSummaryVO> rows = jdbcTemplate.query(sql, this::mapSummary, pageArgs.toArray());
        Page<AdminProblemSummaryVO> page = new Page<>(request.getCurrent(), request.getPageSize(), total == null ? 0 : total);
        page.setRecords(rows);
        return page;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> publish(long questionId, long expectedRowVersion, long operatorId) {
        Question question = questionService.getOne(new QueryWrapper<Question>().eq("id", questionId)
                .eq("rowVersion", expectedRowVersion).last("FOR UPDATE"));
        if (question == null) throw new BusinessException(ErrorCode.OPERATION_ERROR,
                "题目已被其他管理员修改", "EDIT_CONFLICT");
        ProblemIdentity identity = lockIdentity(question.getProblemNo());
        if (Boolean.TRUE.equals(question.getIsPublished())) {
            if (question.getId().equals(identity.getCurrentQuestionId()) && ONLINE.equals(identity.getStatus())) {
                return publishResult(question, identity);
            }
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该版本已经发布");
        }
        questionService.validQuestion(question, true);
        int nextVersion = 1;
        if (identity.getCurrentQuestionId() != null) {
            Question current = questionService.getById(identity.getCurrentQuestionId());
            if (current != null && current.getProblemVersion() != null) nextVersion = current.getProblemVersion() + 1;
        }
        question.setProblemVersion(nextVersion);
        questionService.publishQuestion(question, operatorId);
        boolean questionUpdated = questionService.update(new Question(), new UpdateWrapper<Question>()
                .eq("id", questionId).eq("rowVersion", expectedRowVersion)
                .set("problemVersion", nextVersion).set("rowVersion", expectedRowVersion + 1));
        if (!questionUpdated) throw new BusinessException(ErrorCode.OPERATION_ERROR,
                "题目已被其他管理员修改", "EDIT_CONFLICT");
        boolean identityUpdated = updateIdentity(identity, new UpdateWrapper<ProblemIdentity>()
                .set("currentQuestionId", questionId).set("status", ONLINE)
                .set("lastOperatorId", operatorId).set("offlineReason", null));
        if (!identityUpdated) throw lifecycleConflict();
        question.setRowVersion(expectedRowVersion + 1);
        question.setIsPublished(true);
        identity.setCurrentQuestionId(questionId);
        identity.setStatus(ONLINE);
        identity.setLifecycleVersion(identity.getLifecycleVersion() + 1);
        return publishResult(question, identity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long createDraft(long sourceQuestionId, long expectedRowVersion, long operatorId) {
        Question source = questionService.getOne(new QueryWrapper<Question>().eq("id", sourceQuestionId)
                .eq("rowVersion", expectedRowVersion).last("FOR UPDATE"));
        if (source == null) throw new BusinessException(ErrorCode.OPERATION_ERROR,
                "题目已被其他管理员修改", "EDIT_CONFLICT");
        if (!Boolean.TRUE.equals(source.getIsPublished())) throw new BusinessException(ErrorCode.PARAMS_ERROR,
                "只能从已发布题目创建新版本");
        ProblemIdentity identity = lockIdentity(source.getProblemNo());
        if (!source.getId().equals(identity.getCurrentQuestionId())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "只能从当前版本创建新草稿");
        }
        long drafts = questionService.count(new QueryWrapper<Question>().eq("problemNo", source.getProblemNo())
                .eq("isPublished", false));
        if (drafts > 0) throw new BusinessException(ErrorCode.OPERATION_ERROR,
                "该题已有待发布草稿", "DRAFT_EXISTS");
        Question draft = new Question();
        BeanUtils.copyProperties(source, draft);
        draft.setId(null);
        draft.setIsPublished(false);
        draft.setSourceQuestionId(source.getId());
        draft.setProblemVersion((source.getProblemVersion() == null ? 1 : source.getProblemVersion()) + 1);
        draft.setRowVersion(0L);
        draft.setSubmitNum(0);
        draft.setAcceptedNum(0);
        draft.setThumbNum(0);
        draft.setFavourNum(0);
        draft.setUserId(operatorId);
        ThrowUtils.throwIf(!questionService.save(draft), ErrorCode.OPERATION_ERROR);
        return draft.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProblemIdentity takeOffline(long problemNo, long expectedLifecycleVersion, String reason, long operatorId) {
        String cleanReason = StringUtils.trim(reason);
        if (StringUtils.isBlank(cleanReason) || cleanReason.length() > 512) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请填写不超过 512 字的下线原因");
        }
        ProblemIdentity identity = lockIdentity(problemNo);
        if (identity.getLifecycleVersion() != expectedLifecycleVersion) throw lifecycleConflict();
        if (!ONLINE.equals(identity.getStatus()) || identity.getCurrentQuestionId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目当前未上线");
        }
        if (!updateIdentity(identity, new UpdateWrapper<ProblemIdentity>().set("status", OFFLINE)
                .set("lastOperatorId", operatorId).set("offlineReason", cleanReason))) throw lifecycleConflict();
        identity.setStatus(OFFLINE);
        identity.setOfflineReason(cleanReason);
        identity.setLastOperatorId(operatorId);
        identity.setLifecycleVersion(expectedLifecycleVersion + 1);
        return identity;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ProblemIdentity putOnline(long problemNo, long expectedLifecycleVersion, long operatorId) {
        ProblemIdentity identity = lockIdentity(problemNo);
        if (identity.getLifecycleVersion() != expectedLifecycleVersion) throw lifecycleConflict();
        if (!OFFLINE.equals(identity.getStatus()) || identity.getCurrentQuestionId() == null
                || questionService.getById(identity.getCurrentQuestionId()) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "题目没有可重新上线的版本");
        }
        if (!updateIdentity(identity, new UpdateWrapper<ProblemIdentity>().set("status", ONLINE)
                .set("lastOperatorId", operatorId).set("offlineReason", null))) throw lifecycleConflict();
        identity.setStatus(ONLINE);
        identity.setOfflineReason(null);
        identity.setLastOperatorId(operatorId);
        identity.setLifecycleVersion(expectedLifecycleVersion + 1);
        return identity;
    }

    private ProblemIdentity lockIdentity(Long problemNo) {
        if (problemNo == null) throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目缺少自然题号");
        ProblemIdentity identity = identityMapper.selectOne(new QueryWrapper<ProblemIdentity>()
                .eq("problemNo", problemNo).last("FOR UPDATE"));
        ThrowUtils.throwIf(identity == null, ErrorCode.NOT_FOUND_ERROR);
        if (identity.getLifecycleVersion() == null) identity.setLifecycleVersion(0L);
        return identity;
    }

    private boolean updateIdentity(ProblemIdentity identity, UpdateWrapper<ProblemIdentity> changes) {
        changes.eq("problemNo", identity.getProblemNo()).eq("lifecycleVersion", identity.getLifecycleVersion())
                .set("lifecycleVersion", identity.getLifecycleVersion() + 1);
        return identityMapper.update(null, changes) == 1;
    }

    private BusinessException lifecycleConflict() {
        return new BusinessException(ErrorCode.OPERATION_ERROR, "题目状态已被其他管理员修改", "EDIT_CONFLICT");
    }

    private Map<String, Object> publishResult(Question question, ProblemIdentity identity) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", question.getId());
        result.put("problemNo", question.getProblemNo());
        result.put("problemVersion", question.getProblemVersion());
        result.put("published", true);
        result.put("status", identity.getStatus());
        result.put("rowVersion", question.getRowVersion());
        result.put("lifecycleVersion", identity.getLifecycleVersion());
        return result;
    }

    private String normalizeStatus(String status, boolean nullable) {
        if (StringUtils.isBlank(status)) return nullable ? null : status;
        String normalized = status.trim().toUpperCase();
        if (!List.of(DRAFT, ONLINE, OFFLINE).contains(normalized)) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        return normalized;
    }

    private AdminProblemSummaryVO mapSummary(ResultSet rs, int ignored) throws SQLException {
        AdminProblemSummaryVO vo = new AdminProblemSummaryVO();
        vo.setProblemNo(rs.getLong("problemNo"));
        vo.setRootQuestionId(rs.getLong("rootQuestionId"));
        vo.setCurrentQuestionId(nullableLong(rs, "currentQuestionId"));
        vo.setTitle(rs.getString("title"));
        vo.setCurrentVersion(nullableInt(rs, "currentVersion"));
        vo.setCurrentRowVersion(nullableLong(rs, "currentRowVersion"));
        vo.setStatus(rs.getString("status"));
        vo.setLifecycleVersion(rs.getLong("lifecycleVersion"));
        vo.setOfflineReason(rs.getString("offlineReason"));
        vo.setDraftId(nullableLong(rs, "draftId"));
        vo.setDraftVersion(nullableInt(rs, "draftVersion"));
        vo.setUpdateTime(rs.getTimestamp("updateTime"));
        return vo;
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
