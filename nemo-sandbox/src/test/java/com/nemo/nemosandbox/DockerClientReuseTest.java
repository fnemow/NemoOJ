package com.nemo.nemosandbox;

import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

class DockerClientReuseTest {
    @Test void borrowedCloseDoesNotStopOtherExecutionsAndShutdownClosesOnce() throws Exception {
        DockerClient delegate=mock(DockerClient.class);
        DockerClientFactory factory=spy(new DockerClientFactory());
        doReturn(delegate).when(factory).newClient();
        DockerClient first=factory.borrowClient(),second=factory.borrowClient();
        assertSame(first,second);first.close();verify(delegate,never()).close();
        second.pingCmd();verify(delegate).pingCmd();
        factory.shutdown();factory.shutdown();verify(delegate,times(1)).close();
        assertThrows(IllegalStateException.class,factory::borrowClient);
    }
    @Test void switchOffReturnsOwnedFreshClients() throws Exception {
        DockerClient delegate=mock(DockerClient.class);
        DockerClientFactory factory=spy(new DockerClientFactory());
        ReflectionTestUtils.setField(factory,"sharedClientEnabled",false);
        doReturn(delegate).when(factory).newClient();
        assertSame(delegate,factory.borrowClient());delegate.close();verify(delegate).close();
    }
    @Test void statisticsStreamIsClosedIncludingLateStartAndRepeatedCleanup() throws Exception {
        DockerExecutionEngine engine=new DockerExecutionEngine();
        var callback=engine.memCallback(new long[1]);java.io.Closeable stream=mock(java.io.Closeable.class);
        callback.onStart(stream);callback.close();callback.close();verify(stream,times(1)).close();
        var late=engine.memCallback(new long[1]);late.close();late.onStart(stream);verify(stream,times(2)).close();
    }
    @Test void completePeakAndOomEvidenceRequired(){
        var snapshot=DockerExecutionEngine.parseMemorySnapshot("low 0\noom_kill 2\npeak 268435456\n");
        assertEquals(2,snapshot.oomKills());assertEquals(268435456,snapshot.peak());
        assertThrows(IllegalStateException.class,()->DockerExecutionEngine.parseMemorySnapshot("oom_kill 0\n"));
        assertThrows(IllegalStateException.class,()->DockerExecutionEngine.parseMemorySnapshot("oom_kill 0\npeak -1\n"));
    }
}
