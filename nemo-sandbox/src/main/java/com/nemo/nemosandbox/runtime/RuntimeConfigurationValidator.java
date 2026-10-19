package com.nemo.nemosandbox.runtime;
import org.springframework.stereotype.Component;
import org.springframework.core.env.Environment;
import org.springframework.boot.context.properties.bind.*;
import jakarta.annotation.PostConstruct;
import java.util.*;
@Component
public class RuntimeConfigurationValidator {
    private final Environment env;
    private final LanguageRuntimeRegistry registry;
    public RuntimeConfigurationValidator(Environment env, LanguageRuntimeRegistry registry) { this.env=env; this.registry=registry; }
    @PostConstruct public void validate() {
        long maxTask=env.getProperty("sandbox.limits.max-task-time-ms",Long.class,600000L);
        if (maxTask <= 0 || maxTask > 3600000) throw new IllegalStateException("Invalid total task deadline");
        Set<String> seen=new HashSet<>();
        for(String item:env.getProperty("sandbox.language.enabled","java").split(",",-1)) {
            String language=item.trim();
            if(language.isEmpty() || !seen.add(language) || !registry.isSupported(language)) throw new IllegalStateException("Invalid enabled language: "+language);
            var modes=Binder.get(env).bind("sandbox.language.modes."+language,Bindable.listOf(String.class)).orElse(List.of("ACM","FUNCTION"));
            if(modes.isEmpty() || new HashSet<>(modes).size()!=modes.size() || !Set.of("ACM","FUNCTION").containsAll(modes)) throw new IllegalStateException("Invalid modes: "+language);
            registry.get(language).policy();
            String image=registry.get(language).descriptor().getImageRef();
            if(image==null || image.isBlank()) throw new IllegalStateException("Missing image for enabled language: "+language);
            if(Arrays.asList(env.getActiveProfiles()).contains("prod") && !image.matches(".+@sha256:[a-fA-F0-9]{64}")) throw new IllegalStateException("Production image must be pinned: "+language);
        }
    }
}
