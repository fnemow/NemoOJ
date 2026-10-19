package com.nemo.OJbackendquestionservice.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.constant.CommonConstant;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendcommon.exception.ThrowUtils;
import com.nemo.OJbackendcommon.utils.SqlUtils;
import com.nemo.OJbackendmodel.model.dto.question.ComparatorSpec;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.JudgeConfig;
import com.nemo.OJbackendmodel.model.dto.question.ParameterSpec;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.dto.question.QuestionQueryRequest;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.User;
import com.nemo.OJbackendmodel.model.vo.QuestionVO;
import com.nemo.OJbackendmodel.model.vo.QuestionSampleVO;
import com.nemo.OJbackendquestionservice.mapper.QuestionMapper;
import com.nemo.OJbackendquestionservice.mapper.QuestionSubmitMapper;
import com.nemo.OJbackendquestionservice.model.ProblemSubmissionStats;


import com.nemo.OJbackendquestionservice.service.QuestionService;
import com.nemo.OJserviceclient.UserFeignClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
* @author nemo
* @description 针对表【question(题目)】的数据库操作Service实现
* @createDate 2026-04-21 19:33:50
*/
@Slf4j
@Service
public class QuestionServiceImpl extends ServiceImpl<QuestionMapper, Question>
    implements QuestionService {

    @Resource
    private UserFeignClient userService;

    @Resource
    private com.nemo.OJbackendquestionservice.version.QuestionVersionService questionVersionService;

    @Resource
    private QuestionSubmitMapper questionSubmitMapper;

    @Override
    public void validQuestion(Question question, boolean add) {
        if (question == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        String title = question.getTitle();
        String content = question.getContent();
        String tags = question.getTags();

        // 1. 创建时，题目基础信息必填
        if (add) {
            ThrowUtils.throwIf(StringUtils.isAnyBlank(title, content, tags), ErrorCode.PARAMS_ERROR, "缺少必要参数");
        }

        // 2. 长度校验
        if (StringUtils.isNotBlank(title) && title.length() > 80) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "标题过长");
        }
        if (StringUtils.isNotBlank(content) && content.length() > 8192) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "内容过长");
        }

        // 3. 判题配置校验
        JudgeConfig judgeConfig = question.getJudgeConfig();
        if (judgeConfig != null) {
            Long timeLimit = judgeConfig.getTimeLimit();
            Long memoryLimit = judgeConfig.getMemoryLimit();
            Long outputLimit = judgeConfig.getOutputLimitBytes();
            if (timeLimit != null && (timeLimit <= 0 || timeLimit > 30000)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "时间限制非法(1~30000ms)");
            }
            if (memoryLimit != null && (memoryLimit <= 0 || memoryLimit > 1048576)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "内存限制非法(1~1048576KB)");
            }
            if (outputLimit != null && (outputLimit <= 0 || outputLimit > 67108864)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "输出限制非法(1~67108864 bytes)");
            }
        }
        // 4. 判题用例校验
        List<JudgeCase> judgeCaseList = question.getJudgeCase();
        if (add && (judgeCaseList == null || judgeCaseList.isEmpty())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题用例不能为空");
        }
        if (CollectionUtils.isNotEmpty(judgeCaseList)) {
            if (judgeCaseList.size() > 100) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题用例数量不能超过100");
            }
            for (JudgeCase judgeCase : judgeCaseList) {
                // Empty stdin/stdout is valid for empty collections and no-input programs.
                if (judgeCase == null || judgeCase.getInput() == null || judgeCase.getOutput() == null) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "判题用例包含空值");
                }
                if (judgeCase.getInput().length() > 65536 || judgeCase.getOutput().length() > 65536) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR, "单个用例数据过大");
                }
            }
        }

        // 5. ProblemSpec 校验（核心代码模式）
        ProblemSpec problemSpec = question.getProblemSpec();
        if (problemSpec != null) {
            if (StringUtils.isBlank(problemSpec.getClassName())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "类名不能为空");
            }
            if (!isValidJavaIdentifier(problemSpec.getClassName())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "类名不是合法的 Java 标识符: " + problemSpec.getClassName());
            }
            // 核心代码模式固定使用 Solution 类名，避免与文件名不一致导致编译失败
            if (!"Solution".equals(problemSpec.getClassName())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "核心代码模式类名必须为 Solution，当前: " + problemSpec.getClassName());
            }
            if (StringUtils.isBlank(problemSpec.getMethodName())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "方法名不能为空");
            }
            if (!isValidJavaIdentifier(problemSpec.getMethodName())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "方法名不是合法的 Java 标识符: " + problemSpec.getMethodName());
            }
            if (CollectionUtils.isEmpty(problemSpec.getParameters())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数列表不能为空");
            }
            if (problemSpec.getParameters().size() > 10) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数数量不能超过10");
            }
            if (StringUtils.isBlank(problemSpec.getReturnType())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "返回类型不能为空");
            }
            // 校验参数类型白名单 + 重名检查
            Set<String> seenParamNames = new HashSet<>();
            for (int i = 0; i < problemSpec.getParameters().size(); i++) {
                ParameterSpec p = problemSpec.getParameters().get(i);
                if (p == null || StringUtils.isBlank(p.getName())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "第" + (i + 1) + "个参数名不能为空");
                }
                if (!isValidJavaIdentifier(p.getName())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "参数名不是合法的 Java 标识符: " + p.getName());
                }
                if (!seenParamNames.add(p.getName())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "参数名重复: " + p.getName());
                }
                if (StringUtils.isBlank(p.getType())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "第" + (i + 1) + "个参数类型不能为空");
                }
                if (!isSupportedParamType(p.getType())&&!com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(p.getType())) {
                    throw new BusinessException(ErrorCode.PARAMS_ERROR,
                            "不支持的参数类型: " + p.getType());
                }
            }
            if (!isSupportedReturnType(problemSpec.getReturnType())&&!com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.isNode(problemSpec.getReturnType())) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "不支持的返回类型: " + problemSpec.getReturnType());
            }
            // 校验 resultSource 格式及参数索引越界
            try{com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract.validate(problemSpec);}catch(IllegalArgumentException invalid){throw new BusinessException(ErrorCode.PARAMS_ERROR,invalid.getMessage());}
            String resultSource = problemSpec.getResultSource();
            String returnType = problemSpec.getReturnType();
            int paramCount = problemSpec.getParameters().size();
            // FUNCTION 模式必须指定 resultSource
            if (StringUtils.isBlank(resultSource)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "核心代码模式必须指定 resultSource");
            }
            if (!isValidResultSource(resultSource, paramCount)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "resultSource 非法或参数索引越界: " + resultSource);
            }
            // void 返回类型不允许 resultSource=return（方法无返回值无法序列化）
            if ("void".equals(returnType) && "return".equals(resultSource)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR,
                        "void 返回类型不能使用 resultSource=return，请指定原地修改的参数");
            }
            // 比较器校验
            ComparatorSpec comparator = problemSpec.getComparator();
            if (comparator != null) {
                if (StringUtils.isNotBlank(comparator.getType())) {
                    if (!isValidComparatorType(comparator.getType())) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR,
                                "不支持的比较器类型: " + comparator.getType());
                    }
                }
                if ("FLOAT".equals(comparator.getType())) {
                    if (comparator.getEpsilon() == null || comparator.getEpsilon() <= 0) {
                        throw new BusinessException(ErrorCode.PARAMS_ERROR,
                                "FLOAT 比较器必须指定 epsilon > 0");
                    }
                }
            }
            if (problemSpec.getHarnessVersion() == null || problemSpec.getHarnessVersion() < 1) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "Harness 版本号非法");
            }
        }

        // 6. 已发布题目保护：以数据库中的发布状态为准（防止请求不传 isPublished 绕过）
        if (!add) {
            Question dbQuestion = this.getById(question.getId());
            if (dbQuestion != null && Boolean.TRUE.equals(dbQuestion.getIsPublished())) {
                // 不允许修改 judgeCase, judgeConfig, problemSpec, answer
                if (judgeCaseList != null || question.getJudgeConfig() != null
                        || question.getProblemSpec() != null
                        || StringUtils.isNotBlank(question.getAnswer())) {
                    throw new BusinessException(ErrorCode.OPERATION_ERROR,
                            "已发布的题目不允许修改评测配置，请创建新版本");
                }
            }
        }
    }

    // ======================== ProblemSpec 校验辅助方法 ========================

    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[a-zA-Z_$][a-zA-Z0-9_$]*");

    /** Java 保留关键字：用作类名/方法名/参数名会导致编译失败 */
    private static final Set<String> JAVA_KEYWORDS = new HashSet<>(Arrays.asList(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null"
    ));

    /** 支持的参数类型白名单（与 JavaHarnessGenerator 实际实现严格一致） */
    private static final Set<String> SUPPORTED_PARAM_TYPES = new HashSet<>(
            Arrays.asList(
                    "int", "long", "double", "boolean", "string",
                    "int[]", "long[]", "double[]", "boolean[]", "string[]",
                    "int[][]",
                    "list<int>",
                    "list_node<int>", "tree_node<int>"
            ));

    /** 返回类型白名单 = 参数类型 + void */
    private static final Set<String> SUPPORTED_RETURN_TYPES = new HashSet<>(
            Arrays.asList(
                    "int", "long", "double", "boolean", "string", "void",
                    "int[]", "long[]", "double[]", "boolean[]", "string[]",
                    "int[][]",
                    "list<int>",
                    "list_node<int>", "tree_node<int>"
            ));

    /** 合法的比较器类型（与 OutputChecker 实现一致） */
    private static final Set<String> VALID_COMPARATOR_TYPES = new HashSet<>(
            Arrays.asList("EXACT", "TEXT", "FLOAT", "UNORDERED_LIST", "MULTISET"));

    private static final Pattern RESULT_SOURCE_RETURN = Pattern.compile("return");
    private static final Pattern RESULT_SOURCE_PARAM = Pattern.compile("parameter:(\\d+)");
    private static final Pattern RESULT_SOURCE_PARAMS = Pattern.compile("parameters:\\[(\\d+),(\\d+)\\]");

    private boolean isValidJavaIdentifier(String s) {
        if (s == null || !JAVA_IDENTIFIER.matcher(s).matches()) return false;
        return !JAVA_KEYWORDS.contains(s);
    }

    private boolean isSupportedParamType(String type) {
        return type != null && SUPPORTED_PARAM_TYPES.contains(type);
    }

    private boolean isSupportedReturnType(String type) {
        return type != null && SUPPORTED_RETURN_TYPES.contains(type);
    }

    private boolean isValidComparatorType(String type) {
        return type != null && VALID_COMPARATOR_TYPES.contains(type);
    }

    private boolean isValidResultSource(String rs, int paramCount) {
        if (rs == null) return false;
        if (RESULT_SOURCE_RETURN.matcher(rs).matches()) return true;
        Matcher m1 = RESULT_SOURCE_PARAM.matcher(rs);
        if (m1.matches()) {
            int idx = Integer.parseInt(m1.group(1));
            return idx >= 0 && idx < paramCount;
        }
        Matcher m2 = RESULT_SOURCE_PARAMS.matcher(rs);
        if (m2.matches()) {
            int a = Integer.parseInt(m2.group(1));
            int b = Integer.parseInt(m2.group(2));
            return a >= 0 && a < paramCount && b >= 0 && b < paramCount;
        }
        return false;
    }

    @Override
    public QueryWrapper<Question> getQueryWrapper(QuestionQueryRequest questionQueryRequest) {
        QueryWrapper<Question> queryWrapper = new QueryWrapper<>();
        if (questionQueryRequest == null) {
            return queryWrapper;
        }
        Long id = questionQueryRequest.getId();
        Long problemNo = questionQueryRequest.getProblemNo();
        String title = questionQueryRequest.getTitle();
        String content = questionQueryRequest.getContent();
        List<String> tags = questionQueryRequest.getTags();
        Long userId = questionQueryRequest.getUserId();
        String sortField = questionQueryRequest.getSortField();
        String sortOrder = questionQueryRequest.getSortOrder();

        // 拼接查询条件
        queryWrapper.like(StringUtils.isNotBlank(title), "title", title);
        queryWrapper.like(StringUtils.isNotBlank(content), "content", content);
        if (CollectionUtils.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        queryWrapper.eq(ObjectUtils.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjectUtils.isNotEmpty(problemNo), "problemNo", problemNo);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.eq("isDelete", false);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), CommonConstant.SORT_ORDER_ASC.equals(sortOrder), sortField);
        return queryWrapper;
    }

    @Override
    public QuestionVO getQuestionVO(Question question, HttpServletRequest request) {
        // 草稿只对作者与管理员可见；其余访问者一律按"不存在"处理，
        // 不用 403——否则等于确认了该 id 上存在一道未公开的题。
        if (!Boolean.TRUE.equals(question.getIsPublished()) && !canManage(question, request)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR);
        }

        QuestionVO questionVO = QuestionVO.objToVo(question);
        applySubmissionStats(List.of(questionVO));
        questionVO.setSamples(question.getJudgeCase() == null ? List.of() : question.getJudgeCase().stream()
                .filter(c -> Boolean.FALSE.equals(c.getIsHidden()))
                .map(c -> new QuestionSampleVO(c.getInput(), c.getOutput(), null))
                .collect(Collectors.toList()));
        var functionCases=question.getProblemSpec()==null?null:question.getProblemSpec().getJudgeCases();
        questionVO.setFunctionSamples(functionCases==null?List.of():functionCases.stream()
                .filter(c->Boolean.FALSE.equals(c.getIsHidden()))
                .map(c->new QuestionSampleVO(c.getInput(),c.getOutput(),null)).collect(Collectors.toList()));
        // QuestionVO.objToVo also strips problemSpec.judgeCases, which may
        // contain hidden FUNCTION-mode inputs and expected outputs.

        // 关联用户信息
        Long userId = question.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userService.getById(userId);
        }
        questionVO.setUser(getPublicUserVO(user));
        return questionVO;
    }

    /**
     * 当前访问者是否有权管理该题目（作者本人或管理员）。
     * <p>
     * 匿名访问者返回 false，不抛异常——公开接口需要区分"匿名"与"已登录"，
     * 不能因为未登录就报错。
     */
    /**
     * 发布题目。
     * <p>
     * 顺序很重要：**先生成版本快照，再标记已发布**。
     * 反过来的话，会出现"题目已标记发布但还没有对应版本"的窗口，
     * 此时提交的请求会绑定到一个不存在的版本。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int publishQuestion(Question question, Long publisherId) {
        int version = questionVersionService.publish(question, publisherId);

        Question update = new Question();
        update.setId(question.getId());
        update.setIsPublished(true);
        update.setProblemVersion(version);
        boolean ok = this.updateById(update);
        if (!ok) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "发布失败");
        }
        log.info("题目 {} 发布完成, version={}, publisher={}", question.getId(), version, publisherId);
        return version;
    }

    @Override
    public boolean canManage(Question question, HttpServletRequest request) {
        if (question == null) {
            return false;
        }
        User loginUser = userService.getLoginUserPermitNull(request);
        if (loginUser == null || loginUser.getId() == null) {
            return false;
        }
        return loginUser.getId().equals(question.getUserId()) || userService.isAdmin(loginUser);
    }

    @Override
    public Page<QuestionVO> getQuestionVOPage(Page<Question> questionPage, HttpServletRequest request) {
        List<Question> questionList = questionPage.getRecords();
        Page<QuestionVO> questionVOPage = new Page<>(questionPage.getCurrent(), questionPage.getSize(), questionPage.getTotal());
        if (CollectionUtils.isEmpty(questionList)) {
            return questionVOPage;
        }

        // 1. 批量关联查询用户信息
        Set<Long> userIdSet = questionList.stream().map(Question::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream().collect(Collectors.groupingBy(User::getId));

        // 2. 填充 VO
        List<QuestionVO> questionVOList = questionList.stream().map(question -> {
            QuestionVO questionVO = QuestionVO.objToVo(question);

            Long userId = question.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            questionVO.setUser(getPublicUserVO(user));
            return questionVO;
        }).collect(Collectors.toList());
        applySubmissionStats(questionVOList);

        questionVOPage.setRecords(questionVOList);
        return questionVOPage;
    }

    /**
     * Read transactional counters once per page, including historical versions of
     * the same public number. No submission history scan on the request path.
     */
    private void applySubmissionStats(List<QuestionVO> questions) {
        List<Long> problemNos = questions.stream()
                .map(QuestionVO::getProblemNo)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (problemNos.isEmpty()) {
            return;
        }
        Map<Long, ProblemSubmissionStats> statsByNumber = questionSubmitMapper
                .readStatsByProblemNos(problemNos).stream()
                .collect(Collectors.toMap(ProblemSubmissionStats::getProblemNo, stats -> stats));
        for (QuestionVO question : questions) {
            ProblemSubmissionStats stats = statsByNumber.get(question.getProblemNo());
            long submissions = stats == null || stats.getSubmitNum() == null ? 0 : stats.getSubmitNum();
            long accepted = stats == null || stats.getAcceptedNum() == null ? 0 : stats.getAcceptedNum();
            question.setSubmitNum((int) Math.min(Integer.MAX_VALUE, submissions));
            question.setAcceptedNum((int) Math.min(Integer.MAX_VALUE, accepted));
        }
    }

    /**
     * 题目公开接口只展示作者资料，不公开用于登录的账号。
     */
    private com.nemo.OJbackendmodel.model.vo.UserVO getPublicUserVO(User user) {
        com.nemo.OJbackendmodel.model.vo.UserVO userVO = userService.getUserVO(user);
        if (userVO != null) {
            userVO.setUserAccount(null);
        }
        return userVO;
    }
}




