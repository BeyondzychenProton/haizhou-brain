package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.RunId;
import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/** 完整结果与快照读取；登录属主校验先于结果读取。 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionRecoveryController {
    private final SessionApplicationService sessions;
    private final AgentResultRepository results;

    public SessionRecoveryController(SessionApplicationService sessions, AgentResultRepository results) {
        this.sessions = sessions;
        this.results = results;
    }

    @GetMapping("/runs/{runId}/result")
    public Mono<ResultResponse> result(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String runId) {
        return RunStreamController.blocking(() -> {
            RunId id = new RunId(runId);
            sessions.getRun(id, user.userId());
            var result = results.findRoot(id, user.userId())
                    .orElseThrow(() -> new IllegalArgumentException("Run result was not found"));
            return new ResultResponse(result.resultId(), id.value(), result.mediaType(), result.body(),
                    result.bodySha256(), result.byteSize(), result.schemaVersion(), result.createdAt(), result.legacySummary(),
                    result.executorRoleId(), result.executorEmployeeId(), result.executorDefinitionVersionId());
        });
    }

    @GetMapping("/{sessionId}/results")
    public Mono<List<ReferenceableResultResponse>> referenceableResults(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String sessionId) {
        return RunStreamController.blocking(() -> sessions.referenceableResults(new SessionId(sessionId), user.userId())
                .stream().map(result -> new ReferenceableResultResponse(result.resultId(), result.runId().value(),
                        result.kind(), result.mediaType(), result.bodySha256(), result.byteSize(), result.createdAt(),
                        result.executorRoleId(), result.executorEmployeeId(), result.executorDefinitionVersionId()))
                .toList());
    }

    @GetMapping("/{sessionId}/snapshot")
    public Mono<SnapshotResponse> snapshot(@AuthenticationPrincipal AuthenticatedUser user,
                                          @PathVariable String sessionId,
                                          @RequestParam(defaultValue = "200") int limit,
                                          @RequestParam(defaultValue = "v1") String format) {
        boolean v2 = StreamEvent.isV2(format);
        return RunStreamController.blocking(() -> {
            var snapshot = sessions.snapshot(new SessionId(sessionId), user.userId(), limit);
            return new SnapshotResponse(snapshot.session().id().value(),
                    snapshot.runs().stream().map(run -> {
                        var target = sessions.executionTarget(run, user.userId());
                        return new SessionController.RunResponse(run.id().value(), run.sessionId().value(),
                                run.state().name(), run.definitionVersionId(), run.createdAt(),
                                sessions.queuePosition(run.id(), user.userId()), target.roleId(), target.employeeId(),
                                target.definitionVersionId(), target.mode().name());
                    }).toList(),
                    snapshot.events().stream().map(event -> SessionStreamController.envelope(event, v2)).toList(),
                    snapshot.snapshotCursor(), snapshot.cursorFloor());
        });
    }

    public record ResultResponse(String resultId, String runId, String mediaType, String body,
                                 String bodySha256, long byteSize, int schemaVersion, Instant createdAt,
                                 boolean legacySummary, String executorRoleId, Long executorEmployeeId,
                                 Long executorDefinitionVersionId) { }
    public record ReferenceableResultResponse(String resultId, String runId, String kind, String mediaType,
                                               String bodySha256, long byteSize, Instant createdAt,
                                               String executorRoleId, Long executorEmployeeId,
                                               Long executorDefinitionVersionId) { }
    public record SnapshotResponse(String sessionId, List<SessionController.RunResponse> runs,
                                   List<StreamEvent> events, long snapshotCursor, long cursorFloor) { }
}
