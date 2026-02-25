package com.wzz.smscode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wzz.smscode.entity.SystemConfig;
import com.wzz.smscode.entity.User;
import org.springframework.transaction.annotation.Transactional;

public interface SystemConfigService extends IService<SystemConfig> {
    SystemConfig getConfig();

    @Transactional
    boolean updateConfig(SystemConfig config);

    boolean isBanModeEnabled();

    Double getMinCodeRateLimit();

    void checkAndBanUser(User user);
}
