package myfluxo.adapter.http.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthRateLimiterTest {

    private static final String IP_A = "10.0.0.1";
    private static final String IP_B = "10.0.0.2";

    @Test
    void login_allowsUpToCapacity_thenDenies() {
        var limiter = new AuthRateLimiter();

        for (int i = 0; i < AuthRateLimiter.LOGIN_CAPACITY; i++) {
            assertThat(limiter.allowLogin(IP_A))
                .as("attempt %d should be allowed", i + 1)
                .isTrue();
        }
        // One more — over the bucket capacity.
        assertThat(limiter.allowLogin(IP_A))
            .as("attempt %d must be rate-limited", AuthRateLimiter.LOGIN_CAPACITY + 1)
            .isFalse();
    }

    @Test
    void login_perIpBucket_isolatesDifferentCallers() {
        var limiter = new AuthRateLimiter();

        // Exhaust IP A.
        for (int i = 0; i < AuthRateLimiter.LOGIN_CAPACITY; i++) {
            limiter.allowLogin(IP_A);
        }
        assertThat(limiter.allowLogin(IP_A)).isFalse();

        // IP B is unaffected.
        assertThat(limiter.allowLogin(IP_B)).isTrue();
    }

    @Test
    void register_allowsUpToCapacity_thenDenies() {
        var limiter = new AuthRateLimiter();

        for (int i = 0; i < AuthRateLimiter.REGISTER_CAPACITY; i++) {
            assertThat(limiter.allowRegister(IP_A)).isTrue();
        }
        assertThat(limiter.allowRegister(IP_A)).isFalse();
    }

    @Test
    void registerAndLogin_haveIndependentBuckets() {
        // Exhausting login bucket must NOT exhaust register bucket and
        // vice versa — different threat models, different limits.
        var limiter = new AuthRateLimiter();

        for (int i = 0; i < AuthRateLimiter.LOGIN_CAPACITY; i++) {
            limiter.allowLogin(IP_A);
        }
        assertThat(limiter.allowLogin(IP_A)).isFalse();

        // Register from the same IP still allowed.
        assertThat(limiter.allowRegister(IP_A)).isTrue();
    }

    @Test
    void nullIp_treatedAsSharedBucket() {
        // Unknown caller → strict shared bucket. After capacity, deny.
        var limiter = new AuthRateLimiter();

        for (int i = 0; i < AuthRateLimiter.LOGIN_CAPACITY; i++) {
            assertThat(limiter.allowLogin(null)).isTrue();
        }
        assertThat(limiter.allowLogin(null)).isFalse();
    }

    @Test
    void blankIp_treatedAsSharedBucket() {
        var limiter = new AuthRateLimiter();

        for (int i = 0; i < AuthRateLimiter.LOGIN_CAPACITY; i++) {
            assertThat(limiter.allowLogin("")).isTrue();
        }
        assertThat(limiter.allowLogin("   ")).isFalse();
    }
}
