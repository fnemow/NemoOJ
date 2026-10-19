package com.nemo.OJbackendjudgeservice.judge;
import cn.hutool.http.HttpRequest;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class RuntimeCapabilitiesClient {
    @jakarta.annotation.Resource private org.springframework.core.env.Environment environment;
    @Value("${judge.sandbox.base-url}") private String baseUrl;
    @Value("${judge.sandbox.secret}") private String secret;
    @Value("${judge.runtime-capabilities.cache-ttl-ms:30000}") private long cacheTtlMs;

    private final ExecutorService refreshExecutor=Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"runtime-capabilities-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshInProgress=new AtomicBoolean();
    private volatile List<Map<String,Object>> cached=List.of();
    private volatile long expiresAt;
    private volatile Future<?> initialRefresh;

    @jakarta.annotation.PostConstruct
    void warmCache() { initialRefresh=scheduleRefresh(); }

    // Keep bounded verification fresh during idle periods, before the next
    // formal submission takes a slot. Busy sandboxes still defer their probes.
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 5000, fixedDelay = 5000)
    void refreshExpiredCache() {
        if (System.currentTimeMillis() >= expiresAt) scheduleRefresh();
    }

    @jakarta.annotation.PreDestroy
    void close() { refreshExecutor.shutdownNow(); }

    public List<Map<String,Object>> snapshot() {
        List<Map<String,Object>> current=cached;
        if(!current.isEmpty()) {
            if(System.currentTimeMillis()>=expiresAt) scheduleRefresh();
            return current;
        }
        Future<?> warm=initialRefresh;
        if(warm==null||warm.isDone()) warm=scheduleRefresh();
        try { warm.get(125,TimeUnit.SECONDS); }
        catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Runtime capabilities interrupted",e); }
        catch(ExecutionException|TimeoutException e) { throw new IllegalStateException("Runtime capabilities unavailable",e); }
        current=cached;
        if(current.isEmpty()) throw new IllegalStateException("Runtime capabilities unavailable");
        return current;
    }

    private synchronized Future<?> scheduleRefresh() {
        if(!refreshInProgress.compareAndSet(false,true)) return initialRefresh;
        Future<?> task=refreshExecutor.submit(()->{
            try {
                List<Map<String,Object>> fresh=loadFromSandbox();
                cached=List.copyOf(fresh);
                boolean allReady = fresh.stream().allMatch(c -> Boolean.TRUE.equals(c.get("imageReady")));
                expiresAt=System.currentTimeMillis()+Math.max(1000,allReady ? cacheTtlMs : Math.min(cacheTtlMs,5000));
            } catch(RuntimeException e) {
                if(cached.isEmpty()) throw e;
                expiresAt=System.currentTimeMillis()+Math.min(Math.max(1000,cacheTtlMs),5000);
                log.warn("运行时能力刷新失败，继续使用最近一次有效快照: {}",e.getMessage());
            } finally { refreshInProgress.set(false); }
        });
        initialRefresh=task;
        return task;
    }

    private List<Map<String,Object>> loadFromSandbox() {
        try(var response=HttpRequest.get(baseUrl+"/api/v2/capabilities").header("auth",secret).setConnectionTimeout(2000).setReadTimeout(120000).execute()) {
            if(response.getStatus()!=200) throw new IllegalStateException("Runtime capabilities unavailable");
            List<Map<String,Object>> result=new Gson().fromJson(response.body(),new TypeToken<List<Map<String,Object>>>(){}.getType());
            if(result==null) throw new IllegalStateException("Missing runtime capabilities");
            var enabled=Arrays.stream(environment.getProperty("judge.language.enabled","java").split(",")).map(String::trim).toList();
            result.removeIf(c->!enabled.contains(String.valueOf(c.get("language"))));
            for(var c:result) {
                if (!(c.get("modes") instanceof List<?> modes)) throw new IllegalStateException("Invalid capability modes");
                c.put("modes",modes.stream().filter(mode->com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.modeEnabled(environment,"judge",String.valueOf(c.get("language")),"FUNCTION".equals(mode))).toList());
            }
            return result;
        }
    }
}
