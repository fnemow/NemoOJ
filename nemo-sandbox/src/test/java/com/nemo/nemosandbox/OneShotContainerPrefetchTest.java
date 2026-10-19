package com.nemo.nemosandbox;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class OneShotContainerPrefetchTest {
    @Test void eachContainerCanBeClaimedOnlyOnceAndNeverByAnotherWorkspace() throws Exception {
        var removed=new CopyOnWriteArrayList<String>();
        try(var pool=new OneShotContainerPrefetch()) {
            var ready=new CountDownLatch(1);
            pool.offer("workspace-A",()->{ready.countDown();return "fresh";},removed::add);
            assertTrue(ready.await(2,TimeUnit.SECONDS));
            String id=null;
            for(int i=0;i<100&&id==null;i++){assertNull(pool.take("workspace-B"));id=pool.take("workspace-A");if(id==null)Thread.sleep(5);}
            assertEquals("fresh",id);
            assertNull(pool.take("workspace-A"));
            pool.finish("workspace-A");
            assertTrue(removed.isEmpty()); // Claimed containers are cleaned by the execution engine.
        }
    }
    @Test void finishDrainsCreationAndRemovesOnlyUnusedContainer() throws Exception {
        var removed=new CopyOnWriteArrayList<String>();var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var pool=new OneShotContainerPrefetch()) {
            pool.offer("A",()->{started.countDown();try{release.await();}catch(InterruptedException e){throw new RuntimeException(e);}return "unused";},removed::add);
            assertTrue(started.await(2,TimeUnit.SECONDS));
            assertNull(pool.take("A"));
            var finishing=CompletableFuture.runAsync(()->pool.finish("A"));
            release.countDown();finishing.get(2,TimeUnit.SECONDS);
            assertEquals(java.util.List.of("unused"),removed);
            assertNull(pool.take("A"));
        }
    }
    @Test void failedPreparationFallsBackAndCapacityIsBounded() throws Exception {
        var calls=new AtomicInteger();
        try(var pool=new OneShotContainerPrefetch()) {
            pool.offer("A",()->{throw new IllegalStateException("daemon");},id->{});
            Thread.sleep(50);
            assertNull(pool.take("A"));
            pool.offer("A",()->"fresh",id->{});
            pool.offer("B",()->{calls.incrementAndGet();return "bad";},id->{});
            pool.finish("A");assertEquals(0,calls.get());
        }
    }
}
