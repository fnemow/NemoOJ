package com.nemo.OJbackendjudgeservice.judge;

import com.nemo.OJbackendjudgeservice.judge.codesandbox.SandboxBusyException;
import com.nemo.OJbackendjudgeservice.judge.codesandbox.impl.RemoteCodeSandBox;
import com.nemo.OJbackendmodel.model.codesandbox.ExecuteCodeRequestV2;
import com.nemo.OJbackendmodel.model.execution.ExecutionPlan;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RemoteSandboxBusyTest {
    @Test void admission503IsTypedBusyRatherThanCandidateFailure() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v2/executeCode",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});server.start();
        try{
            var remote=new RemoteCodeSandBox();remote.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort());remote.setSecret("test");
            var plan=new ExecutionPlan();plan.setCases(List.of());
            assertThrows(SandboxBusyException.class,()->remote.executeCodeV2(ExecuteCodeRequestV2.builder().submissionId(0L).backgroundValidation(true).executionPlan(plan).build()));
        }finally{server.stop(0);}
    }
}
