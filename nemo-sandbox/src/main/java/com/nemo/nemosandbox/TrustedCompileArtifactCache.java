package com.nemo.nemosandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nemo.nemosandbox.model.ExecuteCodeRequestV2;
import com.nemo.nemosandbox.runtime.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.*;

/** Compile outputs only. No process, container, expected answer or execution result is cached. */
@Component
public class TrustedCompileArtifactCache {
    @Value("${SANDBOX_TRUSTED_COMPILE_CACHE_ENABLED:false}") private boolean enabled;
    private final Path root;
    private final Clock clock;
    private final int maxEntries;
    private final long maxBytes, ttlMillis;
    private static final int MAX_FILES = 256;
    private static final long MAX_ENTRY_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper json = new ObjectMapper();
    record Artifact(long bytes, String sha256) {}
    record Manifest(long createdAt, String language, Map<String, Artifact> files) {}

    public TrustedCompileArtifactCache() {
        this(Path.of(System.getProperty("user.dir"), JavaCodeSandBoxTemplate.GLOBAL_CODE_DIR_NAME, ".trusted-compile-cache"),
             Clock.systemUTC(), 128, 64 * 1024 * 1024, 24 * 60 * 60 * 1000L);
        enabled = false;
    }
    TrustedCompileArtifactCache(Path root, Clock clock, int maxEntries, long maxBytes, long ttlMillis) {
        this.root = root.toAbsolutePath().normalize(); this.clock = clock;
        this.maxEntries = maxEntries; this.maxBytes = maxBytes; this.ttlMillis = ttlMillis; enabled = true;
    }
    public boolean enabled() { return enabled; }

