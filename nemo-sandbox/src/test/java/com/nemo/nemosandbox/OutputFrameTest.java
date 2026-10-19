package com.nemo.nemosandbox;
import com.github.dockerjava.api.model.*;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class OutputFrameTest {
    @Test void pollingCompilationDoesNotCloseItsOutputStream() throws Exception {
        var finished = new java.util.concurrent.CountDownLatch(1);
        var out = new StringBuilder();
        var callback = new DockerExecutionEngine().outputCallback(out, new StringBuilder(), finished);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        callback.onStart(() -> closed.set(true));
        assertFalse(finished.await(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        assertFalse(closed.get());
        callback.onNext(new Frame(StreamType.STDOUT, "compiled".getBytes(StandardCharsets.UTF_8)));
        callback.onComplete();
        assertTrue(finished.await(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        callback.awaitCompletion();
        assertEquals("compiled", out.toString());
        assertTrue(closed.get());
    }
    @Test void utf8SplitAcrossFramesIsDecodedOnceAndStderrIsBounded() {
        var engine=new DockerExecutionEngine();
        var out=new StringBuilder();var err=new StringBuilder();
        var callback=engine.outputCallback(out,err);
        byte[] value="你好".getBytes(StandardCharsets.UTF_8);
        callback.onNext(new Frame(StreamType.STDOUT,java.util.Arrays.copyOfRange(value,0,2)));
        callback.onNext(new Frame(StreamType.STDOUT,java.util.Arrays.copyOfRange(value,2,value.length)));
        callback.onNext(new Frame(StreamType.STDERR,new byte[2*1024*1024]));
        callback.onComplete();
        assertEquals("你好",out.toString());
        assertTrue(err.length()<=1024*1024);
    }
}
