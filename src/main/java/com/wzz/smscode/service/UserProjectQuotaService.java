package com.wzz.smscode.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.wzz.smscode.entity.UserProjectQuota;

import java.util.List;

public interface UserProjectQuotaService extends IService<UserProjectQuota> {

    /**
     * 查询用户在指定项目线路下的剩余配额
     */
    long getAvailableCount(Long userId, String projectId, String lineId);

    /**
     * 扣减配额（幂等）
     */
    void deductQuota(Long userId, String projectId, String lineId, long count,
                     String bizNo, Long operatorId, String remark);

    /**
     * 增加配额（幂等）
     */
    void addQuota(Long userId, String projectId, String lineId, long count,
                  String bizNo, Long operatorId, String remark);

    /**
     * 转移配额：从来源用户扣减，再给目标用户增加（幂等）
     */
    void transferQuota(Long fromUserId, Long toUserId,
                       String projectId, String lineId, long count,
                       String bizNo, Long operatorId,
                       String fromRemark, String toRemark);

    /**
     * 查询用户在某个项目下有余额的线路ID列表
     */
    List<String> listAvailableLineIds(Long userId, String projectId);
}
