package com.haizhuo.brain.infrastructure.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haizhuo.brain.platform.channel.ChannelManagementCommandExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 将渠道管理审计和幂等回执与账号/身份变更原子写入。 */
@Repository
public class JdbcChannelManagementCommandExecutor implements ChannelManagementCommandExecutor {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcChannelManagementCommandExecutor(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                                ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.json = json;
        this.clock = clock;
    }

    @Override
    public <T> T execute(Command command, Class<T> responseType, Supplier<T> action) {
        validate(command);
        return Objects.requireNonNull(tx.execute(status -> {
            String requestHash = hash(command.payloadCanonical());
            Receipt receipt = command.requestId() == null ? null : findOrInsertReceipt(command, requestHash);
            if (receipt != null && !matches(receipt, command, requestHash)) {
                throw new IllegalStateException("STATE_CONFLICT");
            }
            if (receipt != null && receipt.responseJson() != null) {
                try {
                    return json.readValue(receipt.responseJson(), responseType);
                } catch (Exception invalid) {
                    throw new IllegalStateException("CHANNEL_COMMAND_RECEIPT_INVALID", invalid);
                }
            }

            T result = action.get();
            appendAudit(command, requestHash);
            if (command.requestId() != null) {
                try {
                    jdbc.update("UPDATE platform_channel_management_receipt SET result_json=? "
                                    + "WHERE actor_user_id=? AND request_id=?",
                            json.writeValueAsString(result), command.actorUserId(), command.requestId());
                } catch (Exception invalid) {
                    throw new IllegalStateException("CHANNEL_COMMAND_RECEIPT_WRITE_FAILED", invalid);
                }
            }
            return result;
        }), "Channel command transaction did not return a result");
    }

    private Receipt findOrInsertReceipt(Command command, String requestHash) {
        jdbc.update("INSERT INTO platform_channel_management_receipt(actor_user_id,request_id,action_code,"
                        + "binding_id,external_user_id,payload_hash,result_json,created_at) "
                        + "VALUES(?,?,?,?,?,?,NULL,?) ON DUPLICATE KEY UPDATE request_id=VALUES(request_id)",
                command.actorUserId(), command.requestId(), command.action(), command.bindingId(),
                command.externalUserId(), requestHash, Timestamp.from(clock.instant()));
        List<Receipt> receipts = jdbc.query("SELECT action_code,binding_id,external_user_id,payload_hash,result_json "
                        + "FROM platform_channel_management_receipt WHERE actor_user_id=? AND request_id=? FOR UPDATE",
                (rs, row) -> new Receipt(rs.getString("action_code"), rs.getString("binding_id"),
                        rs.getString("external_user_id"), rs.getString("payload_hash"), rs.getString("result_json")),
                command.actorUserId(), command.requestId());
        if (receipts.isEmpty()) throw new IllegalStateException("CHANNEL_COMMAND_RECEIPT_UNAVAILABLE");
        return receipts.get(0);
    }

    private void appendAudit(Command command, String requestHash) {
        jdbc.update("INSERT INTO platform_channel_management_audit(binding_id,external_user_id,action_code,"
                        + "actor_user_id,request_id,reason,client_source,payload_hash,safe_changes,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)",
                command.bindingId(), command.externalUserId(), command.action(), command.actorUserId(),
                command.requestId(), command.reason(), command.clientSource(), requestHash,
                command.safeChanges(), Timestamp.from(clock.instant()));
    }

    private static boolean matches(Receipt receipt, Command command, String requestHash) {
        return command.action().equals(receipt.action())
                && Objects.equals(command.bindingId(), receipt.bindingId())
                && Objects.equals(command.externalUserId(), receipt.externalUserId())
                && requestHash.equals(receipt.payloadHash());
    }

    private static String hash(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validate(Command command) {
        if (command == null || command.actorUserId() <= 0 || blank(command.action())
                || blank(command.bindingId()) || blank(command.clientSource()) || blank(command.payloadCanonical())
                || command.payloadCanonical().length() > 16_384
                || command.safeChanges() == null || command.safeChanges().length() > 4_000) {
            throw new IllegalArgumentException("Invalid channel management command");
        }
        boolean hasRequest = command.requestId() != null && !command.requestId().isBlank();
        boolean hasReason = command.reason() != null && !command.reason().isBlank();
        if (hasRequest != hasReason || hasRequest && (command.requestId().length() > 128
                || command.reason().length() > 500)) {
            throw new IllegalArgumentException("requestId and reason must be supplied together");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record Receipt(String action, String bindingId, String externalUserId,
                           String payloadHash, String responseJson) {
    }
}
