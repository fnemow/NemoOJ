package com.nemo.nemosandbox.runtime;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 语言运行时注册表 —— 按语言标识查找适配器。
 * 找不到时抛出 UnsupportedLanguageException（Layer 3 终局拒绝）。
 */
@Component
public class LanguageRuntimeRegistry {

    private final Map<String, LanguageRuntimeAdapter> adapters;

    public LanguageRuntimeRegistry(List<LanguageRuntimeAdapter> adapterList) {
        this.adapters = new HashMap<>();
        for (LanguageRuntimeAdapter a : adapterList) {
            if (this.adapters.putIfAbsent(a.language(), a) != null) {
                throw new IllegalStateException("Duplicate runtime adapter: " + a.language());
            }
        }
    }

    public LanguageRuntimeAdapter get(String language) {
        LanguageRuntimeAdapter adapter = adapters.get(language);
        if (adapter == null) {
            throw new UnsupportedLanguageException(language);
        }
        return adapter;
    }

    public boolean isSupported(String language) {
        return adapters.containsKey(language);
    }
}
