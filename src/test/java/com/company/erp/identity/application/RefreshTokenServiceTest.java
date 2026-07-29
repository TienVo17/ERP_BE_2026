package com.company.erp.identity.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.company.erp.identity.domain.AppUser;
import com.company.erp.identity.infrastructure.IdentityJdbcRepository;
import com.company.erp.support.PostgresTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestConfiguration.class)
class RefreshTokenServiceTest {

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private IdentityJdbcRepository identityRepository;

    @Autowired
    private PasswordService passwordService;

    @Autowired
    private JdbcClient jdbc;

    private AppUser user;

    @BeforeEach
    void createUser() {
        UUID id = UUID.randomUUID();
        String loginId = "refresh-" + id;
        jdbc.sql("""
                        INSERT INTO identity.app_user (
                            id, kind, login_id, password_hash, position, name, status
                        ) VALUES (:id, 'USER', :loginId, :passwordHash, 'QA', 'Refresh User', 'ACTIVE')
                        """)
                .param("id", id)
                .param("loginId", loginId)
                .param("passwordHash", passwordService.encode("refresh test password"))
                .update();
        user = identityRepository.findById(id).orElseThrow();
    }

    @Test
    void rotatesOpaqueTokenAndPersistsOnlySha256Hashes() {
        var issued = refreshTokenService.createSession(user, "192.0.2.20", "unit-test-agent");

        assertThat(issued.rawRefreshToken()).isNotBlank();
        assertThat(jdbc.sql("SELECT octet_length(token_hash) FROM identity.refresh_token WHERE auth_session_id = :id")
                .param("id", issued.session().id())
                .query(Integer.class)
                .single()).isEqualTo(32);
        assertThat(jdbc.sql("SELECT encode(token_hash, 'hex') FROM identity.refresh_token WHERE auth_session_id = :id")
                .param("id", issued.session().id())
                .query(String.class)
                .single()).doesNotContain(issued.rawRefreshToken());

        var rotated = refreshTokenService.rotate(issued.rawRefreshToken());

        assertThat(rotated).isInstanceOf(RefreshTokenService.RotationSucceeded.class);
        var success = (RefreshTokenService.RotationSucceeded) rotated;
        assertThat(success.rawRefreshToken()).isNotEqualTo(issued.rawRefreshToken());
        assertThat(jdbc.sql("SELECT status FROM identity.refresh_token WHERE id = :id")
                .param("id", issued.refreshTokenId())
                .query(String.class)
                .single()).isEqualTo("USED");
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE parent_token_id = :id AND status = 'ACTIVE'")
                .param("id", issued.refreshTokenId())
                .query(Integer.class)
                .single()).isOne();
    }

    @Test
    void replayOfUsedTokenFailsClosedAndCommitsFamilyRevocation() {
        var issued = refreshTokenService.createSession(user, "192.0.2.21", "replay-test-agent");
        assertThat(refreshTokenService.rotate(issued.rawRefreshToken()))
                .isInstanceOf(RefreshTokenService.RotationSucceeded.class);

        var replay = refreshTokenService.rotate(issued.rawRefreshToken());

        assertThat(replay).isInstanceOf(RefreshTokenService.ReauthenticationRequired.class);
        assertThat(jdbc.sql("SELECT revoked_at FROM identity.auth_session WHERE id = :id")
                .param("id", issued.session().id())
                .query(OffsetDateTime.class)
                .single()).isBeforeOrEqualTo(OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC));
        assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE auth_session_id = :id AND status = 'ACTIVE'")
                .param("id", issued.session().id())
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    void concurrentRefreshAllowsOneRotationThenRevokesTheFamily() throws Exception {
        var issued = refreshTokenService.createSession(user, "192.0.2.23", "concurrent-test-agent");
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<java.util.concurrent.Future<RefreshTokenService.RotationResult>> futures = List.of(
                    executor.submit(() -> rotateAfterBarrier(barrier, issued.rawRefreshToken())),
                    executor.submit(() -> rotateAfterBarrier(barrier, issued.rawRefreshToken())));

            List<RefreshTokenService.RotationResult> results = futures.stream()
                    .map(RefreshTokenServiceTest::await)
                    .toList();

            assertThat(results).filteredOn(RefreshTokenService.RotationSucceeded.class::isInstance).hasSize(1);
            assertThat(results).filteredOn(RefreshTokenService.ReauthenticationRequired.class::isInstance).hasSize(1);
            assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM identity.auth_session WHERE id = :id")
                    .param("id", issued.session().id())
                    .query(Boolean.class)
                    .single()).isTrue();
            assertThat(jdbc.sql("SELECT count(*) FROM identity.refresh_token WHERE auth_session_id = :id AND status = 'ACTIVE'")
                    .param("id", issued.session().id())
                    .query(Integer.class)
                    .single()).isZero();
        }
    }

    @Test
    void successfulRefreshAdvancesIdleDeadlineWithoutExtendingAbsoluteLifetime() {
        var issued = refreshTokenService.createSession(user, "192.0.2.22", "expiry-test-agent");
        var rotated = (RefreshTokenService.RotationSucceeded) refreshTokenService.rotate(issued.rawRefreshToken());

        assertThat(rotated.session().absoluteExpiresAt()).isEqualTo(issued.session().absoluteExpiresAt());
        assertThat(rotated.session().idleExpiresAt()).isAfterOrEqualTo(issued.session().idleExpiresAt());
        assertThat(rotated.session().idleExpiresAt()).isBeforeOrEqualTo(rotated.session().absoluteExpiresAt());
    }

    @Test
    void revokeAllSessionsEndsEveryLiveSessionWithoutTouchingHistoricalRows() {
        var firstLive = refreshTokenService.createSession(user, "192.0.2.24", "device-one");
        var secondLive = refreshTokenService.createSession(user, "192.0.2.25", "device-two");
        var alreadyEnded = refreshTokenService.createSession(user, "192.0.2.26", "device-three");
        identityRepository.revokeOwnedSession(
                alreadyEnded.session().id(), user.id(), Instant.now(), "USER_REVOKED");

        identityRepository.revokeAllSessions(user.id(), Instant.now(), "PASSWORD_CHANGED");

        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM identity.auth_session
                        WHERE user_id = :userId AND revoked_at IS NULL
                        """)
                .param("userId", user.id())
                .query(Integer.class)
                .single()).isZero();
        assertThat(jdbc.sql("""
                        SELECT count(*)
                        FROM identity.refresh_token rt
                        JOIN identity.auth_session s ON s.id = rt.auth_session_id
                        WHERE s.user_id = :userId AND rt.status = 'ACTIVE'
                        """)
                .param("userId", user.id())
                .query(Integer.class)
                .single()).isZero();
        // The earlier revocation reason must survive; revoke-all only touches still-live rows.
        assertThat(jdbc.sql("SELECT revoked_reason FROM identity.auth_session WHERE id = :id")
                .param("id", alreadyEnded.session().id())
                .query(String.class)
                .single()).isEqualTo("USER_REVOKED");
        assertThat(jdbc.sql("SELECT revoked_reason FROM identity.auth_session WHERE id IN (:first, :second)")
                .param("first", firstLive.session().id())
                .param("second", secondLive.session().id())
                .query(String.class)
                .list()).containsExactly("PASSWORD_CHANGED", "PASSWORD_CHANGED");
    }

    private RefreshTokenService.RotationResult rotateAfterBarrier(
            CyclicBarrier barrier,
            String rawRefreshToken) throws Exception {
        barrier.await(5, TimeUnit.SECONDS);
        return refreshTokenService.rotate(rawRefreshToken);
    }

    private static RefreshTokenService.RotationResult await(
            java.util.concurrent.Future<RefreshTokenService.RotationResult> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("Concurrent refresh did not complete", exception);
        }
    }
}
