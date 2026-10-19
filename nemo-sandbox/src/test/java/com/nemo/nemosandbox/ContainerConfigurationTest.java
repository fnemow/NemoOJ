package com.nemo.nemosandbox;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.*;
import com.github.dockerjava.api.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContainerConfigurationTest {
    @Test void cleanupForceRemovesWithoutWaitingForStopAndIsIdempotent() {
        var client=mock(DockerClient.class);
        var remove=mock(RemoveContainerCmd.class,RETURNS_SELF);
        when(client.removeContainerCmd("container")).thenReturn(remove);
        var engine=new DockerExecutionEngine();
        engine.cleanupContainer(client,"container","run");
        engine.cleanupContainer(client,null,"run");
        verify(remove).withForce(true);
        verify(remove).exec();
        verify(client,never()).stopContainerCmd(anyString());
    }

    @Test void cleanupFailureIsContainedAfterItIsLogged() {
        var client=mock(DockerClient.class);
        when(client.removeContainerCmd("container")).thenThrow(new RuntimeException("daemon unavailable"));
        assertDoesNotThrow(()->new DockerExecutionEngine().cleanupContainer(client,"container","compile"));
    }

    @Test void compileAndRunHavePrivateCgroupTmpfsAndRealCpuQuota() {
        for(boolean readOnly:new boolean[]{false,true}) {
            var client=mock(DockerClient.class);
            var create=mock(CreateContainerCmd.class,RETURNS_SELF);
            var start=mock(StartContainerCmd.class);
            var response=new CreateContainerResponse();response.setId("test-container");
            when(client.createContainerCmd("image")).thenReturn(create);
            when(create.exec()).thenReturn(response);
            when(client.startContainerCmd("test-container")).thenReturn(start);
            var engine=new DockerExecutionEngine();ReflectionTestUtils.setField(engine,"cpuCount",1.0);
            assertEquals("test-container",engine.createContainer(client,"image","/workspace",268435456L,readOnly,64));
            var config=ArgumentCaptor.forClass(HostConfig.class);
            verify(create).withHostConfig(config.capture());
            assertEquals("private",config.getValue().getCgroupnsMode());
            assertEquals(100000L,config.getValue().getCpuQuota());
            assertTrue(config.getValue().getTmpFs().get("/tmp").contains("noexec"));
            assertEquals(readOnly?AccessMode.ro:AccessMode.rw,config.getValue().getBinds()[0].getAccessMode());
            verify(create).withUser("65534:65534");
            verify(create).withNetworkDisabled(true);
            verify(create).withReadonlyRootfs(true);
        }
    }
    @Test void missingExecExitCodeNeverBecomesSuccess() {
        var client=mock(DockerClient.class);var inspect=mock(InspectExecCmd.class);
        when(client.inspectExecCmd("exec")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(new InspectExecResponse());
        assertThrows(IllegalStateException.class,()->new DockerExecutionEngine().inspectExitCode(client,"exec",0));
    }

    @Test void failedStartReclaimsContainerBeforeIdCanReachCaller() {
        var client=mock(DockerClient.class);
        var create=mock(CreateContainerCmd.class,RETURNS_SELF);
        var start=mock(StartContainerCmd.class);
        var remove=mock(RemoveContainerCmd.class,RETURNS_SELF);
        var response=new CreateContainerResponse();response.setId("failed-start");
        when(client.createContainerCmd("image")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(client.startContainerCmd("failed-start")).thenReturn(start);
        when(client.removeContainerCmd("failed-start")).thenReturn(remove);
        doThrow(new IllegalStateException("start failed")).when(start).exec();
        var engine=new DockerExecutionEngine();ReflectionTestUtils.setField(engine,"cpuCount",1.0);
        assertThrows(IllegalStateException.class,()->engine.createContainer(client,"image","/workspace",268435456L,true,64));
        verify(remove).withForce(true);verify(remove).exec();
    }
    @Test void backgroundCompileUsesSoftWeightAndKeepsCpuQuotaAndMemoryBudget() {
        var client=mock(DockerClient.class);var create=mock(CreateContainerCmd.class,RETURNS_SELF);
        var response=new CreateContainerResponse();response.setId("background");
        when(client.createContainerCmd("image")).thenReturn(create);when(create.exec()).thenReturn(response);
        when(client.startContainerCmd("background")).thenReturn(mock(StartContainerCmd.class));
        var engine=new DockerExecutionEngine();ReflectionTestUtils.setField(engine,"cpuCount",1.0);
        engine.createContainer(client,"image","/workspace",268435456L,false,64,128L);
        var config=ArgumentCaptor.forClass(HostConfig.class);verify(create).withHostConfig(config.capture());
        assertEquals(128,config.getValue().getCpuShares());assertEquals(100000L,config.getValue().getCpuQuota());
        assertEquals(268435456L,config.getValue().getMemory());assertEquals(268435456L,config.getValue().getMemorySwap());
    }
}
