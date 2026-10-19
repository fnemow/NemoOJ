package com.nemo.OJbackendquestionservice.service;

import com.nemo.OJbackendmodel.model.dto.question.PrivateProblemValidationRequest;
import com.nemo.OJserviceclient.JudgeFeignClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Executes generated reference implementations in the judge's isolated sandbox. */
@Service
public class PrivateProblemValidationGateway {
    private final JudgeFeignClient judge;

    public PrivateProblemValidationGateway(JudgeFeignClient judge) { this.judge = judge; }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Map<String, Object> validate(PrivateProblemValidationRequest request) {
        return judge.validatePrivateProblem(request);
    }
}
