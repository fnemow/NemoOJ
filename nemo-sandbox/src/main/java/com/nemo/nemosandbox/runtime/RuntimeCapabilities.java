package com.nemo.nemosandbox.runtime;
import com.nemo.nemosandbox.CodeSandBox;
import com.nemo.nemosandbox.config.TaskConcurrencyLimiter;
import com.nemo.nemosandbox.model.*;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.*;
import java.util.*;
/** A short-lived, positively verified snapshot for this single sandbox endpoint. */
@Component
public class RuntimeCapabilities {
    private final CodeSandBox sandbox;
    private final LanguageRuntimeRegistry registry;
    private final Environment env;
    private final TaskConcurrencyLimiter limiter;
    private java.time.Clock clock=java.time.Clock.systemUTC();
    private record Verified(String fingerprint, Map<String,Object> value, long expiresAt) {}
    private final Map<String,Verified> verified=new HashMap<>();
    public RuntimeCapabilities(CodeSandBox sandbox,LanguageRuntimeRegistry registry,Environment env,TaskConcurrencyLimiter limiter) {
        this.sandbox=sandbox;this.registry=registry;this.env=env;this.limiter=limiter;
    }
    @jakarta.annotation.PostConstruct
    void warmBeforeServing() { snapshot(); }
    public static String runtimeId(LanguageRuntimeAdapter adapter) {
        try {
            byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((adapter.descriptor().getImageRef()+policyVersion(adapter)+sdkVersion(adapter)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return adapter.descriptor().getRuntimeId()+"-"+java.util.HexFormat.of().formatHex(hash);
        } catch(java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String sdkVersion(LanguageRuntimeAdapter adapter) {
        return adapter.language().equals("java") ? "java-2" : adapter.language()+"-1";
    }
    public static String policyVersion(LanguageRuntimeAdapter adapter) {
        var p=adapter.policy();
        return "multilanguage-1:"+p.getCompileTimeoutSeconds()+":"+p.getCompileMemoryBytes()+":"+p.getRuntimeFactor()+":"+p.getProcessLimit()+":"+p.getOutputLimitBytes();
    }
    public synchronized List<Map<String,Object>> snapshot() {
        long now=clock.millis();
        List<Map<String,Object>> result=new ArrayList<>();
        Set<String> enabled=new HashSet<>();
        for(String item:env.getProperty("sandbox.language.enabled","java").split(",")) {
            String language=item.trim();
            enabled.add(language);
            String fingerprint=null;
            try {
            var adapter=registry.get(language);
            var modes=Binder.get(env).bind("sandbox.language.modes."+language,Bindable.listOf(String.class)).orElse(List.of("ACM","FUNCTION"));
            fingerprint=runtimeId(adapter)+":"+modes+":"+env.getProperty("sandbox.limits.max-task-time-ms","600000");
            Verified previous=verified.get(language);
            if(previous!=null && previous.fingerprint().equals(fingerprint) && now<previous.expiresAt()) {
                result.add(previous.value());
                continue;
            }
            try (var permit=limiter.tryAcquire(TaskConcurrencyLimiter.Priority.HEALTH)) {
            if(permit==null) {
                // Busy is not a failed runtime. Reuse only a bounded, positively verified,
                // matching configuration; never perpetually extend its verification age.
                if(previous!=null && previous.fingerprint().equals(fingerprint)
                    && Boolean.TRUE.equals(previous.value().get("imageReady"))
                    && now<previous.expiresAt()+deferTtl()) {
                    var deferred=new LinkedHashMap<String,Object>(previous.value());
                    deferred.put("verificationDeferred",true);
                    result.add(Collections.unmodifiableMap(deferred));
                } else result.add(Map.of("language",language,"modes",List.of(),"imageReady",false,
                    "reason","Runtime verification deferred: sandbox busy"));
                continue;
            }
            String file,entry,source;
            switch(language) {
                case "java": file="Main.java";entry="Main";source="public class Main { public static void main(String[] args) { System.out.print(System.getProperty(\"java.version\")); } }";break;
                case "python": file="main.py";entry="main.py";source="import platform\nprint(platform.python_version(), end='')";break;
                case "cpp": file="main.cpp";entry="main";source="#include <nlohmann/json.hpp>\n#include <iostream>\nint main(){std::cout << __VERSION__;}";break;
                default: continue;
            }
            var plan=ExecutionPlan.builder().language(language).mode(ExecutionMode.ACM).entryPoint(entry)
                .sourceFiles(List.of(new SourceFile(file,source))).cases(List.of(new ExecutionCaseInput(0,"")))
                .limits(ResourceLimit.builder().timeLimitMs(2000L).memoryLimitKB(262144L).outputLimitBytes(4096L).build()).build();
            if (!modes.contains("ACM")) {
                plan.setMode(ExecutionMode.FUNCTION);
                if(language.equals("java")) {
                    plan.setEntryPoint("Runner");
                    plan.setSourceFiles(List.of(new SourceFile("Runner.java",source.replace("class Main", "class Runner")),new SourceFile("Solution.java","class Solution {}"),new SourceFile("ListNode.java","class ListNode {}"),new SourceFile("TreeNode.java","class TreeNode {}")));
                } else if(language.equals("python")) {
                    plan.setEntryPoint("runner.py");plan.setSourceFiles(List.of(new SourceFile("runner.py",source),new SourceFile("solution.py","class Solution: pass")));
                } else {
                    plan.setSourceFiles(List.of(new SourceFile("runner.cpp",source),new SourceFile("solution.hpp","class Solution {};"),new SourceFile("nodes.hpp","")));
                }
            }
            var response=sandbox.executeCodeV2(ExecuteCodeRequestV2.builder().executionPlan(plan).build());
            if(response==null || !Integer.valueOf(0).equals(response.getOverallStatus()) || response.getCases()==null || response.getCases().size()!=1)
                throw new IllegalStateException("Runtime smoke test failed: "+language);
            String version=response.getCases().get(0).getStdout().trim();
            if((language.equals("python") && !version.startsWith("3.12.")) || (language.equals("cpp") && !version.startsWith("14.")))
                throw new IllegalStateException("Unexpected toolchain version: "+language);

            Map<String,Object> capability=new LinkedHashMap<>();
            capability.put("language",language);capability.put("modes",modes);
            capability.put("runtimeId",runtimeId(adapter));capability.put("toolchainVersion",version);
            capability.put("compileTimeoutSeconds",adapter.policy().getCompileTimeoutSeconds());capability.put("maxTaskTimeMs",env.getProperty("sandbox.limits.max-task-time-ms",Long.class,600000L));
            capability.put("verifiedAt",clock.millis());capability.put("validUntil",clock.millis()+ttl(true)+deferTtl());
            capability.put("protocolVersion","2.0");capability.put("imageReady",true);
            capability.put("policyVersion",policyVersion(adapter));capability.put("sdkVersion",sdkVersion(adapter));capability.put("runtimeFactor",adapter.policy().getRuntimeFactor());capability.put("imageDigest",adapter.descriptor().getImageRef());
            var value=Collections.unmodifiableMap(capability);
            verified.put(language,new Verified(fingerprint,value,clock.millis()+ttl(true)));
            result.add(value);
            }
            } catch (RuntimeException failure) {
                Map<String,Object> value=Map.of("language",language,"modes",List.of(),"imageReady",false,"reason","Runtime smoke test unavailable");
                if(fingerprint!=null) verified.put(language,new Verified(fingerprint,value,clock.millis()+ttl(false)));
                result.add(value);
            }
        }
        verified.keySet().retainAll(enabled);
        return List.copyOf(result);
    }
    private long deferTtl() {
        return Math.max(0,Math.min(900000L,env.getProperty("sandbox.capabilities.busy-grace-ms",Long.class,600000L)));
    }
    private long ttl(boolean success) {
        long value=env.getProperty(success ? "sandbox.capabilities.success-ttl-ms" : "sandbox.capabilities.failure-ttl-ms",
                Long.class,success ? 300000L : 5000L);
        return Math.max(1000L,Math.min(value,success ? 300000L : 30000L));
    }
}
