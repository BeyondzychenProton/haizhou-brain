package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.platform.channel.ChannelDeliveryWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 出站投递调度器（P3 补全）：按节奏认领并发送待投递的渠道答复。
 *
 * <p>与 {@code RunWorker} 同构——本类不含任何业务逻辑，认领互斥、失败分级与状态落库
 * 都在 {@link ChannelDeliveryWorker} 与 outbox 里，因此天然支持多实例并发（同一条投递
 * 只会被一个实例的认领翻转成功）。</p>
 *
 * <p>默认关闭：在没有真实 sender 的情况下开启调度，会把每条待投递记成永久失败。</p>
 */
@Component
@ConditionalOnProperty(prefix = "haizhuo.brain.channel-worker", name = "enabled", havingValue = "true")
class ChannelDeliveryDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ChannelDeliveryDispatcher.class);

    private final ChannelDeliveryWorker worker;
    private final int batchSize;

    ChannelDeliveryDispatcher(ChannelDeliveryWorker worker,
                              @Value("${haizhuo.brain.channel-worker.batch-size:20}") int batchSize) {
        this.worker = worker;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${haizhuo.brain.channel-worker.poll-delay-ms:1000}")
    void poll() {
        try {
            int delivered = worker.deliverBatch(batchSize);
            if (delivered > 0) {
                log.info("channel.delivery.dispatched count={}", delivered);
            }
        } catch (RuntimeException error) {
            // 单拍失败不终止调度；异常类型足以定位，不记录响应正文。
            log.warn("channel.delivery.dispatch failed errorType={}", error.getClass().getSimpleName());
        }
    }
}
