package com.nemo.OJbackendjudgeservice.judge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedTemplateEvaluationTest {
    @Test void allPinnedContractsAgreeWithIndependentBackendChecksAndRetainVersionGates() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("nemo-agent/evaluation/private-generation-v1.json")))root=root.getParent();
        assertNotNull(root);
        ObjectMapper json=new ObjectMapper();
        var data=json.readTree(root.resolve("nemo-agent/evaluation/private-generation-v1.json").toFile());int contracts=0,inputs=0;
        for(var scenario:data.path("scenarios")){
            String key=scenario.path("id").asText(),version=scenario.path("version").asText();
            ProblemSpec spec=json.treeToValue(scenario.path("signature"),ProblemSpec.class);
            TrustedTemplateProperties.signature(key,version,spec);
            assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.signature(key,"unknown-version",spec));
            if(version.equals("trusted-v2"))assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.signature(key,"trusted-v1",spec));
            for(var test:scenario.path("cases")){
                assertEquals(test.path("expected"),TrustedTemplateProperties.expected(key,test.path("args")),key+" input "+inputs);
                inputs++;
            }
            String method=spec.getMethodName();spec.setMethodName("wrong");
            assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.signature(key,version,spec));
            spec.setMethodName(method);spec.getParameters().get(0).setType("boolean");
            assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.signature(key,version,spec));
            contracts++;
        }
        assertEquals(18,contracts);assertEquals(360,inputs);
    }

    @Test void invalidNewInputContractsNeverYieldPublishableAnswers() throws Exception {
        ObjectMapper json=new ObjectMapper();
        for(String[] sample:new String[][]{
            {"string_unique","{\"s\":\"A\"}"}, {"dp_climb","{\"n\":true}"},
            {"dp_coins","{\"coins\":[0],\"amount\":1}"},
            {"list_kth","{\"head\":[],\"k\":1}"},
            {"list_groups","{\"head\":[1],\"k\":0}"},
            {"tree_depth","{\"root\":[null,1]}"},
            {"tree_depth","{\"root\":[1,null,null,2]}"}}){
            var args=json.readTree(sample[1]);
            assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.expected(sample[0],args));
        }
    }
}
