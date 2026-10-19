package com.nemo.OJbackenduserservice.service;

import com.nemo.OJbackendcommon.common.ErrorCode;
import com.nemo.OJbackendcommon.exception.BusinessException;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

@Service
public class RegistrationInvitationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final UserService users;

    public RegistrationInvitationService(JdbcTemplate jdbc, UserService users) {
        this.jdbc = jdbc;
        this.users = users;
    }

    public record Invitation(@JsonSerialize(using = ToStringSerializer.class) long id, String note,
                             @JsonSerialize(using = ToStringSerializer.class) Long createdBy, OffsetDateTime expiresAt,
                             @JsonSerialize(using = ToStringSerializer.class) Long usedBy,
                             OffsetDateTime usedAt, OffsetDateTime createTime, String status) {}
    public record CreatedInvitation(String code, Invitation invitation) {}
    public record InvitationPage(List<Invitation> records, long total, int current, int size) {}

    private static final String FIELDS = "id,note,createdBy,expiresAt,usedBy,usedAt,createTime,"
            + "CASE WHEN usedBy IS NOT NULL THEN 'USED' WHEN revokedAt IS NOT NULL THEN 'REVOKED' "
            + "WHEN expiresAt<=UTC_TIMESTAMP() THEN 'EXPIRED' ELSE 'AVAILABLE' END AS status";

    /** The row lock and user insert share the same transaction/DataSource. */
    @Transactional(rollbackFor = Exception.class)
    public long register(String account, String password, String confirmation, String code) {
        String normalized = code == null ? "" : code.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{32}")) throw invalidInvitation();
        List<Long> ids = jdbc.query("SELECT id FROM registration_invitation WHERE codeHash=? "
                        + "AND usedBy IS NULL AND revokedAt IS NULL AND expiresAt>UTC_TIMESTAMP() FOR UPDATE",
                (rs, row) -> rs.getLong("id"), hash(normalized));
        if (ids.isEmpty()) throw invalidInvitation();
        long userId = users.userRegister(account, password, confirmation);
        int updated = jdbc.update("UPDATE registration_invitation SET usedBy=?,usedAt=UTC_TIMESTAMP() "
                + "WHERE id=? AND usedBy IS NULL AND revokedAt IS NULL", userId, ids.get(0));
        if (updated != 1) throw invalidInvitation();
        return userId;
    }

    public CreatedInvitation create(long adminId, int validityHours, String note) {
        if (validityHours < 1 || validityHours > 720) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "有效期须为 1～720 小时");
        }
        String memo = note == null ? "" : note.trim();
        if (memo.length() > 120) throw new BusinessException(ErrorCode.PARAMS_ERROR, "备注最多 120 字");
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO registration_invitation "
                    + "(codeHash,note,createdBy,expiresAt,createTime) VALUES (?,?,?,"
                    + "DATE_ADD(UTC_TIMESTAMP(),INTERVAL ? HOUR),UTC_TIMESTAMP())", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, hash(code));
            statement.setString(2, memo);
            statement.setLong(3, adminId);
            statement.setInt(4, validityHours);
            return statement;
        }, key);
        Number id = key.getKey();
        if (id == null) throw new BusinessException(ErrorCode.SYSTEM_ERROR, "邀请码创建失败");
        return new CreatedInvitation(code, find(id.longValue()));
    }

    public InvitationPage list(int current, int size) {
        if (current < 1 || current > 100000 || size < 1 || size > 50) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "分页参数无效");
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM registration_invitation", Long.class);
        var records = jdbc.query("SELECT " + FIELDS + " FROM registration_invitation ORDER BY id DESC LIMIT ? OFFSET ?",
                (rs, row) -> map(rs), size, (current - 1) * size);
        return new InvitationPage(records, total == null ? 0 : total, current, size);
    }

    public boolean revoke(long id) {
        if (id <= 0) throw new BusinessException(ErrorCode.PARAMS_ERROR);
        if (jdbc.update("UPDATE registration_invitation SET revokedAt=UTC_TIMESTAMP() WHERE id=? "
                + "AND usedBy IS NULL AND revokedAt IS NULL AND expiresAt>UTC_TIMESTAMP()", id) != 1) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码已使用、撤销或过期，请刷新列表");
        }
        return true;
    }

    private Invitation find(long id) {
        return jdbc.queryForObject("SELECT " + FIELDS + " FROM registration_invitation WHERE id=?",
                (rs, row) -> map(rs), id);
    }

    private static Invitation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Invitation(rs.getLong("id"), rs.getString("note"), rs.getObject("createdBy", Long.class),
                utc(rs.getObject("expiresAt", LocalDateTime.class)), rs.getObject("usedBy", Long.class),
                utc(rs.getObject("usedAt", LocalDateTime.class)), utc(rs.getObject("createTime", LocalDateTime.class)), rs.getString("status"));
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static BusinessException invalidInvitation() {
        return new BusinessException(ErrorCode.PARAMS_ERROR, "邀请码无效、已使用、已撤销或已过期");
    }
}
