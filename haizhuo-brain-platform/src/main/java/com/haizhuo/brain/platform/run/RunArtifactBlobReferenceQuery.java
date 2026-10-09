package com.haizhuo.brain.platform.run;

/** 删除不可变成果物 Blob 前使用的精确引用检查，不依赖成果物状态。 */
public interface RunArtifactBlobReferenceQuery {
    boolean isBlobRefReferenced(String blobRef);
}
