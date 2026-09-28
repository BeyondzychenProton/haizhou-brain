package com.haizhuo.brain.platform.run;

/** 事件可见性由平台在生成时标注，出口再按当前主体与渠道策略过滤，不能由模型或渠道原文指定。 */
public enum EventVisibility {
    /** 面向提出该请求的普通用户。 */
    USER,
    /** 仅供排障/管理角色查看的中间过程，不进入普通用户流与渠道投影。 */
    INTERNAL
}
