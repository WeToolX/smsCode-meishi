package com.wzz.smscode.cacheManager;


import com.wzz.smscode.entity.NumberRecord;
import com.wzz.smscode.entity.Project;
import com.wzz.smscode.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 号码业务缓存管理器
 */
@Slf4j
@Component
public class NumberRecordCacheManager {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 前缀定义
    private static final String PREFIX_USER = "sms:user:name:";
    private static final String PREFIX_PROJECT = "sms:project:cfg:";
    private static final String PREFIX_RECORD = "sms:record:phone:";

    // 过期时间定义
    private static final long USER_EXPIRE = 24; // 24小时，用户信息
    private static final long PROJECT_EXPIRE = 15; // 15分钟  项目缓存
    private static final long RECORD_EXPIRE = 5; // 5秒，号码记录

    // --- 用户缓存 ---

    public void cacheUser(User user) {
        if (user == null) return;
        redisTemplate.opsForValue().set(PREFIX_USER + user.getUserName(), user, USER_EXPIRE, TimeUnit.HOURS);
    }

    public User getUser(String userName) {
        return (User) redisTemplate.opsForValue().get(PREFIX_USER + userName);
    }

    public void evictUser(String userName) {
        redisTemplate.delete(PREFIX_USER + userName);
    }

    // --- 项目配置缓存 ---

    public void cacheProject(String projectId, String lineId, Project project) {
        if (project == null) return;
        String key = PREFIX_PROJECT + projectId + ":" + lineId;
        redisTemplate.opsForValue().set(key, project, PROJECT_EXPIRE, TimeUnit.MINUTES);
    }

    public Project getProject(String projectId, String lineId) {
        String key = PREFIX_PROJECT + projectId + ":" + lineId;
        return (Project) redisTemplate.opsForValue().get(key);
    }

    /**
     * 清除项目缓存
     */
    public void evictProject(String projectId, String lineId) {
        if (projectId == null || lineId == null) return;
        String key = PREFIX_PROJECT + projectId + ":" + lineId;
        redisTemplate.delete(key);
    }

    // --- 号码记录缓存 (核心) ---

    /**
     * 缓存号码记录
     * 使用 手机号 + 项目ID 作为组合Key
     */
    public void cacheRecord(NumberRecord record) {
        log.info("缓存号码记录：{}", record);
        if (record == null) return;
        String key = PREFIX_RECORD + record.getPhoneNumber() + ":" + record.getProjectId();
        redisTemplate.opsForValue().set(key, record, RECORD_EXPIRE, TimeUnit.SECONDS);
    }

    public NumberRecord getRecord(String phoneNumber, String projectId) {
        log.info("获取缓存号码记录：{}", phoneNumber);
        String key = PREFIX_RECORD + phoneNumber + ":" + projectId;
        return (NumberRecord) redisTemplate.opsForValue().get(key);
    }

    public void evictRecord(String phoneNumber, String projectId) {
        String key = PREFIX_RECORD + phoneNumber + ":" + projectId;
        redisTemplate.delete(key);
    }
}
