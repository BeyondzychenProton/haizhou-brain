package com.haizhuo.brain.api.session;

import com.haizhuo.brain.kernel.identity.SessionId;
import com.haizhuo.brain.platform.run.SessionRenderView;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** 只读首屏与历史视图；此接口不会访问 AgentRuntime 或 Worker 队列。 */
@RestController
@RequestMapping("/api/v1/sessions")
public final class SessionRenderViewController {
    private final SessionRenderViewService views;
    private final boolean enabled;

    public SessionRenderViewController(SessionRenderViewService views,
                                       @Value("${haizhuo.brain.session-render-v3.enabled:false}") boolean enabled) {
        this.views = views;
        this.enabled = enabled;
    }

    @GetMapping("/{sessionId}/view")
    public Mono<ViewResponse> view(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable String sessionId,
                                  @RequestParam(required = false) Integer limit,
                                  @RequestParam(required = false) String cursor) {
        if (!enabled) return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND));
        return RunStreamController.blocking(() -> {
            SessionRenderView view = views.view(new SessionId(sessionId), user.userId(), limit, cursor);
            return new ViewResponse(3, view.sessionId(), view.runs(), view.items(), view.resultRefs(),
                    view.snapshotCursor(), view.cursorFloor(), view.renderCursor(), view.renderCursorFloor(),
                    view.draftRecoveryStatus(), view.nextCursor(), view.hasMore());
        });
    }

    public record ViewResponse(int schemaVersion, String sessionId,
                               java.util.List<com.haizhuo.brain.platform.run.SessionRenderRun> runs,
                               java.util.List<com.haizhuo.brain.platform.run.SessionRenderMessage> items,
                               java.util.List<SessionRenderView.ResultReference> resultRefs,
                               long snapshotCursor, long cursorFloor, long renderCursor,
                               long renderCursorFloor, String draftRecoveryStatus,
                               String nextCursor, boolean hasMore) { }
}
