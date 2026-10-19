package com.nemo.OJbackendjudgeservice.judge.harness;
import com.nemo.OJbackendmodel.model.dto.question.*;
import com.nemo.OJbackendmodel.model.execution.LanguageCompatibility;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LanguageCompatibilityTest {
    @Test void declaredCustomNodesEnableAllThreeLanguagesAndUndeclaredTypesFailClosed() {
        var spec=new ProblemSpec("function","Solution","solve",List.of(new ParameterSpec("start","node<ColoredVertex>")),"node<ColoredVertex>","return",null,1);
        var node=new CustomStructureSpec();node.setName("ColoredVertex");node.setFields(List.of(new ParameterSpec("value","int"),new ParameterSpec("parent","ref")));
        spec.setDataStructures(List.of(node));
        for(String language:List.of("java","python","cpp"))assertNull(LanguageCompatibility.reason(language,spec));
        spec.setDataStructures(List.of());
        for(String language:List.of("java","python","cpp"))assertNotNull(LanguageCompatibility.reason(language,spec));
    }
    @Test void legacyJavaIdentifierDoesNotEnableOtherLanguages() {
        var spec=new ProblemSpec("function","Solution","def",List.of(new ParameterSpec("value","int")),"int","return",null,1);
        assertNull(LanguageCompatibility.reason("java",spec));
        assertNotNull(LanguageCompatibility.reason("python",spec));
        spec.setMethodName("solve");
        assertNull(LanguageCompatibility.reason("cpp",spec));
        spec.setReturnType("void");spec.setResultSource("parameter:0");
        assertNotNull(LanguageCompatibility.reason("python",spec));
        spec.getParameters().get(0).setType("int[]");
        assertNull(LanguageCompatibility.reason("cpp",spec));
        spec.setResultSource("parameters:[0,0]");assertNull(LanguageCompatibility.reason("python",spec));
        spec.setResultSource("parameters:[0,2]");assertNotNull(LanguageCompatibility.reason("python",spec));
    }
    @Test void invalidDeploymentConfigurationFailsClosed() {
        for(String value:List.of("","java,java","ruby"))
            assertThrows(IllegalStateException.class,()->LanguageCompatibility.validateConfiguration(new MockEnvironment().withProperty("judge.language.enabled",value)));
    }
}
