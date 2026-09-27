package com.haizhuo.brain.platform.run;

/** Immutable worker snapshot, built from persisted Run, published definition and first input. */
public record ClaimedRun(AgentRun run, String employeeName, String instructions, String modelProvider, String modelName, String input) { }
