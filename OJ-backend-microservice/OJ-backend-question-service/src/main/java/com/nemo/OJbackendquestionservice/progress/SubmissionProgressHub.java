package com.nemo.OJbackendquestionservice.progress;

import com.nemo.OJbackendmodel.model.entity.QuestionSubmit;
import com.nemo.OJbackendquestionservice.mapper.QuestionSubmitMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory fan-out for the instance serving an SSE connection. */
@Slf4j
@Component
public class SubmissionProgressHub {
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final QuestionSubmitMapper mapper;
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public SubmissionProgressHub(QuestionSubmitMapper mapper) {
        this.mapper = mapper;
    }

    public SseEmitter subscribe(QuestionSubmit submission) {
        long id = submission.getId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list = subscribers.computeIfAbsent(id,
                ignored -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> remove(id, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        try {
            send(emitter, payload(submission));
            if (terminal(submission.getStatus())) {
                emitter.complete();
            }
        } catch (IOException failure) {
            remove(id, emitter);
            emitter.completeWithError(failure);
        }
        return emitter;
    }

    @Async("submissionProgressExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onChanged(SubmissionProgressChangedEvent event) {
        List<SseEmitter> emitters = subscribers.get(event.submissionId());
        if (emitters == null || emitters.isEmpty()) return;
        QuestionSubmit submission = mapper.selectById(event.submissionId());
        if (submission == null) return;
        SubmissionProgressPayload payload = payload(submission);
        for (SseEmitter emitter : emitters) {
            try {
                send(emitter, payload);
                if (payload.terminal()) emitter.complete();
            } catch (Exception failure) {
                remove(event.submissionId(), emitter);
                emitter.completeWithError(failure);
            }
        }
    }

    @Scheduled(fixedDelayString = "${submission.progress.heartbeat-ms:15000}")
    public void heartbeat() {
        subscribers.forEach((id, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data(System.currentTimeMillis()));
            } catch (Exception failure) {
                remove(id, emitter);
            }
        }));
    }

    private static void send(SseEmitter emitter, SubmissionProgressPayload payload) throws IOException {
        emitter.send(SseEmitter.event().name("submission-progress").id(String.valueOf(payload.id())).data(payload));
    }

    private static SubmissionProgressPayload payload(QuestionSubmit submission) {
        Integer value = submission.getStatus();
        int status = value == null ? 0 : value;
        return new SubmissionProgressPayload(submission.getId(), status, terminal(status), submission.getUpdateTime());
    }

    private static boolean terminal(Integer status) {
        return status != null && (status == 2 || status == 3);
    }

    private void remove(long id, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = subscribers.get(id);
        if (list == null) return;
        list.remove(emitter);
        if (list.isEmpty()) subscribers.remove(id, list);
    }
}
