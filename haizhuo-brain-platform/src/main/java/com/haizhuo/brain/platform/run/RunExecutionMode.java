package com.haizhuo.brain.platform.run;

/** Product intent frozen for one root Run. Only DIRECT is enabled by the current vertical slice. */
public enum RunExecutionMode {
    DIRECT,
    COLLABORATIVE,
    AUTONOMOUS
}
