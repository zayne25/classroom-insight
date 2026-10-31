package com.zhiketang.config;

import com.zhiketang.service.PermService;
import com.zhiketang.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 系统管理初始化：写入内置配置项、权限点与角色默认权限（幂等，不覆盖管理员修改）
 *
 * 与 DataSeeder 的区别：DataSeeder 负责业务演示数据，且在已有数据时会跳过；
 * 本类**每次启动都执行**，保证新版本新增的配置项 / 权限点能自动补齐。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SysInitRunner implements CommandLineRunner {

    private final SysConfigService sysConfig;
    private final PermService permService;

    @Override
    public void run(String... args) {
        try {
            sysConfig.ensureDefaults();
            permService.refresh();
            log.info("[SysInit] 系统配置 {} 项、权限点 {} 项就绪",
                    sysConfig.list().get("total"), SysConfigService.PERM_DEFS.size());
        } catch (Exception e) {
            // 初始化失败不能阻断应用启动：配置读取会回退到内置默认值
            log.warn("[SysInit] 系统管理初始化失败，将使用内置默认值：{}", e.getMessage());
        }
    }
}
