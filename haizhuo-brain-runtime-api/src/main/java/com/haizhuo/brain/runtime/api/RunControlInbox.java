package com.haizhuo.brain.runtime.api;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.List;

/** 持久化控制通道；运行时只在框架定义的安全检查点上读取。 */
public interface RunControlInbox {
    List<RunGuidanceMessage> consumeGuidance(RunId runId);

    boolean isCancellationRequested(RunId runId);
}
