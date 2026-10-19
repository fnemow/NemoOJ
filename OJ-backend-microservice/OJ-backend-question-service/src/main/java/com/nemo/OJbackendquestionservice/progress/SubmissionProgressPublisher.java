package com.nemo.OJbackendquestionservice.progress;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class SubmissionProgressPublisher {
    private final ApplicationEventPublisher publisher;

    public SubmissionProgressPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void changed(long submissionId) {
        publisher.publishEvent(new SubmissionProgressChangedEvent(submissionId));
    }
}
