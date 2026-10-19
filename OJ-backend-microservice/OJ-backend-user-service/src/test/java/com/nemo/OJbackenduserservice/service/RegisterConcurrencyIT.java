package com.nemo.OJbackenduserservice.service;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.nemo.OJbackenduserservice.mapper.UserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 账号唯一性集成测试（需要真实 MySQL，且该库已执行 deploy/migration）。
 * <p>
 * 对应验收项：
 * <ul>
 *   <li>并发注册同一账号只产生一条有效记录</li>
 *   <li>软删除后账号仍被占用，不可重新注册</li>
 *   <li>大小写 / 尾部空格变体按同一账号处理（与 utf8mb4_unicode_ci 一致）</li>
 *   <li>唯一键冲突返回稳定的业务错误，而不是系统错误</li>
 * </ul>
 * <p>
 * 运行：{@code IT_DB_PASSWORD='<口令>' mvn -P integration verify}（见 build/README.md）
 */
@SpringBootTest
@ActiveProfiles("it")
class RegisterConcurrencyIT {

    private static final String PASSWORD = "it-password-123";

    @Autowired
    private UserService userService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 前置校验：唯一索引必须存在，否则并发测试会"通过"得毫无意义。
     * 早失败并给出可操作的提示，好过让人以为约束已经被验证过。
     */
    private void assumeUniqueIndexPresent() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user' " +
                        "AND INDEX_NAME = 'uk_user_userAccount'", Integer.class);
        assertNotNull(count);
        assertTrue(count > 0,
                "测试库缺少唯一索引 uk_user_userAccount，请先执行 deploy/migration/apply_migrations.sh");
    }

    private void cleanup(String account) {
        // 物理删除，避免测试数据残留影响后续运行
        jdbcTemplate.update("DELETE FROM `user` WHERE userAccount = ?", account);
    }

    private long rowsOf(String account) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM `user` WHERE userAccount = ?", Long.class, account);
        return n == null ? 0 : n;
    }

    // ---------- 并发 ----------

    @Test
    @DisplayName("并发注册同一账号只产生一条记录，其余返回稳定的业务错误")
    void concurrentRegistrationYieldsSingleRecord() throws Exception {
        assumeUniqueIndexPresent();
        String account = "it_race_" + System.nanoTime();
        cleanup(account);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();
        List<String> unexpected = new ArrayList<>();

        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    startGate.await();
                    try {
                        userService.userRegister(account, PASSWORD, PASSWORD);
                        succeeded.incrementAndGet();
                    } catch (BusinessException e) {
                        if ("账号重复".equals(e.getMessage())) {
                            duplicate.incrementAndGet();
                        } else {
                            synchronized (unexpected) {
                                unexpected.add(e.getCode() + ":" + e.getMessage());
                            }
                        }
                    } catch (Exception e) {
                        synchronized (unexpected) {
                            unexpected.add(e.getClass().getSimpleName() + ":" + e.getMessage());
                        }
                    }
                    return null;
                });
            }

            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> t : tasks) {
                futures.add(pool.submit(t));
            }
            startGate.countDown();
            for (Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, succeeded.get(), "并发注册应只有一条成功");
        assertEquals(threads - 1, duplicate.get(), "其余请求应返回'账号重复'");
        assertTrue(unexpected.isEmpty(), "不应出现其它异常: " + unexpected);
        assertEquals(1, rowsOf(account), "数据库中该账号只应有一条记录");

        cleanup(account);
    }

    // ---------- 软删除占用 ----------

    @Test
    @DisplayName("软删除后账号仍被占用，不能重新注册")
    void softDeletedAccountRemainsOccupied() {
        assumeUniqueIndexPresent();
        String account = "it_soft_" + System.nanoTime();
        cleanup(account);

        try {
            userService.userRegister(account, PASSWORD, PASSWORD);
            // 模拟业务删除：@TableLogic 把 isDelete 置 1，记录仍在表里
            jdbcTemplate.update("UPDATE `user` SET isDelete = 1 WHERE userAccount = ?", account);

            assertTrue(userMapper.countByAccountIncludingDeleted(account) > 0,
                    "统计口径必须包含软删除记录，否则与数据库唯一约束不一致");

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userService.userRegister(account, PASSWORD, PASSWORD));
            assertEquals("账号重复", ex.getMessage());
        } finally {
            cleanup(account);
        }
    }

    // ---------- 排序规则一致性 ----------

    @Test
    @DisplayName("大小写与尾部空格变体按同一账号处理，与 utf8mb4_unicode_ci 一致")
    void collationVariantsTreatedAsSameAccount() {
        assumeUniqueIndexPresent();
        String account = "it_case_" + System.nanoTime();
        cleanup(account);

        try {
            userService.userRegister(account, PASSWORD, PASSWORD);

            for (String variant : new String[]{account.toUpperCase(), account + " "}) {
                BusinessException ex = assertThrows(BusinessException.class,
                        () -> userService.userRegister(variant, PASSWORD, PASSWORD),
                        "变体应被拒绝: '" + variant + "'");
                assertEquals("账号重复", ex.getMessage(), "变体 '" + variant + "' 的错误信息");
            }
        } finally {
            cleanup(account);
        }
    }

    // ---------- 正常路径不被误伤 ----------

    @Test
    @DisplayName("不同账号可以正常注册")
    void distinctAccountsRegisterNormally() {
        assumeUniqueIndexPresent();
        String a = "it_ok_a_" + System.nanoTime();
        String b = "it_ok_b_" + System.nanoTime();
        cleanup(a);
        cleanup(b);

        try {
            long idA = userService.userRegister(a, PASSWORD, PASSWORD);
            long idB = userService.userRegister(b, PASSWORD, PASSWORD);
            assertTrue(idA > 0 && idB > 0);
            assertTrue(idA != idB);
        } finally {
            cleanup(a);
            cleanup(b);
        }
    }
}
