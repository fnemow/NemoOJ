package com.nemo.OJbackendquestionservice.budget;

import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Admission control for the provider's shared daily budget. Call within the
 * same transaction that inserts the RESERVED ai_usage row so the date lock is
 * held until the reservation commits or rolls back.
 */
public final class AiDailyBudget {
    private AiDailyBudget() { }

    public static boolean canReserve(JdbcTemplate jdbc, LocalDate date,
                                     BigDecimal reservation, BigDecimal dailyLimit) {
        return canReserve(jdbc, date, reservation, dailyLimit, null);
    }

    public static boolean canReserve(JdbcTemplate jdbc, LocalDate date,
                                     BigDecimal reservation, BigDecimal dailyLimit, String replacingTask) {
        if (date == null || reservation == null || dailyLimit == null
                || reservation.signum() <= 0 || dailyLimit.signum() <= 0) return false;

        jdbc.update("INSERT IGNORE INTO ai_daily_budget_lock(usageDate) VALUES (?)", date);
        jdbc.queryForObject("SELECT usageDate FROM ai_daily_budget_lock WHERE usageDate=? FOR UPDATE",
                LocalDate.class, date);

        // A locking read sees reservations committed while we waited for the
        // date lock, even when the surrounding transaction is REPEATABLE READ.
        List<BigDecimal> costs = jdbc.query(
                "SELECT estimatedCost+reservedCost FROM ai_usage u "
                        + "WHERE usageDate=? AND state IN ('RESERVED','COMMITTED','RELEASED') "
                        + "AND (? IS NULL OR taskId<>?) "
                        + "AND NOT EXISTS (SELECT 1 FROM ai_model_call c WHERE c.taskId=u.taskId) FOR UPDATE",
                (rs, row) -> rs.getBigDecimal(1), date, replacingTask, replacingTask);
        BigDecimal committedAndReserved = costs.stream().filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BigDecimal> calls = jdbc.query("SELECT estimatedCost+reservedCost FROM ai_model_call WHERE usageDate=? FOR UPDATE",
                (rs, row) -> rs.getBigDecimal(1), date);
        committedAndReserved = committedAndReserved.add(calls.stream().filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return committedAndReserved.add(reservation).compareTo(dailyLimit) <= 0;
    }
}
