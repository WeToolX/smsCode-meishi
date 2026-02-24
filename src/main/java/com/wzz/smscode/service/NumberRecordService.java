package com.wzz.smscode.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.dto.*;
import com.wzz.smscode.dto.number.NumberDTO;
import com.wzz.smscode.entity.NumberRecord;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;

public interface NumberRecordService extends IService<NumberRecord> {

    @Async("taskExecutor")
    @Transactional
    void recoverInterruptedTask(NumberRecord record);

//    @Transactional
    CommonResultDTO<String> getNumber(String userName, String password, String projectId, String lineId);

    @Transactional(rollbackFor = Exception.class)
    CommonResultDTO<String> createOrderTransaction(Long userId, String projectId, String lineId,
                                                   Map<String, String> successfulIdentifier, String projectName);

    @Async("taskExecutor")
    void retrieveCode(Long numberId, String identifier);

    @Transactional
    void updateRecordAfterRetrieval(NumberRecord record, boolean is, String result);

    CommonResultDTO<String> getCode(String userName, String password, String identifier,String projectId,String lineId);

    IPage<NumberDTO> listUserNumbers(Long userName, String password, Integer statusFilter, Date startTime, Date endTime, IPage<NumberRecord> page);

    IPage<NumberDTO> listUserNumbersByUSerName(String userName, String password, Integer statusFilter, Date startTime, Date endTime, IPage<NumberRecord> page);

    IPage<NumberRecord> listAllNumbers(
            Integer statusFilter, Date startTime, Date endTime,
            Long userId, String projectId, String phoneNumber, Integer charged,
            IPage<NumberRecord> page,String lineId,String userName
    );

    NumberRecord getRecordByPhone(String phone);

    List<String> getPhoneNumbersByProjectId(String projectId);

    /**
     * 根据用户角色生成统计报表的主入口方法（支持分页和筛选）。
     *
     * @param operatorId 当前操作用户的ID
     * @param queryDTO   包含分页和筛选条件的查询对象
     * @return 分页后的项目统计报告
     */
    IPage<ProjectStatisticsDTO> getStatisticsReport(Long operatorId, StatisticsQueryDTO queryDTO);

    IPage<NumberDTO> listSubordinateRecordsForAgent(Long agentId, SubordinateNumberRecordQueryDTO queryDTO);

    IPage<UserLineStatsDTO> getUserLineStats(UserLineStatsRequestDTO requestDTO, Long agentId);

    @Transactional(rollbackFor = Exception.class)
    int batchRefundByQuery(BatchRefundQueryDTO queryDTO);

    @Transactional(rollbackFor = Exception.class)
    void deleteNumberRecordByDays(Long operatorId, Long targetUserId, Integer days, boolean isAdmin);

    CommonResultDTO<String> releasePhoneNumber(String userName, String password, String phoneNumber, String projectId, String lineId,boolean isSuccess);
}
