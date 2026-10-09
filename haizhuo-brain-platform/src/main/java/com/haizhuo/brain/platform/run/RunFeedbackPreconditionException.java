package com.haizhuo.brain.platform.run;

/** 只有存在完整、成功且对用户可见的根结果后，才允许提交反馈。 */
public final class RunFeedbackPreconditionException extends RuntimeException {
    public RunFeedbackPreconditionException() { super("feedback requires a successful run with a complete result"); }
}
