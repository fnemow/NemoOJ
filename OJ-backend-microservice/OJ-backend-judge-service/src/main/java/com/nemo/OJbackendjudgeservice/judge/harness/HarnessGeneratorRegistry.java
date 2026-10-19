package com.nemo.OJbackendjudgeservice.judge.harness;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class HarnessGeneratorRegistry {
    private final Map<String, HarnessGenerator> generators = new HashMap<>();
    public HarnessGeneratorRegistry(List<HarnessGenerator> items) {
        for (HarnessGenerator item : items)
            if (generators.putIfAbsent(item.language(), item) != null)
                throw new IllegalStateException("Duplicate harness: " + item.language());
    }
    public HarnessGenerator get(String language) {
        HarnessGenerator generator = generators.get(language);
        if (generator == null) throw new IllegalArgumentException("Unsupported harness: " + language);
        return generator;
    }
    public static String resource(String name) {
        try (var input = HarnessGeneratorRegistry.class.getResourceAsStream("/harness/" + name)) {
            if (input == null) throw new IllegalStateException("Missing trusted harness: " + name);
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
}
