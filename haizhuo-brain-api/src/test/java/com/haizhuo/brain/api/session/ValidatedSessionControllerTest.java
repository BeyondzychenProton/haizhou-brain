package com.haizhuo.brain.api.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.observability.LangfuseEvaluationService;
import com.haizhuo.brain.platform.run.RunArtifactService;
import com.haizhuo.brain.platform.run.RunFeedbackService;
import com.haizhuo.brain.platform.run.RunProgressQueryService;
import com.haizhuo.brain.platform.session.SessionApplicationService;
import com.haizhuo.brain.platform.session.SessionHistoryQueryService;
import com.haizhuo.brain.platform.tool.ToolApprovalService;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 使用真实校验自动配置，覆盖控制器装配、代理创建和业务调用前的参数约束。 */
class ValidatedSessionControllerTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(ControllersConfiguration.class)
            .withBean(RunArtifactService.class, () -> mock(RunArtifactService.class))
            .withBean(RunFeedbackService.class, () -> mock(RunFeedbackService.class))
            .withBean(RunProgressQueryService.class, () -> mock(RunProgressQueryService.class))
            .withBean(SessionHistoryQueryService.class, () -> mock(SessionHistoryQueryService.class))
            .withBean(SessionApplicationService.class, () -> mock(SessionApplicationService.class))
            .withBean(ToolApprovalService.class, () -> mock(ToolApprovalService.class))
            .withBean(LangfuseEvaluationService.class, () -> mock(LangfuseEvaluationService.class));

    @Test
    void validatedControllersInitializeWithProductionValidationConfiguration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            for (Class<?> controllerType : new Class<?>[] { RunArtifactController.class,
                    RunFeedbackController.class, RunProgressController.class, SessionHistoryController.class }) {
                assertThat(AopUtils.isCglibProxy(context.getBean(controllerType))).isTrue();
            }
        });
    }

    @Test
    void invalidArtifactPageSizeIsRejectedBeforeCallingTheService() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            RunArtifactController controller = context.getBean(RunArtifactController.class);
            AuthenticatedUser user = new AuthenticatedUser(new UserId(1), Set.of(PlatformRole.USER), 1, false);

            assertThatThrownBy(() -> controller.list(user, "run-a", null, 0))
                    .isInstanceOf(ConstraintViolationException.class);
            verifyNoInteractions(context.getBean(RunArtifactService.class));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({ RunArtifactController.class, RunFeedbackController.class,
            RunProgressController.class, SessionHistoryController.class })
    static class ControllersConfiguration { }
}
