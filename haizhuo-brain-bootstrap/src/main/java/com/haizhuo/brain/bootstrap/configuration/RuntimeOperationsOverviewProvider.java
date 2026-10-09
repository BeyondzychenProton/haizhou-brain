package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.api.admin.OperationsOverviewProvider;
import com.haizhuo.brain.observability.LangfuseProperties;
import java.time.Clock;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.core.env.Environment;

/** 根据本进程实际加载的 Bean 和部署开关生成运维快照。 */
final class RuntimeOperationsOverviewProvider implements OperationsOverviewProvider {
    private static final String AGENTSCOPE_VERSION = "2.0.3";
    private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final Environment environment;
    private final boolean runtimeLoaded;
    private final boolean runWorkerLoaded;
    private final boolean channelWorkerLoaded;
    private final boolean openTelemetryLoaded;
    private final LangfuseProperties langfuse;
    private final Clock clock;

    RuntimeOperationsOverviewProvider(Environment environment, boolean runtimeLoaded,
                                      boolean runWorkerLoaded, boolean channelWorkerLoaded,
                                      boolean openTelemetryLoaded, LangfuseProperties langfuse,
                                      Clock clock) {
        this.environment = environment;
        this.runtimeLoaded = runtimeLoaded;
        this.runWorkerLoaded = runWorkerLoaded;
        this.channelWorkerLoaded = channelWorkerLoaded;
        this.openTelemetryLoaded = openTelemetryLoaded;
        this.langfuse = langfuse;
        this.clock = clock;
    }

    @Override
    public OverviewResponse overview() {
        String instanceLabel = safeLabel(environment.getProperty("haizhuo.brain.instance-label"));
        boolean runWorkerEnabled = enabled("haizhuo.brain.run-worker.enabled");
        boolean channelWorkerEnabled = enabled("haizhuo.brain.channel-worker.enabled");
        Map<String, Boolean> gates = new LinkedHashMap<>();
        gates.put("run-worker", runWorkerEnabled);
        gates.put("channel-worker", channelWorkerEnabled);
        gates.put("session-render-v3", enabled("haizhuo.brain.session-render-v3.enabled"));

        List<WorkerSummary> workers = List.of(
                new WorkerSummary("RUN", runWorkerEnabled, runWorkerLoaded, instanceLabel,
                        positiveLong("haizhuo.brain.run-worker.lease-ttl-seconds", 120L),
                        positiveLong("haizhuo.brain.run-worker.reclaim-every-ticks", 10L), null),
                new WorkerSummary("CHANNEL_DELIVERY", channelWorkerEnabled, channelWorkerLoaded,
                        instanceLabel, null, null, null));

        // 当前没有主动传输探针；配置已启用或 SDK 已加载都不能证明服务健康。
        String probeStatus = langfuse.isEnabled() ? "UNKNOWN" : "NOT_CONFIGURED";
        ObservabilitySummary observability = new ObservabilitySummary(langfuse.isEnabled(),
                openTelemetryLoaded, langfuse.captureContentEnabled(), null, probeStatus,
                null, null);

        return new OverviewResponse(clock.instant(), instanceLabel,
                new RuntimeSummary(AGENTSCOPE_VERSION, runtimeLoaded, gates), workers,
                observability, protectedLinks());
    }

    private boolean enabled(String key) {
        return environment.getProperty(key, Boolean.class, false);
    }

    private Long positiveLong(String key, long defaultValue) {
        Long value = environment.getProperty(key, Long.class, defaultValue);
        return value != null && value > 0 ? value : defaultValue;
    }

    private List<ProtectedLink> protectedLinks() {
        List<ProtectedLink> links = new ArrayList<>(3);
        addLink(links, "logs", "日志平台");
        addLink(links, "metrics", "指标平台");
        addLink(links, "traces", "追踪平台");
        return List.copyOf(links);
    }

    private void addLink(List<ProtectedLink> links, String kind, String label) {
        String configured = environment.getProperty("haizhuo.brain.operations.links." + kind);
        if (configured == null || configured.isBlank() || configured.length() > 2048) return;
        try {
            URI uri = new URI(configured);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null) {
                links.add(new ProtectedLink(kind.toUpperCase(java.util.Locale.ROOT), label, uri.toASCIIString()));
            }
        } catch (URISyntaxException ignored) {
            // 部署 URL 无效时直接省略，不作为可点击链接返回。
        }
    }

    private static String safeLabel(String value) {
        return value != null && SAFE_LABEL.matcher(value).matches() ? value : null;
    }
}
