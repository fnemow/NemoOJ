package com.nemo.OJserviceclient;

import com.nemo.OJbackendcommon.observability.RequestIdFilter;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 跨服务透传 requestId。
 * <p>
 * 没有它，一次用户请求在多个服务里会各自生成 requestId，
 * 日志无法串联，"这条提交卡在哪一步"就只能靠时间戳猜。
 * <p>
 * 仅透传当前线程 MDC 中已有的值（由 {@link RequestIdFilter} 做了字符与长度校验）；
 * 没有则不带，由被调用方自行生成。
 */
@Component
public class RequestIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null && !requestId.isEmpty()) {
            template.header(RequestIdFilter.HEADER, requestId);
        }
    }
}
