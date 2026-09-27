package com.haizhuo.brain.platform.harness;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.kernel.json.CanonicalJson;
import com.haizhuo.brain.platform.run.SessionRunStore;
import com.haizhuo.brain.platform.run.SessionTimelineItem;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 为平台会话创建并复用 Harness 绑定（规格 §16/§17）。
 *
 * <p>此处强制执行的规则：</p>
 * <ul>
 *   <li>按 (user, session, definitionVersion, generation=1) 先查后建 —— §16.1；</li>
 *   <li>键是不可猜测的 {@code hs_<uuid>} / {@code ws_<uuid>} —— §16.2，不做路径拼装；</li>
 *   <li>桥接快照每个绑定只构建一次，取自最近的用户可见时间线条目，
 *       并按 8 KB 做确定性 UTF-8 截断 —— §17.1/§17.3；不为桥接引入额外的 LLM 摘要工作流。</li>
 * </ul>
 */
public class SessionBridgeService {
    /** 规格 §17.3 —— 服务端的确定性截断上限。 */
    public static final int SUMMARY_LIMIT_BYTES = 8192;
    /** P0：取最近多少条用户可见的时间线条目参与摘要。 */
    public static final int RECENT_MESSAGE_LIMIT = 20;
    public static final int BRIDGE_SCHEMA_VERSION = 1;
    public static final int CURRENT_GENERATION = 1;

    private final SessionHarnessBindingRepository bindings;
    private final SessionBridgeSnapshotRepository snapshots;
    private final SessionRunStore runs;
    private final Clock clock;

    public SessionBridgeService(SessionHarnessBindingRepository bindings,
                                SessionBridgeSnapshotRepository snapshots,
                                SessionRunStore runs, Clock clock) {
        this.bindings = Objects.requireNonNull(bindings);
        this.snapshots = Objects.requireNonNull(snapshots);
        this.runs = Objects.requireNonNull(runs);
        this.clock = Objects.requireNonNull(clock);
    }

    /** §16.1：复用该作用域下已有的绑定，否则创建新绑定并生成一份新的桥接快照。 */
    public SessionHarnessBinding getOrCreateBinding(UserId userId, SessionId platformSessionId,
                                                    long employeeId, long definitionVersionId) {
        return bindings
                .findByScope(userId, platformSessionId, definitionVersionId, CURRENT_GENERATION)
                .orElseGet(() -> createBinding(userId, platformSessionId, employeeId, definitionVersionId));
    }

    private SessionHarnessBinding createBinding(UserId userId, SessionId platformSessionId,
                                                long employeeId, long definitionVersionId) {
        try {
            SessionBridgeSnapshot snapshot = snapshots.save(buildSnapshot(userId, platformSessionId, definitionVersionId));
            return bindings.save(new SessionHarnessBinding(0, userId, platformSessionId, employeeId,
                    definitionVersionId, CURRENT_GENERATION,
                    "hs_" + UUID.randomUUID(), "ws_" + UUID.randomUUID(),
                    snapshot.id(), clock.instant()));
        } catch (RuntimeException conflict) {
            // 并发 worker 先创建了同一作用域（唯一约束冲突）——直接采用它。
            return bindings.findByScope(userId, platformSessionId, definitionVersionId, CURRENT_GENERATION)
                    .orElseThrow(() -> conflict);
        }
    }

    private SessionBridgeSnapshot buildSnapshot(UserId userId, SessionId platformSessionId,
                                                long definitionVersionId) {
        List<SessionTimelineItem> recent = runs.findTimeline(platformSessionId, userId, RECENT_MESSAGE_LIMIT);
        StringBuilder summary = new StringBuilder();
        int maxSequence = 0;
        for (SessionTimelineItem item : recent) {
            summary.append('[').append(item.type()).append("] ").append(item.content()).append('\n');
            maxSequence = Math.max(maxSequence, item.sequenceNo());
        }
        String truncated = truncateUtf8(summary.toString(), SUMMARY_LIMIT_BYTES);
        String factsJson = "{}";
        String contentHash = CanonicalJson.sha256(Map.of("summary", truncated, "facts", factsJson));
        return new SessionBridgeSnapshot(0, userId, platformSessionId, definitionVersionId,
                CURRENT_GENERATION, maxSequence, truncated, factsJson, BRIDGE_SCHEMA_VERSION,
                contentHash, clock.instant());
    }

    /**
     * 确定性 UTF-8 截断：在 {@code maxBytes} 处切断，再丢弃尾部不完整的
     * 多字节序列。相同输入必然得到相同输出（§17.3）。
     */
    static String truncateUtf8(String text, int maxBytes) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) return text;
        int length = maxBytes;
        while (length > 0 && (bytes[length - 1] & 0xC0) == 0x80) {
            length--;
        }
        if (length > 0) {
            int lead = bytes[length - 1] & 0xFF;
            int expected = lead < 0x80 ? 1 : lead < 0xE0 ? 2 : lead < 0xF0 ? 3 : 4;
            if (expected > 1) {
                int continuation = 0;
                for (int i = length - 2; i >= 0 && (bytes[i] & 0xC0) == 0x80; i--) {
                    continuation++;
                }
                if (continuation < expected - 1) {
                    length -= continuation + 1;
                }
            }
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }
}
