package com.wzz.smscode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzz.smscode.entity.UserProjectQuota;
import com.wzz.smscode.entity.UserProjectQuotaLedger;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.mapper.UserProjectQuotaLedgerMapper;
import com.wzz.smscode.mapper.UserProjectQuotaMapper;
import com.wzz.smscode.service.UserProjectQuotaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 用户项目线路配额服务
 */
@Slf4j
@Service
public class UserProjectQuotaServiceImpl extends ServiceImpl<UserProjectQuotaMapper, UserProjectQuota>
        implements UserProjectQuotaService {

    @Autowired
    private UserProjectQuotaLedgerMapper quotaLedgerMapper;

    @Override
    public long getAvailableCount(Long userId, String projectId, String lineId) {
        validateBaseParams(userId, projectId, lineId);
        UserProjectQuota quota = this.getOne(new LambdaQueryWrapper<UserProjectQuota>()
                .eq(UserProjectQuota::getUserId, userId)
                .eq(UserProjectQuota::getProjectId, projectId)
                .eq(UserProjectQuota::getLineId, lineId)
                .last("LIMIT 1"));
        if (quota == null || quota.getAvailableCount() == null) {
            return 0L;
        }
        return Math.max(quota.getAvailableCount(), 0L);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deductQuota(Long userId, String projectId, String lineId, long count,
                            String bizNo, Long operatorId, String remark) {
        validateOperationParams(userId, projectId, lineId, count, bizNo, operatorId);
        if (hasProcessed(bizNo)) {
            log.info("配额扣减幂等命中，忽略重复请求。bizNo={}", bizNo);
            return;
        }

        UserProjectQuota quota = getOrCreateLockedQuota(userId, projectId, lineId);
        long beforeCount = safeCount(quota.getAvailableCount());
        long afterCount = beforeCount - count;
        if (afterCount < 0) {
            throw new BusinessException("项目线路配额不足");
        }

        quota.setAvailableCount(afterCount);
        this.updateById(quota);

        createLedger(bizNo, userId, operatorId, projectId, lineId,
                count, 0, beforeCount, afterCount, remark);
        log.info("配额扣减成功，用户ID={}, 项目={}, 线路={}, 扣减={}, 结余={}",
                userId, projectId, lineId, count, afterCount);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void addQuota(Long userId, String projectId, String lineId, long count,
                         String bizNo, Long operatorId, String remark) {
        validateOperationParams(userId, projectId, lineId, count, bizNo, operatorId);
        if (hasProcessed(bizNo)) {
            log.info("配额增加幂等命中，忽略重复请求。bizNo={}", bizNo);
            return;
        }

        UserProjectQuota quota = getOrCreateLockedQuota(userId, projectId, lineId);
        long beforeCount = safeCount(quota.getAvailableCount());
        long afterCount = beforeCount + count;

        quota.setAvailableCount(afterCount);
        this.updateById(quota);

        createLedger(bizNo, userId, operatorId, projectId, lineId,
                count, 1, beforeCount, afterCount, remark);
        log.info("配额增加成功，用户ID={}, 项目={}, 线路={}, 增加={}, 结余={}",
                userId, projectId, lineId, count, afterCount);
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void transferQuota(Long fromUserId, Long toUserId,
                              String projectId, String lineId, long count,
                              String bizNo, Long operatorId,
                              String fromRemark, String toRemark) {
        validateOperationParams(fromUserId, projectId, lineId, count, bizNo, operatorId);
        if (toUserId == null || toUserId <= 0) {
            throw new BusinessException("目标用户参数非法");
        }
        if (Objects.equals(fromUserId, toUserId)) {
            throw new BusinessException("来源用户和目标用户不能相同");
        }
        if (hasProcessed(bizNo + "_OUT") || hasProcessed(bizNo + "_IN")) {
            log.info("配额转移幂等命中，忽略重复请求。bizNo={}", bizNo);
            return;
        }

        // 固定加锁顺序，避免并发死锁
        if (fromUserId < toUserId) {
            getOrCreateLockedQuota(fromUserId, projectId, lineId);
            getOrCreateLockedQuota(toUserId, projectId, lineId);
        } else {
            getOrCreateLockedQuota(toUserId, projectId, lineId);
            getOrCreateLockedQuota(fromUserId, projectId, lineId);
        }

        deductQuota(fromUserId, projectId, lineId, count, bizNo + "_OUT", operatorId, fromRemark);
        addQuota(toUserId, projectId, lineId, count, bizNo + "_IN", operatorId, toRemark);
    }

    @Override
    public List<String> listAvailableLineIds(Long userId, String projectId) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(projectId)) {
            return Collections.emptyList();
        }
        return this.list(new LambdaQueryWrapper<UserProjectQuota>()
                        .eq(UserProjectQuota::getUserId, userId)
                        .eq(UserProjectQuota::getProjectId, projectId)
                        .gt(UserProjectQuota::getAvailableCount, 0)
                        .select(UserProjectQuota::getLineId))
                .stream()
                .map(UserProjectQuota::getLineId)
                .toList();
    }

    /**
     * 获取并锁定配额记录，不存在则创建零配额记录。
     */
    private UserProjectQuota getOrCreateLockedQuota(Long userId, String projectId, String lineId) {
        UserProjectQuota quota = baseMapper.selectByUserProjectLineForUpdate(userId, projectId, lineId);
        if (quota != null) {
            return quota;
        }

        UserProjectQuota create = new UserProjectQuota();
        create.setUserId(userId);
        create.setProjectId(projectId);
        create.setLineId(lineId);
        create.setAvailableCount(0L);
        try {
            this.save(create);
        } catch (DuplicateKeyException e) {
            log.info("配额记录并发创建冲突，改为重新读取并加锁，userId={}, projectId={}, lineId={}",
                    userId, projectId, lineId);
        }
        quota = baseMapper.selectByUserProjectLineForUpdate(userId, projectId, lineId);
        if (quota == null) {
            throw new BusinessException("创建配额记录失败，请重试");
        }
        return quota;
    }

    private boolean hasProcessed(String bizNo) {
        if (!StringUtils.hasText(bizNo)) {
            return false;
        }
        return quotaLedgerMapper.selectByBizNo(bizNo) != null;
    }

    private void createLedger(String bizNo, Long userId, Long operatorId,
                              String projectId, String lineId,
                              long changeCount, int ledgerType,
                              long beforeCount, long afterCount,
                              String remark) {
        UserProjectQuotaLedger ledger = new UserProjectQuotaLedger();
        ledger.setBizNo(bizNo);
        ledger.setUserId(userId);
        ledger.setOperatorId(operatorId);
        ledger.setProjectId(projectId);
        ledger.setLineId(lineId);
        ledger.setChangeCount(changeCount);
        ledger.setLedgerType(ledgerType);
        ledger.setCountBefore(beforeCount);
        ledger.setCountAfter(afterCount);
        ledger.setRemark(remark);
        ledger.setTimestamp(LocalDateTime.now());
        quotaLedgerMapper.insert(ledger);
    }

    private void validateBaseParams(Long userId, String projectId, String lineId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException("用户参数非法");
        }
        if (!StringUtils.hasText(projectId)) {
            throw new BusinessException("项目ID不能为空");
        }
        if (!StringUtils.hasText(lineId)) {
            throw new BusinessException("线路ID不能为空");
        }
    }

    private void validateOperationParams(Long userId, String projectId, String lineId,
                                         long count, String bizNo, Long operatorId) {
        validateBaseParams(userId, projectId, lineId);
        if (count <= 0) {
            throw new BusinessException("配额变动数量必须大于0");
        }
        if (!StringUtils.hasText(bizNo)) {
            throw new BusinessException("业务号不能为空");
        }
        if (operatorId == null) {
            throw new BusinessException("操作人不能为空");
        }
    }

    private long safeCount(Long count) {
        return count == null ? 0L : count;
    }
}
