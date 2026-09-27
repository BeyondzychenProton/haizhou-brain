package com.haizhuo.brain.infrastructure.identity.ratelimit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.security.identity.AuthenticationRejectedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class RedisAuthenticationRateLimiterTest {
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> values;

    @Test
    void rejectsTheSixthFailedAttemptWithoutPuttingMobileInRedisKey() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn("5");
        RedisAuthenticationRateLimiter limiter = new RedisAuthenticationRateLimiter(redis);

        assertThrows(AuthenticationRejectedException.class, () -> limiter.checkLoginAllowed("13812345678", "10.0.0.8"));

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(values).get(key.capture());
        assertFalse(key.getValue().contains("13812345678"));
        assertFalse(key.getValue().contains("10.0.0.8"));
    }
}
