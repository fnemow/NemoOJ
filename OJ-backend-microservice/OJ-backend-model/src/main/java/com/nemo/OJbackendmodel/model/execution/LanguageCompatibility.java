package com.nemo.OJbackendmodel.model.execution;
import com.nemo.OJbackendmodel.model.dto.question.ProblemSpec;
import com.nemo.OJbackendmodel.model.dto.question.CustomStructureContract;
import java.util.*;
/** Shared submission/signature compatibility, preserving existing Java-only signatures. */
public final class LanguageCompatibility {
    private LanguageCompatibility() {}
    private static final Set<String> TYPES = Set.of("int","long","double","boolean","string","int[]","long[]","double[]","boolean[]","string[]","int[][]","list<int>","list_node<int>","tree_node<int>");
    private static final Set<String> RESERVED = new HashSet<>(Arrays.asList((
        "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield " +
        "alignas alignof and_eq bitand bitor compl not_eq or_eq xor xor_eq asm auto bool case catch char char16_t char32_t const constexpr const_cast decltype default delete do double dynamic_cast enum explicit export extern float friend goto inline int long mutable namespace new noexcept nullptr operator private protected public register reinterpret_cast short signed sizeof static static_assert static_cast struct switch template this thread_local throw typedef typeid typename union unsigned using virtual void volatile wchar_t " +
        "abstract boolean byte extends final implements instanceof interface native package strictfp super synchronized throws transient null true false Solution Runner ListNode TreeNode main").split(" +")));
    public static String reason(String language, ProblemSpec spec) {
        if (!Set.of("java","python","cpp").contains(language)) return "Unknown language";
        if (spec == null) return null;
        try { CustomStructureContract.validate(spec); }
        catch (IllegalArgumentException invalid) { return "Invalid custom structure definition"; }
        if ("java".equals(language)) return null;
        if (!"Solution".equals(spec.getClassName()) || !identifier(spec.getMethodName())) return "Signature identifier is not portable";
        if (spec.getParameters() == null || spec.getReturnType() == null || (!supportedType(spec.getReturnType()) && !"void".equals(spec.getReturnType()))) return "Unsupported signature type";
        Set<String> names=new HashSet<>();
        for(var p:spec.getParameters()) if(p==null || !identifier(p.getName()) || !names.add(p.getName()) || !supportedType(p.getType())) return "Unsupported parameter";
        String source=spec.getResultSource();
        if(source==null || "return".equals(source)) return "void".equals(spec.getReturnType()) ? "Void requires parameter result source" : null;
        try {
            String indexes=source.startsWith("parameter:") ? source.substring(10) : source.startsWith("parameters:[") && source.endsWith("]") ? source.substring(12,source.length()-1) : "invalid";
            if(indexes.isBlank()) return "Empty result source";
            for(String item:indexes.split(",",-1)) {
                int i=Integer.parseInt(item.trim());
                String type=spec.getParameters().get(i).getType();
                if(Set.of("int","long","double","boolean","string").contains(type)) return "Immutable parameter cannot be a portable result";
            }
        } catch(RuntimeException e) { return "Invalid result source"; }
        return null;
    }
    private static boolean supportedType(String type) {
        return TYPES.contains(type) || CustomStructureContract.isNode(type);
    }
    public static void validateConfiguration(org.springframework.core.env.Environment environment) {
        Set<String> enabled=new HashSet<>();
        for(String item:environment.getProperty("judge.language.enabled","java").split(",",-1)) {
            String language=item.trim();
            if(!Set.of("java","python","cpp").contains(language) || !enabled.add(language)) throw new IllegalStateException("Invalid enabled language: "+language);
            var modes=org.springframework.boot.context.properties.bind.Binder.get(environment).bind("judge.language.modes."+language,org.springframework.boot.context.properties.bind.Bindable.listOf(String.class)).orElse(List.of("ACM","FUNCTION"));
            if(modes.isEmpty() || new HashSet<>(modes).size()!=modes.size() || !Set.of("ACM","FUNCTION").containsAll(modes)) throw new IllegalStateException("Invalid language modes: "+language);
        }
    }

    public static boolean modeEnabled(org.springframework.core.env.Environment environment, String prefix, String language, boolean function) {
        java.util.List<String> modes = org.springframework.boot.context.properties.bind.Binder.get(environment)
            .bind(prefix + ".language.modes." + language, org.springframework.boot.context.properties.bind.Bindable.listOf(String.class))
            .orElse(java.util.List.of("ACM", "FUNCTION"));
        return modes.contains(function ? "FUNCTION" : "ACM");
    }
    private static boolean identifier(String name) {
        return name!=null && name.matches("[A-Za-z][A-Za-z0-9_]*") && !RESERVED.contains(name);
    }
}
