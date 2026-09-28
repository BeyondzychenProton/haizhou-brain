package com.haizhuo.brain.infrastructure.mcp;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.mcp.McpConnection;
import com.haizhuo.brain.platform.mcp.McpUserTokenProvider;
import com.haizhuo.brain.security.identity.port.PlatformIdentityRepository;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 仅供测试的身份桥接；未提供密钥时绝不启用。 */
public class SimulatorMcpUserTokenProvider implements McpUserTokenProvider {
    private final PlatformIdentityRepository identities;
    private final byte[] secret;
    private final Clock clock;

    public SimulatorMcpUserTokenProvider(PlatformIdentityRepository identities, String secret, Clock clock) {
        if (secret == null || secret.length() < 32)
            throw new IllegalArgumentException("MCP simulator secret must have at least 32 characters");
        this.identities = identities;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    @Override public String tokenFor(UserId userId, McpConnection connection) {
        var user = identities.findById(userId)
                .orElseThrow(() -> new IllegalStateException("MCP user was not found"));
        if (!user.isActive()) throw new IllegalStateException("MCP user is not active");
        String mobile = user.mobileNormalized();
        if (mobile.contains("|") || connection.code().contains("|"))
            throw new IllegalArgumentException("Invalid simulator identity");
        String payload = mobile + "|" + connection.code() + "|"
                + clock.instant().plus(Duration.ofMinutes(5)).getEpochSecond();
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            return encoder.encodeToString(bytes) + "." + encoder.encodeToString(mac.doFinal(bytes));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("MCP simulator signing is unavailable", error);
        }
    }
}
