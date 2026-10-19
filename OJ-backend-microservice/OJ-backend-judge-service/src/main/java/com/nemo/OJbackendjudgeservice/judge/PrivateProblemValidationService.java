package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBox;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandBoxProxy;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.CodeSandboxFactory;
import com.nemo.OJbackendjudgeservice.judge.mode.AcmJudgeModeAdapter;
import com.nemo.OJbackendjudgeservice.judge.mode.FunctionJudgeModeAdapter;
import com.nemo.OJbackendjudgeservice.judge.strategy.JudgeContext;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.codesandbox.JudgeInfo;
import com.nemo.OJbackendmodel.model.dto.question.ComparatorSpec;
import com.nemo.OJbackendmodel.model.dto.question.JudgeCase;
import com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.entity.Question;
import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendmodel.model.execution.CaseExecutionResult;
import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;
import com.nemo.OJbackendmodel.model.enums.JudgeInfoMessageEnum;
import com.nemo.OJbackendmodel.model.enums.SandboxExecutionStatus;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs every proposed reference solution through the same locked-down sandbox and comparator as submissions. */
@Service
@Slf4j
public class PrivateProblemValidationService {
    public static final String PYTHON_PREFLIGHT="CANONICAL_PYTHON_PREFLIGHT_V1";
    private final CodeSandboxFactory sandboxes;
    private final RuntimeCapabilitiesClient capabilities;
    private final AcmJudgeModeAdapter acm;
    private final FunctionJudgeModeAdapter function;
    private final JudgeManager judgeManager;
    private final Environment environment;
    @Value("${judge.codesandbox.type}") private String sandboxType;
    @org.springframework.beans.factory.annotation.Autowired
    private ValidationCheckpointStore checkpoints;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private io.micrometer.core.instrument.MeterRegistry metrics;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private TrustedValidationArtifacts artifacts;
    // validate is synchronized and drains its two workers before clearing this scope.
    private String validationId;
    private String validationLeaseToken;
    private String trustedCompileScope;
    private final java.util.concurrent.ConcurrentHashMap<String,Long> passedProofs=new java.util.concurrent.ConcurrentHashMap<>();
    @Value("${AI_PRIVATE_VALIDATION_COMBINATION_CONCURRENCY:2}") private int combinationConcurrency=2;
    private final java.util.concurrent.ExecutorService validationExecutor=java.util.concurrent.Executors.newFixedThreadPool(2,r->{Thread t=new Thread(r,"private-reference");t.setDaemon(true);return t;});
    @jakarta.annotation.PreDestroy public void close(){validationExecutor.shutdownNow();}

    public PrivateProblemValidationService(CodeSandboxFactory sandboxes, RuntimeCapabilitiesClient capabilities,
            AcmJudgeModeAdapter acm, FunctionJudgeModeAdapter function, JudgeManager judgeManager, Environment environment) {
        this.sandboxes=sandboxes; this.capabilities=capabilities; this.acm=acm; this.function=function;
        this.judgeManager=judgeManager; this.environment=environment;
    }

