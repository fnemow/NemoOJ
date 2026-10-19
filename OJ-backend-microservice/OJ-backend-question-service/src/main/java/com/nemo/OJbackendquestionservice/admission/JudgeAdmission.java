package com.nemo.OJbackendquestionservice.admission;

import com.nemo.OJbackendcommon.exception.ServiceBusyException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.List;

/** Database pending rows include jobs awaiting Outbox, queued, and executing.
 * No expiring distributed permit can admit extra work or leak on rollback.
 */
@Component
public class JudgeAdmission {
    private final JdbcTemplate jdbc;
    private final MeterRegistry metrics;
    @Value("${judge.admission.max-pending:12}") private int maxPending;
    @Value("${judge.admission.max-oldest-seconds:120}") private int maxOldestSeconds;
    @Value("${judge.admission.retry-after-seconds:5}") private int retryAfterSeconds;

    public JudgeAdmission(JdbcTemplate jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc; this.metrics = metrics;
    }

    /** Must remain inside the transaction that inserts submission and Outbox. */
    public void check() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Judge admission requires a submission transaction");
        if (maxPending < 1 || maxOldestSeconds < 1 || retryAfterSeconds < 1)
            throw new IllegalStateException("Invalid judge admission configuration");
        try {
            lockProducer();
            // Current read is essential: prior question reads may have opened an
            // older REPEATABLE READ snapshot before another producer committed.
            List<Long> ages = jdbc.query("SELECT TIMESTAMPDIFF(SECOND,createTime,NOW()) FROM question_submit "
                    + "WHERE status IN (0,1) AND isDelete=0 ORDER BY createTime LIMIT ? FOR UPDATE NOWAIT",
                    (rs, row) -> rs.getLong(1), maxPending);
            if (ages.size() >= maxPending) reject("capacity");
            if (!ages.isEmpty() && ages.get(0) >= maxOldestSeconds) reject("queue_age");
        } catch (DataAccessException e) {
            if (!(e instanceof PessimisticLockingFailureException) && !isNowait(e)) throw e;
            reject("transaction_busy");
        }
    }

    private void lockProducer() {
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (true) {
            try {
                jdbc.queryForObject("SELECT id FROM judge_admission_lock WHERE id=1 FOR UPDATE NOWAIT", Integer.class);
                return;
            } catch (DataAccessException e) {
                // NOWAIT (3572) leaves the MySQL transaction usable. Never retry
                // a deadlock victim or a connection/database failure here.
                if (!isNowait(e) || System.nanoTime() >= deadline) throw e;
                try { Thread.sleep(20); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw e;
                }
            }
        }
    }

    private boolean isNowait(DataAccessException e) {
        return e.getMostSpecificCause() instanceof java.sql.SQLException sql && sql.getErrorCode() == 3572;
    }

    private void reject(String reason) {
        metrics.counter("nemo_judge_admission_rejected", "reason", reason).increment();
        throw new ServiceBusyException(retryAfterSeconds);
    }
}
