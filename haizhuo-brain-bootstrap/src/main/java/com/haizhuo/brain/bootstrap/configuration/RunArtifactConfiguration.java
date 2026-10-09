package com.haizhuo.brain.bootstrap.configuration;

import com.haizhuo.brain.infrastructure.run.PrivateDirectoryRunArtifactBlobStore;
import com.haizhuo.brain.infrastructure.run.UnavailableRunArtifactBlobStore;
import com.haizhuo.brain.platform.run.AgentResultRepository;
import com.haizhuo.brain.platform.run.RunArtifactBlobStore;
import com.haizhuo.brain.platform.run.RunArtifactIndex;
import com.haizhuo.brain.platform.run.RunArtifactService;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import java.nio.file.Path;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 成果物存储默认关闭，只允许服务端运维人员配置。 */
@Configuration
public class RunArtifactConfiguration {
    @Bean
    MarkdownArtifactAdmission markdownArtifactAdmission(RunArtifactProperties properties,
                                                        DeploymentVerificationManifestReader manifestReader) {
        return MarkdownArtifactAdmission.evaluate(properties, manifestReader);
    }

    @Bean
    RunArtifactBlobStore runArtifactBlobStore(RunArtifactProperties properties,
                                              MarkdownArtifactAdmission admission) {
        if (!admission.enabled())
            return new UnavailableRunArtifactBlobStore();
        return new PrivateDirectoryRunArtifactBlobStore(Path.of(properties.getStorageDirectory()));
    }

    @Bean
    RunArtifactService runArtifactService(SessionApplicationService sessions, AgentResultRepository results,
                                          RunArtifactIndex index, RunArtifactBlobStore blobs,
                                          RunArtifactProperties properties, MarkdownArtifactAdmission admission,
                                          Clock clock) {
        return new RunArtifactService(sessions, results, index, blobs, admission.enabled(),
                properties.getMaxBytes(), clock);
    }
}
