package com.nemo.nemosandbox;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class HostCgroupMemoryReaderTest {
    @TempDir Path root;
    private static final String ID = "a".repeat(64);

    private Path group() throws Exception {
        return Files.createDirectories(root.resolve("system.slice/docker-" + ID + ".scope"));
    }

    @Test void readsChildOomEvenWhenPidOneSurvivesAndPreservesPeak() throws Exception {
        Path group = group();
        Files.writeString(group.resolve("memory.events"), "oom 2\noom_kill 1\n");
        Files.writeString(group.resolve("memory.peak"), "268435456\n");
        var snapshot = HostCgroupMemoryReader.read(root.toString(), ID).orElseThrow();
        assertEquals(1, snapshot.oomKills());
        assertEquals(268435456, snapshot.peak());
    }

    @Test void emptyConfigurationDifferentDriverAndRemovedGroupAllowExecFallback() throws Exception {
        assertTrue(HostCgroupMemoryReader.read("", ID).isEmpty());
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID).isEmpty());
        Path group = group();
        Files.writeString(group.resolve("memory.events"), "oom_kill 0\n");
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID).isEmpty());
        Files.writeString(group.resolve("memory.peak"), "4096\n");
        assertEquals(0, HostCgroupMemoryReader.read(root.toString(), ID).orElseThrow().oomKills());
        Files.delete(group.resolve("memory.events"));
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID).isEmpty());
    }

    @Test void incompleteOrNegativeCountersNeverFabricateSuccessfulEvidence() throws Exception {
        Path group = group();
        Files.writeString(group.resolve("memory.events"), "oom 0\n");
        Files.writeString(group.resolve("memory.peak"), "4096\n");
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID).isEmpty());
        Files.writeString(group.resolve("memory.events"), "oom_kill -1\n");
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID).isEmpty());
        Files.writeString(group.resolve("memory.events"), "oom_kill 0\n");
        Files.writeString(group.resolve("memory.peak"), "not-a-number\n");
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID).isEmpty());
    }

    @Test void requiresFullDockerIdAndRejectsTraversal() throws Exception {
        assertTrue(HostCgroupMemoryReader.read(root.toString(), "../" + ID).isEmpty());
        assertTrue(HostCgroupMemoryReader.read(root.toString(), "short-id").isEmpty());
        assertTrue(HostCgroupMemoryReader.read(root.toString(), null).isEmpty());
        Path group = group();
        Files.writeString(group.resolve("memory.events"), "oom_kill 0\n");
        Files.writeString(group.resolve("memory.peak"), "1\n");
        assertTrue(HostCgroupMemoryReader.read(root.toString(), ID.substring(0, 12)).isEmpty());
    }
}
