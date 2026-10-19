package com.nemo.OJbackendjudgeservice.judge;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TrustedTemplatePropertiesTest {
    final ObjectMapper json=new ObjectMapper();
    @Test void repeatedTargetsMustReturnFirstPosition() throws Exception {
        var input=json.readTree("{\"nums\":[0,0,0,0],\"target\":0}");
        assertEquals("0",TrustedTemplateProperties.expected("binary_first",input).toString());
        assertEquals("[0,3]",TrustedTemplateProperties.expected("binary_bounds",input).toString());
        assertFalse(TrustedTemplateProperties.expected("binary_first",input).equals(json.readTree("1")));
    }
    @Test void invalidRectangleAndKAreRejectedBeforeExecution() throws Exception {
        var inverted=json.readTree("{\"matrix\":[[1,2],[3,4]],\"queries\":[[0,1,1,0]]}");
        assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.expected("prefix_matrix",inverted));
        var badK=json.readTree("{\"matrix\":[[1]],\"k\":2}");
        assertThrows(IllegalArgumentException.class,()->TrustedTemplateProperties.expected("binary_matrix_k",badK));
    }
    @Test void prefixSumsAndDuplicateKthElementHaveIndependentExpectedProperties() throws Exception {
        assertEquals("[10,4]",TrustedTemplateProperties.expected("prefix_matrix",json.readTree("{\"matrix\":[[1,2],[3,4]],\"queries\":[[0,0,1,1],[1,1,1,1]]}")).toString());
        assertEquals("2",TrustedTemplateProperties.expected("binary_matrix_k",json.readTree("{\"matrix\":[[1,2],[2,3]],\"k\":3}")).toString());
        assertEquals("-2",TrustedTemplateProperties.expected("prefix_single",json.readTree("{\"nums\":[-3,1],\"left\":0,\"right\":1}")).toString());
        assertEquals("[-2,1]",TrustedTemplateProperties.expected("prefix_queries",json.readTree("{\"nums\":[-3,1],\"queries\":[[0,1],[1,1]]}")).toString());
    }
}
