package com.nemo.OJbackendjudgeservice.judge.checker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ExactIntegerPrecisionTest {
    @Test void preservesLongPrecisionRecursively() {
        var checker=new ExactChecker();
        assertFalse(checker.check("9223372036854775807","9223372036854775806",null).isPassed());
        assertFalse(checker.check("{\"v\":[9007199254740992]}","{\"v\":[9007199254740993]}",null).isPassed());
        assertTrue(checker.check("1","1.0",null).isPassed());
        assertFalse(checker.check("true","1",null).isPassed());
    }
}
