package com.nemo.nemosandbox;

import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** At most one never-executed container. Claimed containers are never returned. */
final class OneShotContainerPrefetch implements AutoCloseable {
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"sandbox-one-shot-prefetch");t.setDaemon(true);return t;});
    private final ScheduledExecutorService reaper=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"sandbox-prefetch-expiry");t.setDaemon(true);return t;});
    OneShotContainerPrefetch(){reaper.scheduleWithFixedDelay(this::expire,1,1,TimeUnit.SECONDS);}
    private Pending pending;
    private boolean closed;
    private record Pending(String scope,String key,CompletableFuture<String> future,Consumer<String> remove,long created){}
    synchronized void offer(String scope,Supplier<String> create,Consumer<String> remove) {
        offer(scope,scope,create,remove);
    }
    synchronized void offer(String scope,String key,Supplier<String> create,Consumer<String> remove) {
        if(closed||pending!=null)return;
        pending=new Pending(scope,key,CompletableFuture.supplyAsync(create,executor),remove,System.nanoTime());
    }
    synchronized String take(String key) {
        if(pending==null||!pending.key().equals(key)||!pending.future().isDone())return null;
        Pending value=pending;pending=null;
        try {
            String id=value.future().join();
            if(System.nanoTime()-value.created()>TimeUnit.SECONDS.toNanos(30)){value.remove().accept(id);return null;}
            return id;
        }catch(CompletionException failed){return null;} // Engine logs preparation failures, then cold-starts.
    }
    synchronized void finish(String scope) {
        if(pending==null||!pending.scope().equals(scope))return;
        Pending value=pending;
        // Drain before deleting the mounted workspace, including a late Docker start.
        try{String id=value.future().join();if(id!=null)value.remove().accept(id);}catch(CompletionException ignored){}
        finally{pending=null;}
    }
    private synchronized void expire(){if(pending!=null&&System.nanoTime()-pending.created()>TimeUnit.SECONDS.toNanos(30))finish(pending.scope());}
    @Override public void close(){String scope;synchronized(this){closed=true;scope=pending==null?null:pending.scope();}reaper.shutdown();if(scope!=null)finish(scope);executor.shutdown();}
}
