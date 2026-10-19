package com.nemo.nemosandbox.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.concurrent.TimeUnit;

/** Bounded non-preemptive admission. Health probes never wait. */
@Slf4j
@Component
public class TaskConcurrencyLimiter {
    public enum Priority { FORMAL, TRY_RUN, BACKGROUND, HEALTH }
    @Value("${sandbox.max-concurrent-tasks:4}") private int maxConcurrentTasks;
    @Value("${sandbox.acquire-timeout-ms:3000}") private long acquireTimeoutMs;
    @Value("${sandbox.foreground.background-wait-ms:15000}") private long foregroundBackgroundWaitMs=15000;
    private int running;
    @Value("${sandbox.background.idle-grace-ms:1000}") private long idleGraceMs=1000;
    @Value("${sandbox.background.max-concurrent:2}") private int backgroundConcurrency=2;
    @Value("${sandbox.background.min-available-memory-kb:524288}") private long minAvailableMemoryKB=524288;
    private long lastForegroundActivity;
    private final int[] runningByPriority = new int[Priority.values().length];
    private final int[] waiting = new int[Priority.values().length];

    @jakarta.annotation.PostConstruct
    void init() {
        if (maxConcurrentTasks < 1 || acquireTimeoutMs < 0 || foregroundBackgroundWaitMs<0 || idleGraceMs<0 || backgroundConcurrency<1 || minAvailableMemoryKB<0)
            throw new IllegalArgumentException("Invalid sandbox admission limits");
        log.info("Sandbox slots={}, bounded wait={}ms; background slots={}, idle grace={}ms; priority=FORMAL,TRY_RUN,BACKGROUND,HEALTH", maxConcurrentTasks, acquireTimeoutMs,backgroundConcurrency,idleGraceMs);
    }
    public Permit tryAcquire() { return tryAcquire(Priority.FORMAL); }
    public synchronized Permit tryAcquire(Priority priority) {
        // A trusted background compilation can outlive the ordinary admission wait.
        // Queue foreground work for its slot instead of reporting an infrastructure
        // failure/retrying the submission. This remains bounded and starts no extra
        // containers. Other saturation paths retain their original wait budget.
        long waitMs=priority==Priority.FORMAL && runningByPriority[Priority.BACKGROUND.ordinal()]>0
                ? Math.max(acquireTimeoutMs,foregroundBackgroundWaitMs) : acquireTimeoutMs;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
        int rank = priority.ordinal();
        waiting[rank]++;
        try {
            // A probe may use idle capacity only. Even with two slots it must
            // not compete for CPU with code that is already being judged.
            while (running >= maxConcurrentTasks || higherPriorityWaiting(rank) || backgroundBlocked(priority)
                    || (priority == Priority.HEALTH && running > 0)) {
                long remaining = deadline - System.nanoTime();
                if (priority == Priority.HEALTH || remaining <= 0) return null;
                TimeUnit.NANOSECONDS.timedWait(this, Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)));
            }
            running++;
            runningByPriority[rank]++;
            if(rank<Priority.BACKGROUND.ordinal())lastForegroundActivity=System.nanoTime();
            return new Permit(priority);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            waiting[rank]--;
            notifyAll();
        }
    }
    private boolean higherPriorityWaiting(int rank) {
        for (int i=0;i<rank;i++) if (waiting[i]>0) return true;
        return false;
    }
    private boolean backgroundBlocked(Priority priority) {
        if(priority!=Priority.BACKGROUND)return false;
        long memory=availableMemoryKB();
        int capacity=memory>=0 && memory<minAvailableMemoryKB*2 ? 1 : backgroundConcurrency;
        return runningByPriority[0]>0 || runningByPriority[1]>0
                || (memory>=0 && memory<minAvailableMemoryKB)
                || runningByPriority[2]>=capacity
                || (lastForegroundActivity!=0 && System.nanoTime()-lastForegroundActivity<TimeUnit.MILLISECONDS.toNanos(idleGraceMs));
    }
    long availableMemoryKB(){
        // /proc/meminfo supplies host pressure, not the per-program memory budget.
        try(var lines=java.nio.file.Files.lines(java.nio.file.Path.of("/proc/meminfo"))){
            return lines.filter(l->l.startsWith("MemAvailable:")).mapToLong(l->Long.parseLong(l.replaceAll("[^0-9]",""))).findFirst().orElse(-1);
        }catch(Exception unavailable){return -1;}
    }
    public synchronized int runningTasks() { return running; }
    public synchronized int availablePermits() { return maxConcurrentTasks-running; }
    public synchronized int waitingTasks(Priority priority) { return waiting[priority.ordinal()]; }
    /** Speculation gets no execution slot and must not run during foreground pressure. */
    public synchronized boolean canPrefetch() {
        long memory=availableMemoryKB();
        return runningByPriority[0]==0&&runningByPriority[1]==0&&waiting[0]==0&&waiting[1]==0
            && runningByPriority[2]>0&&memory>=Math.max(1048576,minAvailableMemoryKB*2);
    }
    public final class Permit implements AutoCloseable {
        private final Priority priority;
        private final long deadlineNanos=System.nanoTime()+TimeUnit.MINUTES.toNanos(15);
        private boolean held=true;
        private boolean released;
        private Permit(Priority priority){this.priority=priority;}
        /** Yield only between containers; no running user process is interrupted. */
        public void yieldToFormal() {
            synchronized(TaskConcurrencyLimiter.this) {
                if(released)throw new IllegalStateException("Sandbox permit already closed");
                if(priority!=Priority.TRY_RUN && priority!=Priority.BACKGROUND)return;
                if(priority==Priority.BACKGROUND && System.nanoTime()>=deadlineNanos)throw new IllegalStateException("Background sandbox deadline exceeded");
                int rank=priority.ordinal();
                boolean background=priority==Priority.BACKGROUND;
                if(!higherPriorityWaiting(rank) && !backgroundBlocked(priority) && (background || runningByPriority[0]==0))return;
                held=false;running--;runningByPriority[rank]--;waiting[rank]++;
                TaskConcurrencyLimiter.this.notifyAll();
                long deadline=background?deadlineNanos:System.nanoTime()+TimeUnit.MINUTES.toNanos(15);
                try {
                    while(running>=maxConcurrentTasks || higherPriorityWaiting(rank) || backgroundBlocked(priority) || (!background && runningByPriority[0]>0)) {
                        long remaining=deadline-System.nanoTime();
                        if(remaining<=0)throw new IllegalStateException("Background sandbox deadline exceeded");
                        TimeUnit.NANOSECONDS.timedWait(TaskConcurrencyLimiter.this,Math.min(remaining,TimeUnit.MILLISECONDS.toNanos(100)));
                    }
                    running++;runningByPriority[rank]++;held=true;
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Background sandbox interrupted",interrupted);}
                finally{waiting[rank]--;TaskConcurrencyLimiter.this.notifyAll();}
            }
        }
        @Override public void close() {
            synchronized (TaskConcurrencyLimiter.this) {
                if (!released) {
                    released=true;
                    if(held){running--;runningByPriority[priority.ordinal()]--;held=false;}
                    if(priority.ordinal()<Priority.BACKGROUND.ordinal())lastForegroundActivity=System.nanoTime();
                    TaskConcurrencyLimiter.this.notifyAll();
                }
            }
        }
    }
}