    public String key(ExecuteCodeRequestV2 request, LanguageRuntimeAdapter runtime, CommandSpec command, Path workspace) {
        var plan = request.getExecutionPlan(); String image = runtime.descriptor().getImageRef();
        if (!enabled || !request.isBackgroundValidation() || request.getTrustedCompileScope() == null
                || !request.getTrustedCompileScope().matches("trusted-v[12]:[a-z_]{1,64}")
                || plan == null || !Set.of("java", "cpp").contains(plan.getLanguage())
                || !plan.getLanguage().equals(runtime.language()) || !image.matches(".*@sha256:[a-fA-F0-9]{64}$")) return null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var fields = new LinkedHashMap<String,Object>();
            fields.put("version", "trusted-compile-v1"); fields.put("scope", request.getTrustedCompileScope());
            fields.put("image", image); fields.put("policy", RuntimeCapabilities.policyVersion(runtime));
            fields.put("architecture", System.getProperty("os.arch"));
            fields.put("toolchain", runtime.descriptor().getToolchainVersion()); fields.put("protocol", runtime.descriptor().getProtocolVersion());
            fields.put("language", plan.getLanguage()); fields.put("mode", plan.getMode()); fields.put("entry", plan.getEntryPoint());
            fields.put("command", command.getCommand()); fields.put("env", new TreeMap<>(command.getEnv()));
            fields.put("sources", plan.getSourceFiles().stream().sorted(Comparator.comparing(com.nemo.nemosandbox.model.SourceFile::getFileName)).toList());
            digest.update(json.writeValueAsBytes(fields));
            Path libs = workspace.resolve("lib");
            if (Files.exists(libs, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(libs)) return null;
                try (var files = Files.list(libs)) {
                    for (Path file : files.sorted().toList()) {
                        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16*1024*1024) return null;
                        digest.update(file.getFileName().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(file));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception unavailable) { return null; }
    }

    public synchronized boolean restore(String key, String language, Path workspace) {
        if (!validKey(key)) return false;
        var copied = new ArrayList<Path>(); Path entry = root.resolve(key);
        try {
            if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return false;
            Manifest manifest = json.readValue(Files.readAllBytes(entry.resolve("manifest.json")), Manifest.class);
            if (!language.equals(manifest.language()) || clock.millis()-manifest.createdAt() >= ttlMillis
                    || manifest.files().isEmpty() || manifest.files().size()>MAX_FILES) { removeEntry(entry); return false; }
            long bytes = 0;
            for (var item : manifest.files().entrySet()) {
                if (!allowedArtifact(item.getKey(), language)) throw new IOException("Invalid cached layout");
                Path file = safeFile(entry, item.getKey()); Artifact expected = item.getValue();
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)!=expected.bytes()) throw new IOException("Invalid cached file");
                bytes += expected.bytes(); if (bytes>Math.min(MAX_ENTRY_BYTES, maxBytes)) throw new IOException("Cached entry too large");
                if (!sha(Files.readAllBytes(file)).equals(expected.sha256())) throw new IOException("Cached checksum mismatch");
            }
            // Verify every artifact before installing any of them. Copies, never links.
            for (String name : manifest.files().keySet()) {
                Path target = safeFile(workspace.toAbsolutePath().normalize(), name);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Output already exists");
                Files.createDirectories(target.getParent()); Files.copy(safeFile(entry,name), target); copied.add(target);
                runtimePermissions(target, "cpp".equals(language));
            }
            return true;
        } catch (Exception unavailable) {
            for (Path file : copied) try { Files.deleteIfExists(file); } catch (IOException ignored) {}
            try { removeEntry(entry); } catch (IOException ignored) {}
            return false;
        }
    }

    public synchronized boolean store(String key, String language, Path workspace) {
        if (!validKey(key)) return false;
        Path temporary = null;
        try {
            if (Files.isSymbolicLink(root)) return false;
            Files.createDirectories(root); privatePermissions(root, true); sweep();
            if (Files.exists(root.resolve(key), LinkOption.NOFOLLOW_LINKS)) return true;
            temporary = Files.createTempDirectory(root, ".build-"); privatePermissions(temporary, true);
            var files = new TreeMap<String,Artifact>(); long bytes = 0;
            try (var paths = Files.walk(workspace)) {
                for (Path file : paths.toList()) {
                    String name = workspace.relativize(file).toString().replace(File.separatorChar,'/');
                    if (!allowedArtifact(name, language)) continue;
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsafe compile output");
                    long size = Files.size(file); bytes += size;
                    if (size<=0 || bytes>Math.min(MAX_ENTRY_BYTES,maxBytes) || files.size()>=MAX_FILES) throw new IOException("Compile output too large");
                    byte[] data = Files.readAllBytes(file); Path target = safeFile(temporary, name);
                    Files.createDirectories(target.getParent()); Files.write(target,data); privatePermissions(target,false);
                    files.put(name,new Artifact(size,sha(data)));
                }
            }
            if (files.isEmpty()) return false;
            Files.write(temporary.resolve("manifest.json"), json.writeValueAsBytes(new Manifest(clock.millis(),language,files)));
            privatePermissions(temporary.resolve("manifest.json"),false);
            Files.move(temporary, root.resolve(key), StandardCopyOption.ATOMIC_MOVE); temporary=null; sweep(); return true;
        } catch (Exception unavailable) { return false; }
        finally { if (temporary!=null) try { removeEntry(temporary); } catch (IOException ignored) {} }
    }

    private boolean validKey(String key) { return enabled && key!=null && key.matches("[a-f0-9]{64}"); }
    private static boolean allowedArtifact(String name,String language) {
        if (name.isBlank() || name.contains("..") || name.contains("\\") || name.startsWith("/") || name.startsWith("lib/")) return false;
        return "cpp".equals(language) ? name.equals("main") : "java".equals(language) && name.matches("[A-Za-z0-9_$/-]+\\.class");
    }
    private static Path safeFile(Path parent,String name) throws IOException {
        Path target=parent.resolve(name).normalize(); if (!target.startsWith(parent)) throw new IOException("Invalid cache path");
        for(Path path=target;path!=null&&!path.equals(parent);path=path.getParent()) if(Files.isSymbolicLink(path))throw new IOException("Symlink cache path");
        return target;
    }
    private static String sha(byte[] value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
    private static void privatePermissions(Path path,boolean directory) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
    }
    private static void runtimePermissions(Path path,boolean executable) throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setAttribute(path,"unix:gid",65534,LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(executable?"rwxr-x---":"rw-r-----"));
            Path parent=path.getParent();
            Files.setAttribute(parent,"unix:gid",65534,LinkOption.NOFOLLOW_LINKS);
            Files.setPosixFilePermissions(parent,PosixFilePermissions.fromString("rwxr-x---"));
        }
    }
    private void removeEntry(Path entry) throws IOException {
        Path absolute=entry.toAbsolutePath().normalize();
        if (!absolute.getParent().equals(root) || !(absolute.getFileName().toString().matches("[a-f0-9]{64}")||absolute.getFileName().toString().startsWith(".build-"))) throw new IOException("Invalid cleanup target");
        if (!Files.exists(absolute,LinkOption.NOFOLLOW_LINKS)) return;
        try(var files=Files.walk(absolute)){for(Path file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}
    }
    private void sweep() throws IOException {
        var entries=new ArrayList<Path>();
        try(var paths=Files.list(root)){
            for(Path path:paths.toList()) {
                String name=path.getFileName().toString();
                if(name.startsWith(".build-") && clock.millis()-Files.getLastModifiedTime(path).toMillis()>600000){removeEntry(path);continue;}
                if(!name.matches("[a-f0-9]{64}"))continue;
                if(Files.isSymbolicLink(path)){removeEntry(path);continue;}
                try {
                    Manifest m=json.readValue(Files.readAllBytes(path.resolve("manifest.json")),Manifest.class);
                    if(clock.millis()-m.createdAt()>=ttlMillis)removeEntry(path);else entries.add(path);
                }catch(Exception corrupt){removeEntry(path);}
            }
        }
        entries.sort(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0;}}));
        long bytes=0;for(Path entry:entries)try(var paths=Files.walk(entry)){for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())bytes+=Files.size(file);}
        while(entries.size()>maxEntries || bytes>maxBytes){Path first=entries.remove(0);try(var paths=Files.walk(first)){for(Path file:paths.filter(p->Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toList())bytes-=Files.size(file);}removeEntry(first);}
    }
    synchronized int entryCount() throws IOException {
        if(!Files.exists(root))return 0;
        try(var files=Files.list(root)){return (int)files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}")).count();}
    }
}
