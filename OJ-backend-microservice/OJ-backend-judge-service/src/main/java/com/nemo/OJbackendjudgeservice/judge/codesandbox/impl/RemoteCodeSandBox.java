package com.nemo.OJbackendjudgeservice.judge.codesandbox.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONUtil;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBox;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequest;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponse;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeResponseV2;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 远程沙箱——通过 HTTP 调用 nemo-sandbox 服务
 * 配置通过 setter 由 CodeSandboxFactory 注入
 */
@Slf4j
public class RemoteCodeSandBox implements CodeSandBox {

    /** 由 CodeSandboxFactory 注入；不提供示例默认值，避免误用固定密钥连生产沙箱 */
    @Setter
    private String baseUrl;

    @Setter
    private String secret;

    @Setter
    private int connectTimeoutMs = 2000;

    @Setter
    private int readTimeoutMs = 15000;

    private static final String AUTH_REQUEST_HEADER = "auth";

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        String url = baseUrl + "/api/executeCode";
        String json = JSONUtil.toJsonStr(executeCodeRequest);

        try (HttpResponse httpResponse = HttpRequest.post(url)
                .header(AUTH_REQUEST_HEADER, secret)
                .body(json)
                .setConnectionTimeout(connectTimeoutMs)
                .setReadTimeout(readTimeoutMs)
                .execute()) {

            int statusCode = httpResponse.getStatus();
            String body = httpResponse.body();

            if (statusCode == 403) {
                log.error("沙箱认证失败，请检查 sandbox.secret 配置");
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱认证失败");
            }

            if (statusCode != 200) {
                log.error("沙箱返回异常状态码: {}, body: {}", statusCode, body);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "沙箱服务异常, status=" + statusCode);
            }

            if (StringUtils.isBlank(body)) {
                log.error("沙箱返回空响应");
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱返回空响应");
            }

            ExecuteCodeResponse response = JSONUtil.toBean(body, ExecuteCodeResponse.class);
            log.info("沙箱执行完成, status: {}, 输出数: {}",
                    response.getStatus(),
                    response.getOutputList() != null ? response.getOutputList().size() : 0);
            return response;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用远程沙箱失败, url={}", url, e);
            // 返回 SYSTEM_ERROR (-1)，Judge Service 识别后触发 MQ 重试
            ExecuteCodeResponse errorResponse = new ExecuteCodeResponse();
            errorResponse.setMessage("Sandbox unavailable: " + e.getMessage());
            errorResponse.setStatus(-1); // SYSTEM_ERROR
            errorResponse.setOutputList(new java.util.ArrayList<>());
            return errorResponse;
        }
    }

    @Override
    public ExecuteCodeResponseV2 executeCodeV2(ExecuteCodeRequestV2 request) {
        String url = baseUrl + "/api/v2/executeCode";
        String json = JSONUtil.toJsonStr(request);

        try (HttpResponse httpResponse = HttpRequest.post(url)
                .header(AUTH_REQUEST_HEADER, secret)
                .body(json)
                .setConnectionTimeout(connectTimeoutMs)
                .setReadTimeout(executionTimeout(request))
                .execute()) {

            int statusCode = httpResponse.getStatus();
            String body = httpResponse.body();

            if(statusCode==503)throw new com.nemo.OJbackendjudgeservice.judge.codesandbox.SandboxBusyException();

            if (statusCode == 403) {
                log.error("V2 沙箱认证失败");
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱认证失败");
            }

            if (statusCode != 200) {
                log.error("V2 沙箱返回异常状态码: {}, body: {}", statusCode, body);
                throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                        "沙箱服务异常, status=" + statusCode);
            }

            if (StringUtils.isBlank(body)) {
                log.error("V2 沙箱返回空响应");
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "沙箱返回空响应");
            }

            ExecuteCodeResponseV2 response = JSONUtil.toBean(body, ExecuteCodeResponseV2.class);
            log.info("V2 沙箱执行完成, overallStatus: {}, 用例数: {}",
                    response.getOverallStatus(),
                    response.getCases() != null ? response.getCases().size() : 0);
            return response;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("V2 调用远程沙箱失败, url={}", url, e);
            // Preserve the transport cause so the consumer can distinguish an outage
            // from a judge result and retain the durable message.
            throw new RuntimeException("Sandbox transport failure", e);
        }
    }
    private int executionTimeout(ExecuteCodeRequestV2 request) {
        var plan=request.getExecutionPlan();
        long base=plan.getLimits()!=null && plan.getLimits().getTimeLimitMs()!=null ? plan.getLimits().getTimeLimitMs() : 10000;
        long factor=3; // Maximum validated deployment policy factor; avoid early HTTP timeout.
        long cases=plan.getCases()==null ? 0 : plan.getCases().size();
        long total=Math.addExact(60000L,Math.multiplyExact(cases,Math.addExact(Math.multiplyExact(base,factor),10000L)));
        if(total>Integer.MAX_VALUE) throw new IllegalArgumentException("Execution deadline overflow");
        return (int)Math.max(readTimeoutMs,request.isBackgroundValidation()?Math.max(total,920000L):total);
    }}