    public synchronized Map<String,Object> validate(PrivateProblemValidationRequest request) {
        if(request!=null && request.getValidationId()!=null && !request.getValidationId().matches("[a-fA-F0-9-]{36}"))throw new BusinessException(ErrorCode.PARAMS_ERROR);
        validationId=request==null?null:request.getValidationId();
        validationLeaseToken=request==null?null:request.getValidationLeaseToken();
        try{return validateCandidate(request);}
        catch(com.nemo.OJbackendjudgeservice.judge.codesandbox.SandboxBusyException busy){
            if(metrics!=null)metrics.counter("nemo.ai.private.validation.capacity_pauses").increment();
            return Map.of("passed",false,"paused",true,"category","CAPACITY","diagnostic","等待判题资源，已完成的验证将保留");
        }finally{validationId=null;validationLeaseToken=null;trustedCompileScope=null;}
    }
    private Map<String,Object> validateCandidate(PrivateProblemValidationRequest request) {
        if(request==null || request.getQuestion()==null || request.getReferenceSolutions()==null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        Question source=request.getQuestion();
        List<JudgeCase> cases=source.getJudgeCase();
        if(cases==null || cases.size()<20 || cases.size()>100)
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"私人题必须包含 20 至 100 组判题用例");
        ProblemSpec spec=source.getProblemSpec();
        // Capture original contract before answer derivation mutates outputs and references.
        String artifactKey=artifactKey(request);
        if(artifactKey!=null){
            var cached=artifacts.load(artifactKey);
            if(metrics!=null)metrics.counter("nemo.ai.private.validation.artifacts","outcome",cached==null?"miss":"hit").increment();
            if(cached!=null){
                cached.put("validatedCombinationMillis",cached.get("combinationMillis"));
                cached.put("combinationMillis",((List<String>)cached.get("combinations")).stream().collect(java.util.stream.Collectors.toMap(k->k,k->0L)));
                cached.put("reusedCombinations",cached.get("combinations"));cached.put("answerDerivationMillis",0L);
                return cached;
            }
        }
        // Evidence is scoped to this validation only. No container or mutable result is reused.
        Map<String,ExecutionEvidence> executions=new java.util.concurrent.ConcurrentHashMap<>();
        long answerStarted=System.nanoTime();
        boolean preflight=PYTHON_PREFLIGHT.equals(request.getGenerationProtocol());
        if(request.getGenerationProtocol()!=null) {
            Map<String,Object> generation;
            if(preflight)request.setGenerationProtocol(com.nemo.OJbackendjudgeservice.judge.harness.CanonicalPlainProtocol.VERSION);
            try{generation=deriveAnswers(request,executions);}
            finally{if(preflight)request.setGenerationProtocol(PYTHON_PREFLIGHT);}
            if(!Boolean.TRUE.equals(generation.get("passed")))return generation;
        }
        long answerMillis=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-answerStarted);
        if(preflight)return Map.of("passed",true,"pythonPreflightValidated",true,"runtimeValidated",false,
            "oracleValidated",true,"combinations",List.of("python:FUNCTION","python:ORACLE"),"caseCount",cases.size(),"answerDerivationMillis",answerMillis);
        List<String> modes=spec==null ? List.of("ACM") : List.of("ACM","FUNCTION");
        Set<String> expectedKeys=new java.util.HashSet<>();
        for(String language:List.of("java","python","cpp")) for(String mode:modes) expectedKeys.add(language+":"+mode);
        if(!request.getReferenceSolutions().keySet().equals(expectedKeys))
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"缺少语言或执行模式的参考实现");
        if(source.getJudgeConfig()==null || source.getJudgeConfig().getTimeLimit()==null || source.getJudgeConfig().getMemoryLimit()==null)
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"缺少有效资源限制");

        List<Map<String,Object>> runtimeList=capabilities.snapshot();
        List<String> passed=new ArrayList<>();
        List<String> reused=new ArrayList<>();Map<String,Long> timings=new LinkedHashMap<>();
        List<String[]> combinations=new ArrayList<>();for(String language:List.of("java","python","cpp"))for(String mode:request.getGenerationProtocol()==null?modes:List.of("FUNCTION","ACM"))combinations.add(new String[]{language,mode});
        int width=Math.max(1,Math.min(2,combinationConcurrency));
        for(int offset=0;offset<combinations.size();offset+=width) {
            List<java.util.concurrent.CompletableFuture<Combination>> futures=new ArrayList<>();
            for(var pair:combinations.subList(offset,Math.min(combinations.size(),offset+width)))futures.add(java.util.concurrent.CompletableFuture.supplyAsync(()->validateCombination(source,pair[0],pair[1],request.getReferenceSolutions().get(pair[0]+":"+pair[1]),runtimeList,spec,executions),validationExecutor));
            // Always wait for every already-started combination, including on failure.
            try{java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture<?>[0])).join();}
            catch(java.util.concurrent.CompletionException e){if(e.getCause() instanceof RuntimeException failure)throw failure;throw new IllegalStateException(e.getCause());}
            Combination failure=null;
            for(var future:futures){var item=future.join();timings.put(item.key(),item.elapsedMillis());if(item.failure()!=null){if(failure==null)failure=item;}else{passed.add(item.key());if(item.reused())reused.add(item.key());}}
            if(failure!=null)return Map.of("passed",false,"category",failure.failure().category,"combination",failure.key(),"diagnostic",safeDiagnostic(failure.failure().getMessage()),"combinations",passed,"runtimeValidated",false,"combinationMillis",timings);
        }
        Map<String,Object> result=new LinkedHashMap<>(); result.put("passed",true); result.put("caseCount",cases.size());
        result.put("combinations",passed); result.put("runtimeValidated",true);
        result.put("reusedCombinations",reused);result.put("combinationMillis",timings);
        if(request.getGenerationProtocol()!=null){
            if(request.getTemplateId()!=null){result.put("templateId",request.getTemplateId());result.put("templateVersion",request.getTemplateVersion());result.put("propertiesValidated",true);}
            result.put("answerDerivationMillis",answerMillis);
            result.put("pythonEvidenceReused",executions.values().stream().anyMatch(e->e.comparisons().get()>0));
            result.put("oracleValidated",true);
            result.put("answerProtocol",request.getGenerationProtocol());
            result.put("functionCases",spec.getJudgeCases());result.put("acmCases",source.getJudgeCase());
            result.put("referenceSolutions",request.getReferenceSolutions());
        }
        if(artifactKey!=null){artifacts.save(artifactKey,result);if(metrics!=null)metrics.counter("nemo.ai.private.validation.artifacts","outcome","validated").increment();}
        return result;
    }

    private String artifactKey(PrivateProblemValidationRequest request){
        if(artifacts==null||!artifacts.accepts(request))return null;
        try{
            var runtimes=new java.util.TreeMap<String,Object>();var plans=new java.util.TreeMap<String,Object>();
            var snapshot=capabilities.snapshot();Question source=request.getQuestion();ProblemSpec spec=source.getProblemSpec();
            for(String language:List.of("java","python","cpp")){
                var runtime=snapshot.stream().filter(r->language.equals(r.get("language"))&&Boolean.TRUE.equals(r.get("imageReady"))&&r.get("modes") instanceof List<?> modes&&modes.containsAll(List.of("FUNCTION","ACM"))).findFirst().orElseThrow();
                if(!String.valueOf(runtime.get("imageDigest")).matches(".*@sha256:[a-fA-F0-9]{64}$"))return null;
                var stable=new java.util.TreeMap<>(runtime);for(String k:List.of("verifiedAt","validUntil","verificationDeferred"))stable.remove(k);runtimes.put(language,stable);
                for(String mode:List.of("FUNCTION","ACM")){
                    if(!com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.modeEnabled(environment,"judge",language,"FUNCTION".equals(mode)))return null;
                    String core=request.getReferenceSolutions().get(language+":FUNCTION");
                    plans.put(language+":"+mode,artifactPlan(source,spec,language,mode,"FUNCTION".equals(mode)?core:com.nemo.OJbackendjudgeservice.judge.harness.CanonicalPlainProtocol.wrap(language,core,spec),String.valueOf(runtime.get("runtimeId"))));
                }
            }
            var python=(Map<?,?>)runtimes.get("python");plans.put("python:ORACLE",artifactPlan(source,spec,"python","FUNCTION",request.getOracleSolution(),String.valueOf(python.get("runtimeId"))));
            return TrustedValidationArtifacts.hash(Map.of("revision",TrustedValidationArtifacts.REVISION,"contract",TrustedValidationArtifacts.contractHash(request),"runtimes",runtimes,"plans",plans));
        }catch(Exception incompatible){return null;}
    }
    private ExecutionPlan artifactPlan(Question source,ProblemSpec spec,String language,String mode,String code,String runtime){
        Question q=new Question();BeanUtils.copyProperties(source,q);q.setProblemSpec("FUNCTION".equals(mode)?spec:null);
        if("FUNCTION".equals(mode))q.setJudgeCase(spec.getJudgeCases());
        QuestionSubmit submit=new QuestionSubmit();submit.setLanguage(language);submit.setCode(code);submit.setExecutionMode(mode);submit.setHarnessVersion("FUNCTION".equals(mode)?spec.getHarnessVersion():null);
        var plan=("FUNCTION".equals(mode)?function:acm).buildExecutionPlan(q,submit);plan.setRuntimeId(runtime);plan.setCases(ValidationCaseOrder.prioritize(plan.getCases()));return plan;
    }

    private Map<String,Object> deriveAnswers(PrivateProblemValidationRequest request,Map<String,ExecutionEvidence> executions) {
        if(!com.nemo.OJbackendjudgeservice.judge.harness.CanonicalPlainProtocol.VERSION.equals(request.getGenerationProtocol()))throw new IllegalArgumentException("unknown generation protocol");
        var q=request.getQuestion();var spec=q.getProblemSpec();var refs=request.getReferenceSolutions();
        if(spec==null||request.getOracleSolution()==null||request.getOracleSolution().isBlank()||request.getOracleSolution().length()>24000
                ||!refs.keySet().equals(Set.of("java:FUNCTION","python:FUNCTION","cpp:FUNCTION"))
                ||request.getOracleSolution().strip().equals(refs.get("python:FUNCTION").strip()))throw new IllegalArgumentException("independent oracle and three function references required");
        if(!"EXACT".equals(spec.getComparator().getType())||!"return".equals(spec.getResultSource()))throw new IllegalArgumentException("canonical generation requires exact return value");
        var runtime=capabilities.snapshot().stream().filter(r->"python".equals(r.get("language"))&&Boolean.TRUE.equals(r.get("imageReady"))&&r.get("modes") instanceof List<?> modes&&modes.contains("FUNCTION")).findFirst().orElseThrow(()->new BusinessException(ErrorCode.OPERATION_ERROR,"Python 验证环境不可用"));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            if(request.getTemplateId()!=null){
                TrustedTemplateProperties.signature(request.getTemplateId(),request.getTemplateVersion(),spec);
                trustedCompileScope=request.getTemplateVersion()+":"+request.getTemplateId();
            }
            // Canonical inputs are checked before any program runs. Outputs from the model are ignored.
            if(spec.getJudgeCases().size()!=20||q.getJudgeCase().size()!=20)throw new IllegalArgumentException("twenty canonical cases required");
            for(int i=0;i<20;i++){
                String stdin=com.nemo.OJbackendjudgeservice.judge.harness.CanonicalPlainProtocol.encodeInput(mapper.readTree(spec.getJudgeCases().get(i).getInput()),spec);
                if(!stdin.equals(q.getJudgeCase().get(i).getInput()))throw new IllegalArgumentException("ACM and FUNCTION inputs differ");
                if(request.getTemplateId()!=null)TrustedTemplateProperties.expected(request.getTemplateId(),mapper.readTree(spec.getJudgeCases().get(i).getInput()));
            }
            ProgramEvidence oracleEvidence,optimizedEvidence;
            if(combinationConcurrency>=2){
                var oracleFuture=java.util.concurrent.CompletableFuture.supplyAsync(()->runPython(q,request.getOracleSolution(),runtime,spec,executions),validationExecutor);
                var optimizedFuture=java.util.concurrent.CompletableFuture.supplyAsync(()->runPython(q,refs.get("python:FUNCTION"),runtime,spec,executions),validationExecutor);
                // Drain both sandbox requests on all paths, including infrastructure failure.
                try{java.util.concurrent.CompletableFuture.allOf(oracleFuture,optimizedFuture).join();}
                catch(java.util.concurrent.CompletionException e){if(e.getCause() instanceof RuntimeException failure)throw failure;throw new IllegalStateException(e.getCause());}
                oracleEvidence=oracleFuture.join();optimizedEvidence=optimizedFuture.join();
            }else{
                oracleEvidence=runPython(q,request.getOracleSolution(),runtime,spec,executions);
                optimizedEvidence=oracleEvidence.failure()==null?runPython(q,refs.get("python:FUNCTION"),runtime,spec,executions):new ProgramEvidence(List.of(),null);
            }
            if(oracleEvidence.failure()!=null){var e=oracleEvidence.failure();return Map.of("passed",false,"category",e.category,"combination","python:ORACLE","diagnostic",safeDiagnostic(e.getMessage()));}
            if(optimizedEvidence.failure()!=null){var e=optimizedEvidence.failure();return Map.of("passed",false,"category",e.category,"combination","python:FUNCTION","diagnostic",safeDiagnostic(e.getMessage()));}
            List<String> oracle=oracleEvidence.outputs(),optimized=optimizedEvidence.outputs();
            // Do not select either implementation's result on disagreement.
            for(int i=0;i<20;i++) if(!mapper.readTree(oracle.get(i)).equals(mapper.readTree(optimized.get(i)))){
                var detail=new LinkedHashMap<String,Object>();detail.put("caseIndex",i);detail.put("input",boundedCase(spec.getJudgeCases().get(i).getInput()));detail.put("oracle",boundedCase(oracle.get(i)));detail.put("optimized",boundedCase(optimized.get(i)));
                return Map.of("passed",false,"category","ORACLE_DISAGREEMENT","combination","python:FUNCTION","diagnostic",mapper.writeValueAsString(detail));
            }
            for(int i=0;i<20;i++){
                if(request.getTemplateId()!=null && !TrustedTemplateProperties.expected(request.getTemplateId(),mapper.readTree(spec.getJudgeCases().get(i).getInput())).equals(mapper.readTree(oracle.get(i))))
                    return Map.of("passed",false,"category","PROPERTY_VIOLATION","combination","python:ORACLE","diagnostic","可信题型性质检查失败，case="+i);
                spec.getJudgeCases().get(i).setOutput(oracle.get(i));
                String output=com.nemo.OJbackendjudgeservice.judge.harness.CanonicalPlainProtocol.encode(mapper.readTree(oracle.get(i)),spec.getReturnType(),spec);
                if(output.length()>12000||oracle.get(i).length()>12000)throw new IllegalArgumentException("derived answer too large");
                q.getJudgeCase().get(i).setOutput(output);
            }
            var complete=new LinkedHashMap<>(refs);
            for(String language:List.of("java","python","cpp"))complete.put(language+":ACM",com.nemo.OJbackendjudgeservice.judge.harness.CanonicalPlainProtocol.wrap(language,refs.get(language+":FUNCTION"),spec));
            request.setReferenceSolutions(complete);
            return Map.of("passed",true);
        }catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("invalid canonical JSON",e);}
    }
    private record ProgramEvidence(List<String> outputs,ReferenceFailure failure){}
    private ProgramEvidence runPython(Question q,String code,Map<String,Object> runtime,ProblemSpec spec,Map<String,ExecutionEvidence> executions){
        try{return new ProgramEvidence(executeOne(q,"python","FUNCTION",code,String.valueOf(runtime.get("runtimeId")),spec,false,executions),null);}
        catch(ReferenceFailure e){return new ProgramEvidence(List.of(),e);}
    }
    private Combination validateCombination(Question source,String language,String mode,String code,List<Map<String,Object>> runtimeList,ProblemSpec spec,Map<String,ExecutionEvidence> executions) {
            if(!com.nemo.OJbackendmodel.model.execution.LanguageCompatibility.modeEnabled(environment,"judge",language,"FUNCTION".equals(mode)))
                throw new BusinessException(ErrorCode.OPERATION_ERROR,"运行环境未开放 "+language+" "+mode);
            Map<String,Object> runtime=runtimeList.stream().filter(item->language.equals(item.get("language"))
                    && item.get("modes") instanceof List<?> available && available.contains(mode)
                    && Boolean.TRUE.equals(item.get("imageReady"))).findFirst()
                    .orElseThrow(()->new BusinessException(ErrorCode.OPERATION_ERROR,"运行环境不可用："+language+" "+mode));
            if(code==null || code.isBlank() || code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>524288)
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"参考实现缺失或过大");
            String proofKey=proofKey(source,language,mode,code,runtime);
            long started=System.nanoTime();
            // Durable jobs must re-compare their own execution evidence on every resume.
            Long proofTime=proofKey==null || validationId!=null ? null : passedProofs.get(proofKey);
            try {
                if(proofTime!=null&&started-proofTime<java.util.concurrent.TimeUnit.MINUTES.toNanos(15))return new Combination(language+":"+mode,0,true,null);
                else {executeOne(source,language,mode,code,String.valueOf(runtime.get("runtimeId")),spec,true,executions);if(proofKey!=null){if(passedProofs.size()>=512)passedProofs.clear();passedProofs.put(proofKey,System.nanoTime());}}
            }
            catch(ReferenceFailure failure) {
                return new Combination(language+":"+mode,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),false,failure);
            }
            return new Combination(language+":"+mode,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),false,null);
    }
    private record Combination(String key,long elapsedMillis,boolean reused,ReferenceFailure failure){}

    /** Cache only immutable images; all source/spec/case/limit changes invalidate the hash. */
    static String proofKey(Question source,String language,String mode,String code,Map<String,Object> runtime) {
        String digest=String.valueOf(runtime.get("imageDigest"));
        if(!digest.matches(".*@sha256:[a-fA-F0-9]{64}$"))return null;
        try {
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            // Readiness timestamps change without changing the runtime; omit them below.
            var stable=new java.util.TreeMap<>(runtime);for(String key:List.of("verifiedAt","validUntil","verificationDeferred"))stable.remove(key);
            String canonical=mapper.writeValueAsString(source)+"\n"+language+":"+mode+"\n"+code+"\n"+mapper.writeValueAsString(stable);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(Exception invalid){return null;}
    }

    private record ExecutionEvidence(List<String> outputs,long time,long memory,com.nemo.OJbackendmodel.model.execution.ResourceLimit limits,java.util.concurrent.atomic.AtomicInteger comparisons){}
    private List<String> executeOne(Question source,String language,String mode,String code,String runtimeId,ProblemSpec originalSpec,boolean compareAnswers,Map<String,ExecutionEvidence> executions) {
        boolean isFunction="FUNCTION".equals(mode);
        ProblemSpec spec=isFunction ? originalSpec : null;
        Question question=new Question(); BeanUtils.copyProperties(source,question);
        question.setProblemSpec(spec);
        if(isFunction && spec.getJudgeCases()!=null && !spec.getJudgeCases().isEmpty()) question.setJudgeCase(spec.getJudgeCases());
        QuestionSubmit submit=new QuestionSubmit(); submit.setLanguage(language); submit.setCode(code); submit.setExecutionMode(mode);
        submit.setHarnessVersion(isFunction ? spec.getHarnessVersion() : null);
        var adapter=isFunction ? function : acm;
        ExecutionPlan plan=adapter.buildExecutionPlan(question,submit);
        plan.setRuntimeId(runtimeId);
        plan.setCases(ValidationCaseOrder.prioritize(plan.getCases()));
        // Actual files include the harness/SDK; the plan binds language, mode, runtime,
        // entry point, every input and all limits. Expected answers are deliberately absent.
        String executionKey;
        String digest=capabilities.snapshot().stream().filter(r->runtimeId.equals(r.get("runtimeId"))).map(r->String.valueOf(r.get("imageDigest"))).findFirst().orElse("");
        boolean immutable=digest.matches(".*@sha256:[a-fA-F0-9]{64}$");
        try{executionKey=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(("checkpoint-v1\n"+digest+"\n"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plan)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}
        ExecutionEvidence previous=immutable?executions.get(executionKey):null;
        if(previous==null && immutable && checkpoints!=null){
            var stored=checkpoints.load(validationId,executionKey);
            if(stored!=null && stored.outputs()!=null && stored.outputs().size()==plan.getCases().size()){
                previous=new ExecutionEvidence(List.copyOf(stored.outputs()),stored.time(),stored.memory(),stored.limits(),new java.util.concurrent.atomic.AtomicInteger());
                if(metrics!=null)metrics.counter("nemo.ai.private.validation.checkpoint_restores","language",language,"mode",mode).increment();
                log.info("Private validation checkpoint restored, validationId={}, language={}, mode={}, cases={}",validationId,language,mode,stored.outputs().size());
            }
        }
        if(previous!=null){
            if(compareAnswers){compareEvidence(question,submit,plan,previous,isFunction);previous.comparisons().incrementAndGet();}
            executions.put(executionKey,previous);
            return previous.outputs();
        }
        CodeSandBox sandbox=new CodeSandBoxProxy(sandboxes.newInstance(sandboxType));
        var response=sandbox.executeCodeV2(ExecuteCodeRequestV2.builder().submissionId(0L).backgroundValidation(true).trustedCompileScope(trustedCompileScope).executionPlan(plan).build());
        if(response==null || response.getOverallStatus()==null || response.getOverallStatus()!=0
                || response.getSystemMessage()!=null || response.getCompileMessage()!=null
                || response.getCases()==null || response.getCases().size()!=question.getJudgeCase().size()) {
            CaseExecutionResult firstFailure=response==null||response.getCases()==null?null:response.getCases().stream()
                    .filter(item->item!=null && (item.getStatus()==null || item.getStatus()!=SandboxExecutionStatus.SUCCESS))
                    .findFirst().orElse(null);
            log.warn("Private reference sandbox failed: language={}, mode={}, overall={}, cases={}, firstCase={}, firstStatus={}, firstExit={}, firstStderr={}, compile={}, system={}",
                    language,mode,response==null?null:response.getOverallStatus(),
                    response==null||response.getCases()==null?null:response.getCases().size(),
                    firstFailure==null?null:firstFailure.getCaseIndex(),
                    firstFailure==null?null:firstFailure.getStatus(),
                    firstFailure==null?null:firstFailure.getExitCode(),
                    safeDiagnostic(firstFailure==null?null:firstFailure.getStderr()),
                    safeDiagnostic(response==null?null:response.getCompileMessage()),
                    safeDiagnostic(response==null?null:response.getSystemMessage()));
            if(response==null||response.getSystemMessage()!=null)throw new BusinessException(ErrorCode.OPERATION_ERROR,"验证基础设施暂不可用");
            String category=response.getCompileMessage()!=null?"COMPILE":"RUNTIME";
            throw new ReferenceFailure(category,"参考实现 "+language+" "+mode+": "+safeDiagnostic(response.getCompileMessage())+" "+(firstFailure==null?"":firstFailure.getStatus()+" case="+firstFailure.getCaseIndex()+" "+safeDiagnostic(firstFailure.getStderr())));
        }
        response.getCases().sort(java.util.Comparator.comparing(CaseExecutionResult::getCaseIndex));
        List<String> outputs=new ArrayList<>(); long time=0,memory=0;
        for(int i=0;i<response.getCases().size();i++) {
            CaseExecutionResult item=response.getCases().get(i);
            if(item==null || item.getStatus()==null || item.getStatus()!=SandboxExecutionStatus.SUCCESS)
                throw new ReferenceFailure("RUNTIME","参考实现未通过用例："+language+" "+mode+" case="+i+" "+(item==null?"missing":item.getStatus()+" "+safeDiagnostic(item.getStderr())));
            String stdout=item.getStdout()==null?"":item.getStdout();
            if(isFunction) {
                String actual=extractFunctionActual(stdout,spec);
                if(actual==null) throw new ReferenceFailure("PROTOCOL","核心代码参考实现输出格式错误，case="+i);
                outputs.add(actual);
            } else outputs.add(stdout);
            if(item.getTime()!=null) time=Math.max(time,item.getTime());
            if(item.getMemory()!=null) memory=Math.max(memory,item.getMemory());
        }
        var limits=response.getEffectiveLimits()==null?null:new com.nemo.OJbackendmodel.model.execution.ResourceLimit();
        if(limits!=null)BeanUtils.copyProperties(response.getEffectiveLimits(),limits);
        ExecutionEvidence evidence=new ExecutionEvidence(List.copyOf(outputs),time,memory,limits,new java.util.concurrent.atomic.AtomicInteger());
        if(immutable){
            executions.put(executionKey,evidence);
            if(checkpoints!=null)checkpoints.save(validationId,executionKey,new ValidationCheckpointStore.Evidence(evidence.outputs(),time,memory,limits),validationLeaseToken);
        }
        if(!compareAnswers){if(immutable)executions.put(executionKey,evidence);return evidence.outputs();}
        compareEvidence(question,submit,plan,evidence,isFunction);
        return evidence.outputs();
    }

    private void compareEvidence(Question question,QuestionSubmit submit,ExecutionPlan plan,ExecutionEvidence collected,boolean isFunction){
        String language=submit.getLanguage(),mode=submit.getExecutionMode();ProblemSpec spec=question.getProblemSpec();
        var outputs=collected.outputs();long time=collected.time(),memory=collected.memory();
        JudgeInfo evidence=new JudgeInfo(); evidence.setExecutedTime(time); evidence.setExecutedMemory(memory);
        JudgeContext context=new JudgeContext(); context.setJudgeInfo(evidence); context.setQuestion(question); context.setQuestionSubmit(submit);
        context.setJudgeCaseList(question.getJudgeCase()); context.setInputList(plan.getCases().stream().sorted(java.util.Comparator.comparing(c->c.getCaseIndex())).map(c->c.getStdin()).toList());
        context.setOutputList(outputs); context.setEffectiveLimits(collected.limits());
        ComparatorSpec comparator=isFunction && spec.getComparator()!=null ? spec.getComparator() : new ComparatorSpec("TEXT",null);
        JudgeInfo judged=judgeManager.doJudgeV2(context,comparator);
        if(!JudgeInfoMessageEnum.ACCEPTED.getValue().equals(judged.getMessage()))
            throw new ReferenceFailure("WRONG_ANSWER","参考实现未通过："+language+" "+mode+" verdict="+judged.getMessage()
                    + mismatchDiagnostic(context,comparator)+"；核对原规格并修复实现，不得迁就实现修改预期结果");
    }

    /** Internal signed response only; never attach hidden-case evidence to public submissions. */
    String mismatchDiagnostic(JudgeContext context,ComparatorSpec comparator) {
        var cases=context.getJudgeCaseList();var outputs=context.getOutputList();
        for(int i=0;i<cases.size();i++) {
            JudgeContext single=new JudgeContext();BeanUtils.copyProperties(context,single);
            single.setJudgeCaseList(List.of(cases.get(i)));single.setOutputList(List.of(outputs.get(i)));
            single.setInputList(List.of(context.getInputList().get(i)));
            if(JudgeInfoMessageEnum.WRONG_ANSWER.getValue().equals(judgeManager.doJudgeV2(single,comparator).getMessage())) {
                var details=new LinkedHashMap<String,Object>();details.put("caseIndex",i);details.put("input",boundedCase(cases.get(i).getInput()));
                details.put("expected",boundedCase(cases.get(i).getOutput()));details.put("actual",boundedCase(outputs.get(i)));
                try{return " details="+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(details);}
                catch(Exception impossible){throw new IllegalStateException(impossible);}
            }
        }
        return "";
    }
    private String boundedCase(String text){return text==null?"":text.substring(0,Math.min(text.length(),240));}

    private static class ReferenceFailure extends RuntimeException {
        final String category;
        ReferenceFailure(String category,String diagnostic){super(diagnostic);this.category=category;}
    }

    private String safeDiagnostic(String value) {
        if(value==null)return "";
        String bounded=value.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ");
        return bounded.substring(0,Math.min(bounded.length(),1200));
    }

    private String extractFunctionActual(String stdout,ProblemSpec spec) {
        try {
            com.fasterxml.jackson.databind.JsonNode root=new com.fasterxml.jackson.databind.ObjectMapper().readTree(stdout);
            if(!"OK".equals(root.path("status").asText())) return null;
            String actual=root.path("actual").toString();
            return com.nemo.OJbackendjudgeservice.judge.harness.FunctionResultValidator.valid(spec,actual)?actual:null;
        } catch(Exception e) { return null; }
    }
}
