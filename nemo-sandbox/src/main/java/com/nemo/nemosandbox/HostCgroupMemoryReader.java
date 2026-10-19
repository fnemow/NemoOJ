package com.nemo.nemosandbox;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Set;

/** Optional read-only host cgroup v2 fast path, for rootful systemd Docker only.
 * The trusted sandbox mounts this hierarchy; submitted-code containers never do.
 * Other drivers, missing counters and inaccessible mounts fall back to Docker exec.
 */
final class HostCgroupMemoryReader {
    private HostCgroupMemoryReader() {}

    static Optional<DockerExecutionEngine.MemorySnapshot> read(String root, String containerId) {
        if (root == null || root.isBlank() || containerId == null || !containerId.matches("[a-f0-9]{64}")) {
            return Optional.empty();
        }
        try {
            Path hierarchy = Path.of(root).toAbsolutePath().normalize();
            Path slice = hierarchy.resolve("system.slice");
            Path group = slice.resolve("docker-" + containerId + ".scope");
            if (Files.isSymbolicLink(slice) || Files.isSymbolicLink(group)) return Optional.empty();
            String events = readCounter(group.resolve("memory.events"));
            String peak = readCounter(group.resolve("memory.peak")).trim();
            if (!peak.matches("[0-9]+")) return Optional.empty();
            return Optional.of(DockerExecutionEngine.parseMemorySnapshot(events + "\npeak " + peak));
        } catch (IOException | RuntimeException unavailable) {
            // No guessed values: the caller must obtain normal exec evidence instead.
            return Optional.empty();
        }
    }

    private static String readCounter(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteBuffer bytes = ByteBuffer.allocate(4097);
            while (bytes.hasRemaining() && channel.read(bytes) > 0) {}
            if (!bytes.hasRemaining()) throw new IOException("Oversized cgroup counter");
            bytes.flip();
            return StandardCharsets.UTF_8.decode(bytes).toString();
        }
    }
}
