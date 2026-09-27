package com.haizhuo.brain.bootstrap.identity;

import com.haizhuo.brain.security.identity.PlatformUser;
import com.haizhuo.brain.security.identity.PlatformUserManagementService;
import java.io.Console;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * Explicit, offline-only bootstrap path. Invoke with a terminal and
 * --haizhuo.brain.bootstrap-admin.enabled=true --haizhuo.brain.security.session.store=memory
 * --spring.main.web-application-type=none.
 */
@Component
@ConditionalOnProperty(prefix = "haizhuo.brain.bootstrap-admin", name = "enabled", havingValue = "true")
public class FirstAdminBootstrapRunner implements ApplicationRunner {
    private final PlatformUserManagementService managementService;
    private final ConfigurableApplicationContext applicationContext;

    public FirstAdminBootstrapRunner(PlatformUserManagementService managementService, ConfigurableApplicationContext applicationContext) {
        this.managementService = managementService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) {
        Console console = System.console();
        if (console == null) throw new IllegalStateException("首位管理员初始化必须从受控交互式终端运行");
        String mobile = console.readLine("首位管理员手机号: ");
        char[] password = console.readPassword("初始密码: ");
        char[] confirmPassword = console.readPassword("再次输入初始密码: ");
        try {
            if (!Arrays.equals(password, confirmPassword)) throw new IllegalArgumentException("两次输入的密码不一致");
            PlatformUser user = managementService.initializeFirstAdmin(mobile, new String(password));
            console.printf("首位管理员已创建，UserId=%d。首次登录后必须修改密码。%n", user.id().value());
        } finally {
            Arrays.fill(password, '\0');
            Arrays.fill(confirmPassword, '\0');
            applicationContext.close();
        }
    }
}
