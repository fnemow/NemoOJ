package com.nemo.OJbackendquestionservice.progress;

import java.util.Date;

public record SubmissionProgressPayload(long id, int status, boolean terminal, Date updatedAt) {
}
