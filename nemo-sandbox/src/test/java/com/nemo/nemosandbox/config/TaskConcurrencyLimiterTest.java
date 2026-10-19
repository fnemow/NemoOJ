package com.nemo.nemosandbox.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 沙箱并发闸门测试（T08）。
 * <p>
 * 要验证的两件事：达到上限时**明确拒绝**而不是无限等待；
 * 许可必须能正确归还，否则容量会永久减少（这是这类限流器最常见的缺陷）。
 */
class TaskConcurrencyLimiterTest {
    @Test void prefetchRequiresBackgroundOwnershipKnownMemoryAndNoForeground(){
        var gate=org.mockito.Mockito.spy(limiter(2,10));
        org.mockito.Mockito.doReturn(2097152L).when(gate).availableMemoryKB();
        assertTrue(!gate.canPrefetch());
        try(var background=gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND)){
            assertNotNull(background);assertTrue(gate.canPrefetch());
            org.mockito.Mockito.doReturn(800000L).when(gate).availableMemoryKB();assertTrue(!gate.canPrefetch());
            org.mockito.Mockito.doReturn(-1L).when(gate).availableMemoryKB();assertTrue(!gate.canPrefetch());
            org.mockito.Mockito.doReturn(2097152L).when(gate).availableMemoryKB();
            try(var formal=gate.tryAcquire()){assertNotNull(formal);assertTrue(!gate.canPrefetch());}
        }
    }
    @Test void formalWaitsForExistingBackgroundWithoutChangingOrdinarySaturationBudget() throws Exception {
        var gate=limiter(1,30);ReflectionTestUtils.setField(gate,"foregroundBackgroundWaitMs",300L);
        var background=gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND);
        var pool=Executors.newSingleThreadExecutor();
        try{
            var admitted=pool.submit(()->gate.tryAcquire());
            awaitWaiting(gate,TaskConcurrencyLimiter.Priority.FORMAL);
            Thread.sleep(80);assertTrue(!admitted.isDone()); // longer than normal 30 ms
            background.close();
            try(var formal=admitted.get(1,TimeUnit.SECONDS)){
                assertNotNull(formal);assertNull(gate.tryAcquire()); // no background: original budget
            }
        }finally{background.close();pool.shutdownNow();}
        assertEquals(1,gate.availablePermits());
        ReflectionTestUtils.setField(gate,"idleGraceMs",0L);
        ReflectionTestUtils.setField(gate,"foregroundBackgroundWaitMs",70L);
        try(var held=gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND)){
            assertNotNull(held);long began=System.nanoTime();assertNull(gate.tryAcquire());
            long waited=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);
            assertTrue(waited>=50 && waited<1000); // still bounded
        }
    }
    @Test void backgroundDoesNotStartAlongsideForegroundEvenWithSpareSlots() {
        var gate=limiter(4,30);
        try(var formal=gate.tryAcquire()) {assertNotNull(formal);assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND));}
        assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND)); // idle grace
        ReflectionTestUtils.setField(gate,"idleGraceMs",0L);
        try(var interactive=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN)) {assertNotNull(interactive);assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND));}
        try(var background=gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND)){assertNotNull(background);}
        assertEquals(4,gate.availablePermits());
    }
    @Test void memoryPressureReducesBackgroundConcurrencyAndThenDefersIt() {
        var gate=org.mockito.Mockito.spy(limiter(4,20));
        org.mockito.Mockito.doReturn(800000L).when(gate).availableMemoryKB();
        try(var first=gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND)){
            assertNotNull(first);assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND));
            try(var formal=gate.tryAcquire()){assertNotNull(formal);}
        }
        org.mockito.Mockito.doReturn(400000L).when(gate).availableMemoryKB();
        assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND));
        assertEquals(4,gate.availablePermits());
    }
    @Test void backgroundYieldsToInteractiveAndResumesWithoutLosingCapacity() throws Exception {
        var gate=limiter(2,1000);ReflectionTestUtils.setField(gate,"idleGraceMs",0L);
        var background=gate.tryAcquire(TaskConcurrencyLimiter.Priority.BACKGROUND);
        var interactive=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN);
        var pool=Executors.newSingleThreadExecutor();
        try{
            var resumed=pool.submit(()->{background.yieldToFormal();return true;});
            awaitWaiting(gate,TaskConcurrencyLimiter.Priority.BACKGROUND);
            assertEquals(1,gate.runningTasks());assertTrue(!resumed.isDone());
            interactive.close();assertTrue(resumed.get(2,TimeUnit.SECONDS));
        }finally{pool.shutdownNow();interactive.close();background.close();}
        assertEquals(2,gate.availablePermits());
    }
    @Test
    void backgroundYieldsBothSlotsAndResumesOnlyAfterFormalWorkCompletes() throws Exception {
        var gate=limiter(2,2000);var first=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN);var second=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN);
        var pool=Executors.newFixedThreadPool(3);var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);
        try {
            var formal=pool.submit(()->{try(var permit=gate.tryAcquire()){assertNotNull(permit);entered.countDown();assertTrue(finish.await(2,TimeUnit.SECONDS));}return true;});
            awaitWaiting(gate,TaskConcurrencyLimiter.Priority.FORMAL);
            var yieldFirst=pool.submit(()->{first.yieldToFormal();return true;});
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            var yieldSecond=pool.submit(()->{second.yieldToFormal();return true;});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);while(gate.waitingTasks(TaskConcurrencyLimiter.Priority.TRY_RUN)<2&&System.nanoTime()<end)Thread.sleep(1);
            assertEquals(2,gate.waitingTasks(TaskConcurrencyLimiter.Priority.TRY_RUN));assertEquals(1,gate.runningTasks());
            assertTrue(!yieldFirst.isDone()&&!yieldSecond.isDone());finish.countDown();
            assertTrue(formal.get(2,TimeUnit.SECONDS));yieldFirst.get(2,TimeUnit.SECONDS);yieldSecond.get(2,TimeUnit.SECONDS);
            assertEquals(2,gate.runningTasks());
        }finally{finish.countDown();pool.shutdownNow();first.close();second.close();}
        assertEquals(2,gate.availablePermits());
    }

    @Test
    void interruptedYieldDoesNotLeakOrDoubleReleaseCapacity() throws Exception {
        var gate=limiter(2,2000);var background=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN);var formal=gate.tryAcquire();
        var thread=new Thread(()->{try{background.yieldToFormal();}catch(IllegalStateException expected){assertTrue(Thread.currentThread().isInterrupted());}finally{background.close();background.close();}});
        thread.start();awaitWaiting(gate,TaskConcurrencyLimiter.Priority.TRY_RUN);thread.interrupt();thread.join(1000);
        assertTrue(!thread.isAlive());assertEquals(1,gate.runningTasks());formal.close();assertEquals(2,gate.availablePermits());
    }
    @Test
    void healthProbeDefersEvenWhenOneOfTwoSlotsIsFree() {
        var gate=limiter(2,100);
        try(var formal=gate.tryAcquire()) {
            assertNotNull(formal);
            assertEquals(1,gate.availablePermits());
            assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.HEALTH));
            try(var interactive=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN)) {
                assertNotNull(interactive);
            }
        }
        try(var probe=gate.tryAcquire(TaskConcurrencyLimiter.Priority.HEALTH)) {
            assertNotNull(probe);
        }
        assertEquals(2,gate.availablePermits());
    }
    @Test
    void formalWorkPrecedesQueuedTryRunAndHealthNeverWaits() throws Exception {
        var gate=limiter(1,2000);
        var held=gate.tryAcquire();
        var order=new java.util.concurrent.CopyOnWriteArrayList<String>();
        var pool=Executors.newFixedThreadPool(2);
        try {
            var interactive=pool.submit(()->{try(var p=gate.tryAcquire(TaskConcurrencyLimiter.Priority.TRY_RUN)) {
                assertNotNull(p);order.add("try");
            }});
            awaitWaiting(gate,TaskConcurrencyLimiter.Priority.TRY_RUN);
            var formal=pool.submit(()->{try(var p=gate.tryAcquire()) { assertNotNull(p);order.add("formal"); }});
            awaitWaiting(gate,TaskConcurrencyLimiter.Priority.FORMAL);
            assertNull(gate.tryAcquire(TaskConcurrencyLimiter.Priority.HEALTH));
            held.close();held.close();
            formal.get(3,TimeUnit.SECONDS);interactive.get(3,TimeUnit.SECONDS);
            assertEquals(java.util.List.of("formal","try"),order);
            assertEquals(1,gate.availablePermits());
        } finally { held.close();pool.shutdownNow(); }
    }
    private static void awaitWaiting(TaskConcurrencyLimiter gate,TaskConcurrencyLimiter.Priority priority) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(gate.waitingTasks(priority)==0 && System.nanoTime()<end) Thread.sleep(1);
        assertEquals(1,gate.waitingTasks(priority));
    }

    private static TaskConcurrencyLimiter limiter(int max, long acquireTimeoutMs) {
        TaskConcurrencyLimiter limiter = new TaskConcurrencyLimiter();
        ReflectionTestUtils.setField(limiter, "maxConcurrentTasks", max);
        ReflectionTestUtils.setField(limiter, "acquireTimeoutMs", acquireTimeoutMs);
        ReflectionTestUtils.invokeMethod(limiter, "init");
        return limiter;
    }

    @Test
    @DisplayName("并发达到上限后，新请求被明确拒绝而不是阻塞")
    void rejectsWhenCapacityExhausted() {
        TaskConcurrencyLimiter limiter = limiter(1, 200);

        try (TaskConcurrencyLimiter.Permit first = limiter.tryAcquire()) {
            assertNotNull(first, "第一个请求应拿到许可");
            assertEquals(1, limiter.runningTasks());

            long start = System.currentTimeMillis();
            TaskConcurrencyLimiter.Permit second = limiter.tryAcquire();
            long elapsed = System.currentTimeMillis() - start;

            assertNull(second, "容量已满时应返回 null 表示过载");
            // 等待时间应接近配置的超时，而不是无限等待
            assertTrue(elapsed >= 150, "应等待了配置的时长，实际 " + elapsed + "ms");
            assertTrue(elapsed < 3000, "不应无限等待，实际 " + elapsed + "ms");
        }
    }

    @Test
    @DisplayName("许可归还后容量恢复（不会泄漏）")
    void releasesPermitOnClose() {
        TaskConcurrencyLimiter limiter = limiter(2, 100);

        TaskConcurrencyLimiter.Permit p1 = limiter.tryAcquire();
        TaskConcurrencyLimiter.Permit p2 = limiter.tryAcquire();
        assertNotNull(p1);
        assertNotNull(p2);
        assertEquals(2, limiter.runningTasks());
        assertNull(limiter.tryAcquire(), "两个许可都用掉后应拒绝");

        p1.close();
        assertEquals(1, limiter.runningTasks());
        TaskConcurrencyLimiter.Permit p3 = limiter.tryAcquire();
        assertNotNull(p3, "归还后应重新可用");

        p2.close();
        p3.close();
        assertEquals(0, limiter.runningTasks());
        assertEquals(2, limiter.availablePermits(), "全部归还后许可数应回到上限");
    }

    @Test
    @DisplayName("重复 close 不会重复归还许可")
    void doubleCloseIsSafe() {
        TaskConcurrencyLimiter limiter = limiter(1, 100);

        TaskConcurrencyLimiter.Permit p = limiter.tryAcquire();
        assertNotNull(p);
        p.close();
        p.close();
        p.close();

        assertEquals(0, limiter.runningTasks(), "重复 close 不应把计数减成负数");
        assertEquals(1, limiter.availablePermits(), "许可数不应超过上限");
    }

    @Test
    @DisplayName("并发使用下计数不串位，且全部归还")
    void concurrentUsageKeepsConsistent() throws Exception {
        int max = 3;
        TaskConcurrencyLimiter limiter = limiter(max, 2000);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        gate.await();
                        try (TaskConcurrencyLimiter.Permit permit = limiter.tryAcquire()) {
                            if (permit == null) {
                                rejected.incrementAndGet();
                            } else {
                                acquired.incrementAndGet();
                                Thread.sleep(50);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            gate.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "任务应在时限内完成");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads, acquired.get() + rejected.get(), "每个请求都应有明确结果");
        assertTrue(acquired.get() >= max, "至少应有 max 个请求被接受，实际 " + acquired.get());
        assertEquals(0, limiter.runningTasks(), "全部结束后不应有残留执行中计数");
        assertEquals(max, limiter.availablePermits(), "许可应全部归还");
    }
}
