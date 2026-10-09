package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.runtime.api.event.AgentEventDescriptor;
import java.time.Instant;
import java.util.Objects;

/** 仅用于界面展示的有界文本批处理器，状态限定在单次 attempt 内。 */
final class RootTextBatchAccumulator {
    private static final int MAX_BATCH_BYTES = 8 * 1024;

    @FunctionalInterface
    interface Writer {
        void write(AgentEventDescriptor descriptor, long streamOffset, long fromOffset, long toOffset,
                   String text, Instant occurredAt);
    }

    private final Writer writer;
    private final StringBuilder text = new StringBuilder();
    private AgentEventDescriptor descriptor;
    private long streamOffset;
    private long fromOffset;
    private long toOffset;
    private int utf8Bytes;

    RootTextBatchAccumulator(Writer writer) { this.writer = Objects.requireNonNull(writer); }

    void append(AgentEventDescriptor nextDescriptor, long nextStreamOffset, long startOffset,
                String value, Instant occurredAt) {
        if (nextDescriptor == null || value == null || value.isEmpty()) return;
        if (text.length() > 0 && (!sameIdentity(descriptor, nextDescriptor) || toOffset != startOffset))
            flush(occurredAt);

        long offset = startOffset;
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            int chars = Character.charCount(codePoint);
            int bytes = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (text.length() > 0 && utf8Bytes + bytes > MAX_BATCH_BYTES) flush(occurredAt);
            if (text.length() == 0) {
                descriptor = nextDescriptor;
                fromOffset = offset;
                toOffset = offset;
            }
            text.append(value, index, index + chars);
            utf8Bytes += bytes;
            toOffset++;
            streamOffset = nextStreamOffset;
            index += chars;
            offset++;
        }
    }

    void flush(Instant occurredAt) {
        if (text.length() == 0) return;
        AgentEventDescriptor current = descriptor;
        long currentStreamOffset = streamOffset;
        long currentFrom = fromOffset;
        long currentTo = toOffset;
        String currentText = text.toString();
        text.setLength(0);
        descriptor = null;
        streamOffset = 0;
        fromOffset = 0;
        toOffset = 0;
        utf8Bytes = 0;
        writer.write(current, currentStreamOffset, currentFrom, currentTo, currentText, occurredAt);
    }

    private static boolean sameIdentity(AgentEventDescriptor left, AgentEventDescriptor right) {
        return left != null && right != null
                && left.executionRole() == right.executionRole()
                && Objects.equals(left.fenceToken(), right.fenceToken())
                && Objects.equals(left.replyId(), right.replyId())
                && Objects.equals(left.blockId(), right.blockId());
    }
}
