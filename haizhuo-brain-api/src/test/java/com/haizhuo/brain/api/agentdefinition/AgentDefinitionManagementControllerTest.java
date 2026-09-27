package com.haizhuo.brain.api.agentdefinition;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.haizhuo.brain.kernel.identity.UserId;
import com.haizhuo.brain.platform.employee.AgentDefinitionDraft;
import com.haizhuo.brain.platform.employee.AgentDefinitionManagementService;
import com.haizhuo.brain.platform.employee.CapabilitySelection;
import com.haizhuo.brain.security.identity.AuthenticatedUser;
import com.haizhuo.brain.security.identity.PlatformRole;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentDefinitionManagementControllerTest {
    @Mock AgentDefinitionManagementService management;

    @Test
    void writesUseTheAuthenticatedAdministratorInsteadOfAnyClientActorField() {
        AgentDefinitionManagementController controller = new AgentDefinitionManagementController(management);
        AuthenticatedUser administrator = new AuthenticatedUser(new UserId(71), Set.of(PlatformRole.PLATFORM_ADMIN), 3, false);
        var update = new AgentDefinitionManagementService.DraftUpdate("只执行受控读取", "openai-compatible", "test-model",
                List.of(new CapabilitySelection("sample.read", "1")));
        var request = new AgentDefinitionManagementController.DraftRequest(4, update.instructions(), update.modelProvider(),
                update.modelName(), update.capabilities(), "修订员工提示与能力绑定");
        var saved = new AgentDefinitionDraft(9, 5, update.instructions(), update.modelProvider(), update.modelName(), update.capabilities(), Instant.now());
        when(management.saveDraft(9, 4, update, 71, request.reason())).thenReturn(saved);

        controller.updateDraft(administrator, 9, request).block();
        controller.setUserGrant(administrator, 88, "sample.read",
                new AgentDefinitionManagementController.GrantRequest(true, "为已审批用户授予读取能力")).block();

        verify(management).saveDraft(9, 4, update, 71, "修订员工提示与能力绑定");
        verify(management).setUserCapabilityGrant(88, "sample.read", true, 71, "为已审批用户授予读取能力");
    }
}
