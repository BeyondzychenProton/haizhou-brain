package com.haizhuo.brain.platform.run;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.Optional;

/** RunSpec 是 Run 级不可变事实；只允许插入，永不更新（规格 §6.3）。 */
public interface HarnessRunSpecRepository {
    HarnessRunSpec save(HarnessRunSpec spec);

    Optional<HarnessRunSpec> findByRunId(RunId runId);
}
