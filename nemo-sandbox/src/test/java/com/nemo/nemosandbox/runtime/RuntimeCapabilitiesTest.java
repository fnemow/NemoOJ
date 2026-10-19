package com.nemo.nemosandbox.runtime;

import com.nemo.nemosandbox.CodeSandBox;
import com.nemo.nemosandbox.config.TaskConcurrencyLimiter;
import com.nemo.nemosandbox.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RuntimeCapabilitiesTest {
    @Test void successfulSmokeIsCachedAndReportsMeasuredVersion() {
        var sandbox=mock(CodeSandBox.class);
        var python=new PythonRuntimeAdapter();
        ReflectionTestUtils.setField(python,"image","python@sha256:"+"a".repeat(64));
        var registry=new LanguageRuntimeRegistry(List.of(python));
        var env=new MockEnvironment().withProperty("sandbox.language.enabled","python");
        when(sandbox.executeCodeV2(any())).thenReturn(ExecuteCodeResponseV2.builder().overallStatus(0)
            .cases(List.of(CaseExecutionResult.builder().caseIndex(0).status(0).stdout("3.12.14").build())).build());
        var service=new RuntimeCapabilities(sandbox,registry,env,limiter());
        var snapshot=service.snapshot();
        assertEquals("3.12.14",snapshot.get(0).get("toolchainVersion"));
        assertEquals(true,snapshot.get(0).get("imageReady"));
        assertEquals(snapshot,service.snapshot());
        verify(sandbox,times(1)).executeCodeV2(any());
        String id=RuntimeCapabilities.runtimeId(python);
        ReflectionTestUtils.setField(python,"factor",2.0);
        assertNotEquals(id,RuntimeCapabilities.runtimeId(python));
        service.snapshot();
        verify(sandbox,times(2)).executeCodeV2(any());
    }
    @Test void successfulProbeSurvivesThirtySecondsButExpiresAtFiveMinutes() {
        var sandbox=mock(CodeSandBox.class);
        var python=new PythonRuntimeAdapter();
        ReflectionTestUtils.setField(python,"image","python@sha256:"+"a".repeat(64));
        when(sandbox.executeCodeV2(any())).thenReturn(ExecuteCodeResponseV2.builder().overallStatus(0)
            .cases(List.of(CaseExecutionResult.builder().stdout("3.12.14").build())).build());
        var service=new RuntimeCapabilities(sandbox,new LanguageRuntimeRegistry(List.of(python)),new MockEnvironment().withProperty("sandbox.language.enabled","python"),limiter());
        setTime(service,0); service.snapshot();
        setTime(service,31000); service.snapshot();
        verify(sandbox,times(1)).executeCodeV2(any());
        setTime(service,300000); service.snapshot();
        verify(sandbox,times(2)).executeCodeV2(any());
    }
    @Test void failedLanguageRetriesWithoutReprobingSuccessfulLanguage() {
        var sandbox=mock(CodeSandBox.class);
        var python=new PythonRuntimeAdapter(); var cpp=new CppRuntimeAdapter();
        ReflectionTestUtils.setField(python,"image","python@sha256:"+"a".repeat(64));
        ReflectionTestUtils.setField(cpp,"image","cpp@sha256:"+"b".repeat(64));
        when(sandbox.executeCodeV2(any())).thenAnswer(invocation->{
            ExecuteCodeRequestV2 request=invocation.getArgument(0);
            if(request.getExecutionPlan().getLanguage().equals("cpp")) throw new IllegalStateException("unavailable");
            return ExecuteCodeResponseV2.builder().overallStatus(0).cases(List.of(CaseExecutionResult.builder().stdout("3.12.14").build())).build();
        });
        var service=new RuntimeCapabilities(sandbox,new LanguageRuntimeRegistry(List.of(python,cpp)),new MockEnvironment().withProperty("sandbox.language.enabled","python,cpp"),limiter());
        setTime(service,0); service.snapshot();
        setTime(service,4999); service.snapshot();
        verify(sandbox,times(2)).executeCodeV2(any());
        setTime(service,5000); var result=service.snapshot();
        verify(sandbox,times(3)).executeCodeV2(any());
        assertEquals(true,result.get(0).get("imageReady"));
        assertEquals(false,result.get(1).get("imageReady"));
    }
    private TaskConcurrencyLimiter limiter() {
        var limiter=new TaskConcurrencyLimiter();
        ReflectionTestUtils.setField(limiter,"maxConcurrentTasks",1);
        ReflectionTestUtils.setField(limiter,"acquireTimeoutMs",100L);
        return limiter;
    }
    @Test void busyCacheDoesNotConsumeSlotsAndGraceIsBounded() {
        var sandbox=mock(CodeSandBox.class);
        var python=new PythonRuntimeAdapter();
        ReflectionTestUtils.setField(python,"image","python@sha256:"+"a".repeat(64));
        when(sandbox.executeCodeV2(any())).thenReturn(ExecuteCodeResponseV2.builder().overallStatus(0)
            .cases(List.of(CaseExecutionResult.builder().stdout("3.12.14").build())).build());
        var limiter=limiter();
        var service=new RuntimeCapabilities(sandbox,new LanguageRuntimeRegistry(List.of(python)),
            new MockEnvironment().withProperty("sandbox.language.enabled","python"),limiter);
        setTime(service,0);service.snapshot();
        try(var permit=limiter.tryAcquire()) {
            setTime(service,1000);assertEquals(true,service.snapshot().get(0).get("imageReady"));
            setTime(service,300000);assertEquals(true,service.snapshot().get(0).get("verificationDeferred"));
            setTime(service,900001);assertEquals(false,service.snapshot().get(0).get("imageReady"));
            verify(sandbox,times(1)).executeCodeV2(any());
        }
        assertEquals(true,service.snapshot().get(0).get("imageReady"));
        verify(sandbox,times(2)).executeCodeV2(any());
    }
    private void setTime(RuntimeCapabilities service,long millis) {
        ReflectionTestUtils.setField(service,"clock",java.time.Clock.fixed(java.time.Instant.ofEpochMilli(millis),java.time.ZoneOffset.UTC));
    }
    @Test void failedOrWrongVersionSmokeDoesNotAdvertiseSupport() {
        var sandbox=mock(CodeSandBox.class);
        var python=new PythonRuntimeAdapter();
        ReflectionTestUtils.setField(python,"image","python@sha256:"+"b".repeat(64));
        when(sandbox.executeCodeV2(any())).thenReturn(ExecuteCodeResponseV2.builder().overallStatus(0)
            .cases(List.of(CaseExecutionResult.builder().stdout("3.13.0").build())).build());
        var service=new RuntimeCapabilities(sandbox,new LanguageRuntimeRegistry(List.of(python)),new MockEnvironment().withProperty("sandbox.language.enabled","python"),limiter());
        assertEquals(false,service.snapshot().get(0).get("imageReady"));
    }
    @Test void configurationRejectsEmptyDuplicateModesAndUnpinnedProductionImage() {
        var python=new PythonRuntimeAdapter();
        ReflectionTestUtils.setField(python,"image","python:3.12");
        var registry=new LanguageRuntimeRegistry(List.of(python));
        for(String enabled:List.of("","python,python","unknown")) {
            var env=new MockEnvironment().withProperty("sandbox.language.enabled",enabled);
            assertThrows(IllegalStateException.class,()->new RuntimeConfigurationValidator(env,registry).validate());
        }
        var prod=new MockEnvironment().withProperty("sandbox.language.enabled","python");prod.setActiveProfiles("prod");
        assertThrows(IllegalStateException.class,()->new RuntimeConfigurationValidator(prod,registry).validate());
    }
}
