package com.nemo.OJbackendjudgeservice.judge;

import com.fasterxml.jackson.databind.*;
import com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.nio.file.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only release-authored contracts may reuse complete proof. No model/user-created allowlist. */
@Component
public class TrustedValidationArtifacts {
    static final String REVISION="complete-proof-v1";
    private final ObjectMapper json=new ObjectMapper();
    private final Set<String> approved;
    private final Map<String,String> descriptions;
    @Value("${AI_TRUSTED_VALIDATION_CACHE_ENABLED:true}") private boolean enabled;
    @Value("${AI_TRUSTED_VALIDATION_CACHE_PATH:/tmp/nemo-trusted-validation}") private String directory;
    public TrustedValidationArtifacts(){
        Set<String> hashes=new HashSet<>();
        Map<String,String> texts=new HashMap<>();
        try(var stream=getClass().getResourceAsStream("/trusted-validation-contracts.json")){
            if(stream!=null){JsonNode registry=json.readTree(stream);for(JsonNode hash:registry.path("contracts"))hashes.add(hash.asText());registry.path("contentHashes").fields().forEachRemaining(e->texts.put(e.getKey(),e.getValue().asText()));}
        }catch(Exception invalid){throw new IllegalStateException("Invalid audited validation registry",invalid);}
        approved=Set.copyOf(hashes);
        descriptions=Map.copyOf(texts);
    }
    public boolean accepts(PrivateProblemValidationRequest r){
        if(!enabled)return false;String identity=contractHash(r);
        return approved.contains(identity)&&r.getQuestion().getContent()!=null&&hash(r.getQuestion().getContent()).equals(descriptions.get(identity));
    }
    static Object normalize(JsonNode n){
        if(n.isObject()){var m=new TreeMap<String,Object>();n.fields().forEachRemaining(e->{if(!e.getValue().isNull())m.put(e.getKey(),normalize(e.getValue()));});return m;}
        if(n.isArray()){var a=new ArrayList<Object>();n.forEach(v->a.add(normalize(v)));return a;}
        if(n.isNull())return null;if(n.isIntegralNumber())return n.longValue();if(n.isNumber())return n.doubleValue();if(n.isBoolean())return n.booleanValue();return n.asText();
    }
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(normalize(new ObjectMapper().valueToTree(value)))));}catch(Exception invalid){throw new IllegalArgumentException("Invalid artifact identity",invalid);}}
    static String contractHash(PrivateProblemValidationRequest r){
        if(r==null||r.getQuestion()==null||!"CANONICAL_LINES_V1".equals(r.getGenerationProtocol()))return "";
        var q=r.getQuestion();var value=new LinkedHashMap<String,Object>();
        value.put("judgeConfig",q.getJudgeConfig());value.put("judgeCases",q.getJudgeCase());value.put("problemSpec",q.getProblemSpec());
        value.put("referenceSolutions",r.getReferenceSolutions());value.put("protocol",r.getGenerationProtocol());value.put("oracle",r.getOracleSolution());
        value.put("templateId",r.getTemplateId());value.put("templateVersion",r.getTemplateVersion());return hash(value);
    }
    private Path path(String key){if(key==null||!key.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid cache key");return Path.of(directory).resolve(key+".json");}
    public Map<String,Object> load(String key){
        if(!enabled)return null;
        try{
            Path file=path(key);if(!Files.exists(file)||Files.size(file)>1000000||Files.getLastModifiedTime(file).toMillis()<System.currentTimeMillis()-7*86400000L)return null;
            JsonNode doc=json.readTree(Files.readString(file));if(!key.equals(doc.path("key").asText())||!REVISION.equals(doc.path("revision").asText()))return null;
            JsonNode result=doc.path("result");if(!result.path("passed").asBoolean()||!result.path("runtimeValidated").asBoolean()||!result.path("oracleValidated").asBoolean())return null;
            if(!hash(result).equals(doc.path("resultHash").asText()))return null;
            Set<String> modes=new HashSet<>();for(JsonNode c:result.path("combinations"))modes.add(c.asText());
            if(!modes.equals(Set.of("java:ACM","java:FUNCTION","python:ACM","python:FUNCTION","cpp:ACM","cpp:FUNCTION")))return null;
            var reply=json.convertValue(result,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});reply.put("completeProofReused",true);return reply;
        }catch(Exception unavailable){return null;}
    }
    public void save(String key,Map<String,Object> result){
        if(!enabled||!Boolean.TRUE.equals(result.get("passed"))||!Boolean.TRUE.equals(result.get("oracleValidated"))||!Boolean.TRUE.equals(result.get("runtimeValidated")))return;
        Path temp=null;
        try{
            Path file=path(key);Files.createDirectories(file.getParent());
            // Bound disk usage. Entries are expendable, always regenerated on a miss.
            try(var paths=Files.list(file.getParent())){var files=paths.filter(p->p.getFileName().toString().endsWith(".json")).toList();if(files.size()>=64&&!Files.exists(file)){for(Path old:files.stream().sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(Exception e){return 0L;}})).limit(files.size()-63).toList())Files.deleteIfExists(old);}}
            byte[] bytes=json.writeValueAsBytes(Map.of("key",key,"revision",REVISION,"result",result,"resultHash",hash(result)));if(bytes.length>1000000)return;
            temp=Files.createTempFile(file.getParent(),".proof-",".tmp");Files.write(temp,bytes);Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception unavailable){/* Optional cache never blocks validation/publishing. */}
        finally{if(temp!=null)try{Files.deleteIfExists(temp);}catch(Exception ignored){}}
    }
}
