package com.haizhuo.brain.observability;

import com.haizhuo.brain.kernel.identity.RunId;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 面向平台 API 的最小评测写入口；Run 所有权由 API 层先校验。 */
@Component
public class LangfuseEvaluationService {
    private static final Pattern SCORE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final LangfuseObservationRecorder recorder;

    public LangfuseEvaluationService(LangfuseObservationRecorder recorder) {
        this.recorder = Objects.requireNonNull(recorder);
    }

    public boolean submitNumeric(RunId runId, String observationId, String name,
                                 double value, String comment) {
        Objects.requireNonNull(runId);
        if (name == null || !SCORE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("score name must match [A-Za-z0-9._-]{1,64}");
        }
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("numeric score must be between 0 and 1");
        }
        return recorder.score(LangfuseTraceIds.forRun(runId), observationId, name, value,
                "NUMERIC", comment);
    }

    public String traceId(RunId runId) {
        return LangfuseTraceIds.forRun(runId);
    }
}
