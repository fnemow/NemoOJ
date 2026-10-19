package com.nemo.OJbackendquestionservice.controller;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class PrivateBatchStateTest {
    @Test void successfulSlotNeverMakesAnUnfinishedBatchComplete(){assertEquals("RUNNING",PrivateProblemBatchController.aggregate(List.of("SUCCEEDED","RUNNING","FAILED")));assertEquals("QUEUED",PrivateProblemBatchController.aggregate(List.of("SUCCEEDED","QUEUED","FAILED")));}
    @Test void partialDeliveryAndFailureRemainDistinct(){assertEquals("PARTIAL_FAILED",PrivateProblemBatchController.aggregate(List.of("SUCCEEDED","FAILED","FAILED")));assertEquals("FAILED",PrivateProblemBatchController.aggregate(List.of("FAILED","FAILED","FAILED")));assertEquals("SUCCEEDED",PrivateProblemBatchController.aggregate(List.of("SUCCEEDED","SUCCEEDED","SUCCEEDED")));}
}
