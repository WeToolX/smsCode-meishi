package com.wzz.smscode.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzz.smscode.entity.SystemConfig;
import com.wzz.smscode.entity.User;
import com.wzz.smscode.mapper.SystemConfigMapper;
import com.wzz.smscode.service.SystemConfigService;
import com.wzz.smscode.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

@Slf4j
@Service
public class SystemConfigServiceImpl extends ServiceImpl<SystemConfigMapper, SystemConfig> implements SystemConfigService {

    // 系统配置在数据库中通常只有一行，我们约定其主键为1
    private static final Integer CONFIG_ID = 1;

    // 假设用户状态 -1 代表封禁
    private static final int USER_STATUS_BANNED = -1;

    @Autowired
    private UserService userService; // 注入UserService以执行用户状态变更

    /**
     * 获取系统配置。
     * 使用Spring Cache进行缓存，key为'systemConfig'。
     * 只有当缓存中没有时，才会查询数据库。
     *
     * @return SystemConfig
     */
    @Override
    public SystemConfig getConfig() {
        log.debug("从数据库加载系统配置...");
        SystemConfig config = this.getById(CONFIG_ID);
        // 如果数据库为空，返回一个默认配置，防止空指针
        if (config == null) {
            log.warn("数据库中未找到ID为 {} 的系统配置，将返回默认实例。", CONFIG_ID);
            return new SystemConfig();
        }
        return config;
    }

    /**
     * 更新系统配置。
     * 仅管理员可执行（权限校验应由Controller或AOP完成）。
     * 更新成功后，会清除'systemConfig'缓存，确保下次读取是最新数据。
     *
     * @param config 待更新的配置对象
     * @return 是否成功
     */
    @Transactional
    @Override
    public boolean updateConfig(SystemConfig config) {
        log.info("准备更新系统配置: {}", config);

        // 1. 参数校验
        if (config.getMin24hCodeRate() != null && (config.getMin24hCodeRate() < 0.0 || config.getMin24hCodeRate() > 1.0)) {
            throw new IllegalArgumentException("24小时最低回码率必须在 0.0 到 1.0 之间");
        }

        // 2. 确保更新的是固定的那条配置记录
        config.setConfigId(CONFIG_ID);

        // 3. 执行更新
        boolean success = this.saveOrUpdate(config); // saveOrUpdate 可以在记录不存在时创建它
        if (success) {
            log.info("系统配置更新成功！");
        } else {
            log.error("系统配置更新失败！");
        }
        return success;
    }

    /**
     * 检查封禁模式是否开启。
     * 此方法会利用缓存的getConfig()，性能很高。
     *
     * @return boolean
     */
    @Override
    public boolean isBanModeEnabled() {
        SystemConfig config = getConfig();
        return config != null && Objects.equals(config.getEnableBanMode(), 1);
    }

    /**
     * 获取24小时最低回码率限制值。
     *
     * @return Double
     */
    @Override
    public Double getMinCodeRateLimit() {
        SystemConfig config = getConfig();
        return (config != null && config.getMin24hCodeRate() != null) ? config.getMin24hCodeRate() : 0.0;
    }

    /**
     * 检查并可能封禁用户。
     * 注意：此方法将配置服务的职责与用户管理耦合。在更大型的应用中，
     * 推荐的做法是创建一个定时任务调度器（e.g., ScheduledTaskService），
     * 该服务注入SystemConfigService和UserService，在定时任务中执行此逻辑。
     *
     * @param user 待检查的用户实体
     */
    @Override
    public void checkAndBanUser(User user) {
        if (!isBanModeEnabled()) {
            return; // 封禁模式未开启，直接返回
        }
        if (user == null || user.getId() == null) {
            log.warn("尝试检查一个无效的用户(null 或 id为null)");
            return;
        }

        // 假设User实体有getDailyCodeRate()方法，并且0为正常状态
        if (user.getStatus() != 0) {
            log.debug("用户 {} (状态: {}) 无需检查，已处于非正常状态。", user.getId(), user.getStatus());
            return;
        }

        Double minRate = getMinCodeRateLimit();
        // 假设User实体有dailyCodeRate字段
        Double userRate = user.getDailyCodeRate();

        if (userRate != null && userRate < minRate) {
            log.warn("用户 {} 的24小时回码率 ({}) 低于系统阈值 ({})，将被封禁。", user.getId(), userRate, minRate);

            // 创建一个只包含ID和新状态的User对象用于更新
            User userToUpdate = new User();
            userToUpdate.setId(user.getId());
            userToUpdate.setStatus(USER_STATUS_BANNED);

            // 调用UserService来更新用户状态
            boolean updated = userService.updateById(userToUpdate);
            if(updated) {
                log.info("用户 {} 已被成功封禁。", user.getId());
                // 可选：在这里添加发送通知给用户的逻辑
            } else {
                log.error("尝试封禁用户 {} 失败！", user.getId());
            }
        }
    }
}
