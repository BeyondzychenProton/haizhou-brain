package com.haizhuo.brain.platform.harness;

import java.util.Optional;

/** 桥接快照的持久化边界。快照只允许插入（规格 §17.1）。 */
public interface SessionBridgeSnapshotRepository {

    Optional<SessionBridgeSnapshot> findById(long id);

    SessionBridgeSnapshot save(SessionBridgeSnapshot snapshot);
}
