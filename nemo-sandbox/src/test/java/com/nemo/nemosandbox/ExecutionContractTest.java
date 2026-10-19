package com.nemo.nemosandbox;
import com.google.gson.Gson;
import com.nemo.nemosandbox.model.ExecuteCodeResponseV2;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class ExecutionContractTest {
    @Test void readsSharedResponseFixtureWithoutDroppingBudgets() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null && !Files.exists(root.resolve("deploy/contracts/execution-response-v2.json"))) root=root.getParent();
        assertNotNull(root);
        String wire=Files.readString(root.resolve("deploy/contracts/execution-response-v2.json"));
        var gson=new Gson(); var response=gson.fromJson(wire,ExecuteCodeResponseV2.class);
        assertEquals(3000L,response.getEffectiveLimits().getTimeLimitMs());
        assertEquals(1500L,response.getCases().get(0).getTime());
        assertEquals(response,gson.fromJson(gson.toJson(response),ExecuteCodeResponseV2.class));
    }
}
