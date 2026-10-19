package com.nemo.OJbackenduserservice.service;

import com.nemo.OJbackendcommon.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RegistrationInvitationServiceTest {
    private JdbcTemplate jdbc;
    private UserService users;
    private RegistrationInvitationService service;
    private static final String CODE = "Abcdefghijklmnopqrstuv01234567_-";

    @Test void preservesLargeIdsAcrossBrowserJsonRoundTrip() throws Exception {
        long id = 2105117919181234177L;
        var invitation = new RegistrationInvitationService.Invitation(1L, "", id, null, id, null, null, "USED");
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(invitation));
        assertTrue(json.get("id").isTextual());
        assertTrue(json.get("usedBy").isTextual());
        assertEquals(Long.toString(id), json.get("createdBy").asText());
        assertEquals(Long.toString(id), json.get("usedBy").asText());
    }

    @BeforeEach void setup() {
        jdbc = mock(JdbcTemplate.class); users = mock(UserService.class);
        service = new RegistrationInvitationService(jdbc, users);
    }

    @Test void rejectsAbsentOrMalformedCodeBeforeRegistration() {
        for (String code : new String[]{null, "", "short", "!".repeat(32)}) {
            assertThrows(BusinessException.class, () -> service.register("user", "password", "password", code));
        }
        verifyNoInteractions(jdbc, users);
    }

    @Test void rejectsUnavailableCodeWithoutCreatingAccount() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), anyString())).thenReturn(List.of());
        assertThrows(BusinessException.class, () -> service.register("user", "password", "password", CODE));
        verifyNoInteractions(users);
        verify(jdbc, never()).update(anyString(), anyLong(), anyLong());
    }

    @Test void locksAndConsumesOnlyAfterSuccessfulRegistration() {
        when(jdbc.query(contains("FOR UPDATE"), ArgumentMatchers.<RowMapper<Long>>any(), eq(RegistrationInvitationService.hash(CODE))))
                .thenReturn(List.of(7L));
        when(users.userRegister("user", "password", "password")).thenReturn(99L);
        when(jdbc.update(anyString(), eq(99L), eq(7L))).thenReturn(1);
        assertEquals(99L, service.register("user", "password", "password", " " + CODE + " "));
        var order = inOrder(jdbc, users);
        order.verify(jdbc).query(contains("FOR UPDATE"), ArgumentMatchers.<RowMapper<Long>>any(), anyString());
        order.verify(users).userRegister("user", "password", "password");
        order.verify(jdbc).update(anyString(), eq(99L), eq(7L));
    }

    @Test void failedAccountCreationDoesNotConsumeInvitation() {
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<Long>>any(), anyString())).thenReturn(List.of(7L));
        when(users.userRegister(anyString(), anyString(), anyString())).thenThrow(new BusinessException(40000, "账号重复"));
        assertThrows(BusinessException.class, () -> service.register("user", "password", "password", CODE));
        verify(jdbc, never()).update(anyString(), anyLong(), anyLong());
    }

    @Test void boundsExpiryAndNoteAndPagination() {
        assertThrows(BusinessException.class, () -> service.create(1L, 0, ""));
        assertThrows(BusinessException.class, () -> service.create(1L, 721, ""));
        assertThrows(BusinessException.class, () -> service.create(1L, 24, "x".repeat(121)));
        assertThrows(BusinessException.class, () -> service.list(0, 20));
        assertThrows(BusinessException.class, () -> service.list(1, 51));
        verifyNoInteractions(jdbc);
    }
}
