package com.haizhuo.brain.platform.tool;

import com.haizhuo.brain.platform.run.AgentRun;

/**
 * 平台侧的持久化工具执行边界（规格 §36）。Harness 只负责挂起；
 * 这里是唯一一处对企业动作进行授权与执行的地方（I-06/I-07）。
 * 授权始终针对冻结的 Run 属主进行，绝不依据调用方或模型提供的身份（I-14）。
 */
public interface ToolExecutionGatewayService {

    /** 执行固定授权流水线（规格 §37）；业务拒绝不抛异常。 */
    ToolPreparation prepare(PlatformToolExecution execution, AgentRun run);

    /** 在 prepare 成功之后执行；执行器失败会映射为安全结果。 */
    ToolExecutionResult execute(PlatformToolExecution execution, AgentRun run, ToolPreparation preparation);
}
