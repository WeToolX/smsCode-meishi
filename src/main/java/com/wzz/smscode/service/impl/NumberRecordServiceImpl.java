package com.wzz.smscode.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzz.smscode.cacheManager.NumberRecordCacheManager;
import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.common.Constants;
import com.wzz.smscode.dto.*;
import com.wzz.smscode.dto.CreatDTO.LedgerCreationDTO;
import com.wzz.smscode.dto.number.NumberDTO;
import com.wzz.smscode.entity.*;
import com.wzz.smscode.enums.FundType;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.mapper.NumberRecordMapper;
import com.wzz.smscode.moduleService.SmsApiService;
import com.wzz.smscode.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NumberRecordServiceImpl extends ServiceImpl<NumberRecordMapper, NumberRecord> implements NumberRecordService {
    @Autowired private UserService userService;
    @Autowired private ProjectService projectService;
    @Autowired private UserLedgerService ledgerService;
    @Autowired @Lazy private NumberRecordService self;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private UserLedgerService userLedgerService; // 引入账本服务
    @Autowired @Lazy private PriceTemplateService priceTemplateService;
    @Autowired private SmsApiService smsApiService;
    @Autowired private UserProjectLineService userProjectLineService;


    private static final Pattern PHONE_NUMBER_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    /**
     * 检查指定的手机号是否已经存在于特定项目的记录中。
     */
    private boolean isPhoneNumberExistsInProject(String projectId, String phoneNumber) {
        long count = this.count(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getProjectId, projectId)
                .eq(NumberRecord::getPhoneNumber, phoneNumber)
        );
        return count > 0;
    }




    /**
     * 恢复中断的任务
     */
    @Async("taskExecutor")
    @Transactional
    @Override
    public void recoverInterruptedTask(NumberRecord record) {
        //计算已经消耗的时间
        LocalDateTime now = LocalDateTime.now();
        long elapsedSeconds = Duration.between(record.getCreateTime(), now).getSeconds();
        long maxWaitSeconds = 15 * 60;
        if (elapsedSeconds > maxWaitSeconds) {
            log.info("恢复任务[VT]: 记录[{}] 在宕机期间已超时，执行退款。", record.getId());

            this.updateRecordAfterRetrieval(record, false, null);
        } else {
            log.info("恢复任务[VT]: 记录[{}] 尚在有效期，重新加入轮询队列。", record.getId());
            self.retrieveCode(record.getId(), null);
        }
    }


//    @Transactional
    @Override
    public CommonResultDTO<String> getNumber(String userName, String password, String projectId, Integer lineId) {
        // 1. 身份验证与余额检查
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户验证失败");

        SystemConfig config = systemConfigService.getConfig();
        if (user.getStatus() != 0) {
            return CommonResultDTO.error(-5, "用户已被禁用");
        }
        if (config.getEnableBanMode() == 1 && user.getDailyCodeRate() < config.getMin24hCodeRate()) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "回码率过低封禁");
        }
        String blacklist = user.getProjectBlacklist();
        String currentKey = projectId + "-" + lineId;
        if (StringUtils.hasText(blacklist)) {
            String[] blocked = blacklist.split(",");
            for (String s : blocked) {
                if (s.trim().equals(currentKey)) {
                    return CommonResultDTO.error(-5, "您已被禁止使用该项目线路");
                }
            }
        }

        //从模板获取价格
        Long templateId = user.getTemplateId();
        if (templateId == null) return CommonResultDTO.error(-5, "用户未配置价格模板");

        PriceTemplateItem priceItem = priceTemplateService.getPriceConfig(templateId, projectId, lineId);
        if (priceItem == null) {
            return CommonResultDTO.error(-5, "当前价格模板未配置该项目线路");
        }

        BigDecimal price = priceItem.getPrice(); // 用户售价
        BigDecimal costPrice = priceItem.getCostPrice(); // 成本价

        Project projectT = projectService.getProject(projectId, lineId);
        if (projectT == null) return CommonResultDTO.error(-5, "总项目表中不存在这个项目和线路！");
        if (!projectT.isStatus()) {
            return CommonResultDTO.error(Constants.ERROR_NO_NUMBER, "该项目没开启！");
        }

        if (user.getBalance().compareTo(price) < 0) {
            return CommonResultDTO.error(Constants.ERROR_INSUFFICIENT_BALANCE, "余额不足");
        }
//        boolean hasOngoingRecord = this.hasOngoingRecord(user.getId());

//        if (!BalanceUtil.canGetNumber(user, hasOngoingRecord)) {
//            return CommonResultDTO.error(Constants.ERROR_INSUFFICIENT_BALANCE, "余额不足或已有进行中的任务");
//        }
//        if (hasOngoingRecord) {
//            return CommonResultDTO.error(Constants.ERROR_INSUFFICIENT_BALANCE, "已有进行中的取号任务");
//        }
        final int MAX_ATTEMPTS = 3;
        Map<String, String> successfulIdentifier = null;
        boolean numberFoundAndVerified = false;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Map<String, String> currentIdentifier = smsApiService.getPhoneNumber(projectT);
                String phone = (currentIdentifier != null) ? currentIdentifier.get("phone") : null;
                boolean isValidPhone = StringUtils.hasText(phone) && PHONE_NUMBER_PATTERN.matcher(phone).matches();
                if (!isValidPhone) {
                    log.warn("获取号码无效或格式不正确 (尝试 {}/{}): {}", attempt, MAX_ATTEMPTS, phone);
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(500);
                    continue;
                }
                if (isPhoneNumberExistsInProject(projectId, phone)) {
                    log.warn("号码 [{}] 已存在，重试...", phone);
                    if (attempt < MAX_ATTEMPTS) Thread.sleep(200);
                    continue;
                }
                if (config.getEnableNumberFiltering() && projectT.getEnableFilter()) {
                    try {
                        Boolean isAvailable = smsApiService.checkPhoneNumberAvailability(projectT, phone, null)
                                .block(Duration.ofSeconds(60));
                        if (!Boolean.TRUE.equals(isAvailable)) {
                            log.warn("号码 [{}] 筛选不通过", phone);
                            if (attempt < MAX_ATTEMPTS) Thread.sleep(200);
                            continue;
                        }
                    } catch (Exception e) {
                        log.error("筛选调用异常，视为筛选不通过", e);
                        continue;
                    }
                }
                successfulIdentifier = currentIdentifier;
                numberFoundAndVerified = true;
                break;

            } catch (BusinessException e) {
                log.error("调用接口获取号码失败 (尝试 {}/{})，终止流程: {}", attempt, MAX_ATTEMPTS, e.getMessage());
                return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "" + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "系统线程中断");
            } catch (Exception e) {
                log.error("未知异常", e);
                return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "系统未知错误");
            }
        }
        if (!numberFoundAndVerified || successfulIdentifier == null) {
            return CommonResultDTO.error(Constants.ERROR_NO_CODE, "获取可用号码失败，请稍后重试");
        }
//        String phoneNumber = successfulIdentifier.get("phone");

//        // 1. 执行扣款账本 (LedgerType=0 出账, FundType=业务扣费)
//        LedgerCreationDTO deductionDto = LedgerCreationDTO.builder()
//                .userId(user.getId())
//                .amount(price)
//                .ledgerType(0) // 出账
//                .fundType(FundType.BUSINESS_DEDUCTION)
//                .remark("取号预扣费")
//                .phoneNumber(phoneNumber)
//                .lineId(lineId)
//                .projectId(projectId)
//                .build();
//
//        // 此方法会扣除余额，如果余额不足会抛出异常回滚事务
//        BigDecimal newBalance = ledgerService.createLedgerAndUpdateBalance(deductionDto);
//
//        // 2. 写入号码记录
//        NumberRecord record = new NumberRecord();
//        record.setUserId(user.getId());
//        record.setProjectId(projectId);
//        record.setLineId(lineId);
//        record.setUserName(user.getUserName());
//        record.setPhoneNumber(phoneNumber);
//        record.setApiPhoneId(successfulIdentifier.get("id"));
//        record.setStatus(0); // 待取码
//        record.setCharged(1); // 【关键】标记为已扣费
//        record.setPrice(price);
//        record.setCostPrice(costPrice);
//        record.setBalanceBefore(user.getBalance()); // 扣费前余额
//        record.setBalanceAfter(newBalance);         // 扣费后余额
//        record.setGetNumberTime(LocalDateTime.now());
//        record.setProjectName(projectT.getProjectName());
//        this.save(record);
//
//        final Long recordId = record.getId();
//        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
//            @Override
//            public void afterCommit() {
//                try {
//                    self.retrieveCode(recordId, null);
//                } catch (Exception e) {
//                    log.error("取号任务启动异常，记录ID: {}", recordId, e);
//                }
//            }
//        });
//
//        userService.updateUserStats(user.getId());
        try {
            return self.createOrderTransaction(
                    user.getId(),
                    projectId,
                    lineId,
                    price,
                    costPrice,
                    successfulIdentifier,
                    projectT.getProjectName()
            );
        } catch (BusinessException e) {
            return CommonResultDTO.error(Constants.ERROR_INSUFFICIENT_BALANCE, e.getMessage());
        }
//        return CommonResultDTO.success("取号成功，请稍后查询验证码", successfulIdentifier.get("phone"));
    }


    /**
     * 专门处理数据库事务的方法
     * 只有这个方法上有 @Transactional
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CommonResultDTO<String> createOrderTransaction(Long userId, String projectId, Integer lineId,
                                                          BigDecimal price, BigDecimal costPrice,
                                                          Map<String, String> successfulIdentifier, String projectName) {
        // 1. 扣费逻辑 (UserLedgerService 内部也会有事务，会加入到当前事务中)
        User user = userService.getById(userId);
        // 双重检查余额(可选)
        if (user.getBalance().compareTo(price) < 0) {
            throw new BusinessException("余额不足");
        }
        LedgerCreationDTO deductionDto = LedgerCreationDTO.builder()
                .userId(userId)
                .amount(price)
                .ledgerType(0) // 出账
                .fundType(FundType.BUSINESS_DEDUCTION)
                .remark("取号预扣费")
                .phoneNumber(successfulIdentifier.get("phone"))
                .lineId(lineId)
                .projectId(projectId)
                .build();
        // 执行扣款
        BigDecimal newBalance = ledgerService.createLedgerAndUpdateBalance(deductionDto);
        // 2. 写入号码记录
        NumberRecord record = new NumberRecord();
        record.setUserId(userId);
        record.setProjectId(projectId);
        record.setLineId(lineId);
        record.setUserName(user.getUserName());
        record.setPhoneNumber(successfulIdentifier.get("phone"));
        record.setApiPhoneId(successfulIdentifier.get("id"));
        record.setStatus(0);
        record.setCharged(1);
        record.setPrice(price);
        record.setCostPrice(costPrice);
        record.setBalanceBefore(user.getBalance());
        record.setBalanceAfter(newBalance);
        record.setGetNumberTime(LocalDateTime.now());
        record.setProjectName(projectName);
        this.save(record); // 落库
        final Long recordId = record.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    self.retrieveCode(recordId, null);
                } catch (Exception e) {
                    log.error("取号任务启动异常，记录ID: {}", recordId, e);
                }
            }
        });

        // 4. 更新用户统计等其他操作
        userService.updateUserStats(userId);
        return CommonResultDTO.success("取号成功，请稍后查询验证码", successfulIdentifier.get("phone"));
    }

    /**
     * 异步轮询取码
     */
    @Async("taskExecutor")
    @Override
    public void retrieveCode(Long numberId, String unusedIdentifier) {
        NumberRecord record = this.getById(numberId);
        if (record == null || record.getStatus() >= 2) {
            return;
        }
        // 更新为取码中
        if (record.getStatus() == 0) {
            record.setStatus(1);
            record.setStartCodeTime(LocalDateTime.now());
            this.updateById(record);
        }
        Project project = projectService.getProject(record.getProjectId(), record.getLineId());
        Map<String, String> context = new HashMap<>();
        context.put("phone", record.getPhoneNumber());
        if (StringUtils.hasText(record.getApiPhoneId())) {
            context.put("id", record.getApiPhoneId());
        }
        String result = null;
        boolean isSuccess = false;
        try {
            if (Boolean.TRUE.equals(project.getSpecialApiStatus())) {
                log.info("记录[{}] 进入特殊获码流程 (等待{}s -> 请求一次)", numberId, project.getSpecialApiDelay());
                try {
                    result = smsApiService.getVerificationCodeSpecial(project, context, false);
                    if (StringUtils.hasText(result) && !result.equalsIgnoreCase("NO")) {
                        isSuccess = true;
                    } else {
                        log.info("记录[{}] 特殊API返回无码或NO，准备退款", numberId);
                        isSuccess = false;
                    }
                } catch (Exception e) {
                    log.error("记录[{}] 特殊API请求发生异常: {}", numberId, e.getMessage());
                    isSuccess = false; // 异常视为失败
                }
            }else {
                // 每次循环都会执行这个 Lambda。如果数据库中状态变成了 2(成功) 或 3(失败)，则停止轮询
                Supplier<Boolean> stopCondition = () -> {
                    NumberRecord current = baseMapper.selectById(numberId);
                    return current == null || current.getStatus() != 1;
                };
                // 传入停止条件
                result = smsApiService.getVerificationCode(project, context, stopCondition);
                if (StringUtils.hasText(result) && !result.contains("等待")) {
                    isSuccess = true;
                }
            }
        } catch (Exception e) {
            log.info("轮询获码异常: {}", e.getMessage());
        }
        self.updateRecordAfterRetrieval(record, isSuccess, result);
    }

    @Autowired
    private NumberRecordCacheManager cacheManager;
    /**
     * 手动获取验证码接口
     * 修改说明：
     * 1. 对于 SpecialApi 项目，不再主动请求第三方API。
     * 2. 检查是否已有码，有则返回。
     * 3. 无码则检查是否超时（Delay + OutTime），超时则触发退款。
     */
    @Override
    public CommonResultDTO<String> getCode(String userName, String password, String identifier, String projectId, String lineId) {
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户验证失败");

        // 2. 获取记录 (优先缓存)
        NumberRecord record = cacheManager.getRecord(identifier, projectId);
        if (record == null) {
            record = this.getOne(new LambdaQueryWrapper<NumberRecord>()
                    .eq(NumberRecord::getPhoneNumber, identifier)
                    .eq(NumberRecord::getUserId, user.getId())
                    .eq(NumberRecord::getProjectId, projectId)
                    .orderByDesc(NumberRecord::getGetNumberTime)
                    .last("LIMIT 1"));
            if (record != null) {
                cacheManager.cacheRecord(record);
            }
        }

        if (record == null) return CommonResultDTO.error(Constants.ERROR_NO_CODE, "无记录");
        //如果已经是成功状态，直接返回库里的码
        if (record.getStatus() == 2 && StringUtils.hasText(record.getCode())) {
            return CommonResultDTO.success("获取成功", record.getCode());
        }
        //如果是已退款/已失效状态，提示已结束
        if (record.getStatus() == 3 || record.getStatus() == 4 || record.getCharged() == 2) {
            return CommonResultDTO.error(Constants.ERROR_NO_CODE, "订单已失效或已退款，请重新取号");
        }
        if (record.getStatus() ==1) {
            return CommonResultDTO.error(Constants.ERROR_NO_CODE,"后台正在取码中，请稍后...");
        }
        Project project = cacheManager.getProject(projectId, Integer.valueOf(lineId));
        if (project == null) {
            project = projectService.getProject(record.getProjectId(), record.getLineId());
            if (project != null) {
                cacheManager.cacheProject(projectId, Integer.valueOf(lineId), project);
            }
        }

        // ================== 特殊API ==================
        if (Boolean.TRUE.equals(project.getSpecialApiStatus())) {
            //获取配置时间参数
            //默认等待30秒
            long delaySeconds = project.getSpecialApiDelay() != null ? project.getSpecialApiDelay() : 30;
            //默认超时150秒
            long timeoutSeconds = project.getSpecialApiGetCodeOutTime() != null ? project.getSpecialApiGetCodeOutTime() : 150;
            //总允许等待时间 = 等待延迟 + 接收超时
            long maxWaitSeconds = delaySeconds + timeoutSeconds;
            //计算已过去的时间 (当前时间 - 取号时间)
            Duration duration = Duration.between(record.getGetNumberTime(), LocalDateTime.now());
            long elapsedSeconds = duration.getSeconds();
            //检查是否超时
            if (elapsedSeconds > maxWaitSeconds) {
                if (record.getCharged() == 1) {
                    log.info("用户[{}]手动查询发现记录[{}]超时 (已耗时{}s > 允许{}s)，触发退款",
                            userName, record.getId(), elapsedSeconds, maxWaitSeconds);
                    self.updateRecordAfterRetrieval(record, false, null);

                    return CommonResultDTO.error(Constants.ERROR_NO_CODE, "获取验证码超时，已自动退款");
                } else {
                    return CommonResultDTO.error(Constants.ERROR_NO_CODE, "订单已超时失效");
                }
            } else {
                long remaining = maxWaitSeconds - elapsedSeconds;
                return CommonResultDTO.error(Constants.ERROR_NO_CODE,
                        "系统正在自动获取中(请勿频繁刷新)，剩余等待时间: " + remaining + "秒");
            }
        }
        // ================== 特殊API ==================END

        //允许尝试调用一次
        Map<String, String> context = new HashMap<>();
        context.put("phone", record.getPhoneNumber());
        if (StringUtils.hasText(record.getApiPhoneId())) {
            context.put("id", record.getApiPhoneId());
        }
        Optional<String> codeOpt = smsApiService.fetchVerificationCodeOnce(project, context);
        if (codeOpt.isPresent()) {
            String code = codeOpt.get();
            if (StringUtils.hasText(code) && code.matches("^\\d{4,8}$")) {
                try {
                    self.updateRecordAfterRetrieval(record, true, code);
                    return CommonResultDTO.success("获取成功", code);
                } catch (BusinessException e) {
                    return CommonResultDTO.error(Constants.ERROR_INSUFFICIENT_BALANCE, e.getMessage());
                }
            }
        }
        // 普通API的超时退款检查
        long minutesElapsed = Duration.between(record.getGetNumberTime(), LocalDateTime.now()).toMinutes();
        //如果记录的扣费状态为已扣费并且当前超时15分钟并且记录中没有code就执行退款
        if (record.getCharged() == 1 && minutesElapsed >= 5 && !StringUtils.hasText(record.getCode())) {
            self.updateRecordAfterRetrieval(record, false, null);
            return CommonResultDTO.error(Constants.ERROR_NO_CODE, "获取超时，已自动退款");
        }
        return CommonResultDTO.error(Constants.ERROR_NO_CODE, "尚未获取到验证码，请稍后重试");
    }

    /**
     * 核心逻辑：更新记录状态，并处理 代理返利 或 用户退款
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateRecordAfterRetrieval(NumberRecord record, boolean isSuccess, String result) {
        NumberRecord latestRecord = baseMapper.selectByIdForUpdate(record.getId());

        // 如果已经是成功状态且已有码，不再重复处理
        if (latestRecord.getStatus() == 2 && StringUtils.hasText(latestRecord.getCode())) {
            return;
        }

        if (isSuccess && StringUtils.hasText(result)) {
            // --- 1. 成功逻辑 ---
            if (latestRecord.getCharged() == 2) {
                // 已退款但又拿到了码，执行再扣款
                User user = userService.getById(latestRecord.getUserId());
                if (user.getBalance().compareTo(latestRecord.getPrice()) < 0) {
                    throw new BusinessException("获取成功，但账户余额不足以支付，无法查看验证码");
                }
                LedgerCreationDTO reDeductDto = LedgerCreationDTO.builder()
                        .userId(latestRecord.getUserId())
                        .amount(latestRecord.getPrice())
                        .ledgerType(0)
                        .fundType(FundType.BUSINESS_DEDUCTION)
                        .remark("超时后获码成功，重新扣费")
                        .phoneNumber(latestRecord.getPhoneNumber())
                        .lineId(latestRecord.getLineId())
                        .projectId(latestRecord.getProjectId())
                        .build();
                BigDecimal newBalance = ledgerService.createLedgerAndUpdateBalance(reDeductDto);
                latestRecord.setBalanceAfter(newBalance);
                latestRecord.setCharged(1);
            }
            latestRecord.setCodeReceivedTime(LocalDateTime.now());
            latestRecord.setStatus(2);
            latestRecord.setCode(result);
            latestRecord.setErrorInfo(null);

            this.updateById(latestRecord); // 更新数据库
            cacheManager.cacheRecord(latestRecord);
            cacheManager.evictUser(latestRecord.getUserName());

            userService.updateUserStats(latestRecord.getUserId());
            try {
                userService.processRebates(latestRecord);
            } catch (Exception e) {
                log.error("代理返款失败，记录ID: {}", latestRecord.getId(), e);
            }
        } else {
            // --- 2. 失败/超时逻辑 ---
            if (latestRecord.getCharged() == 1) {
                log.info("号码 {} 获取失败/超时，执行退款", latestRecord.getPhoneNumber());
                latestRecord.setStatus(3);
                latestRecord.setCodeReceivedTime(LocalDateTime.now());
                latestRecord.setCharged(2);
                latestRecord.setErrorInfo("获取超时，自动退款");
                LedgerCreationDTO refundDto = LedgerCreationDTO.builder()
                        .userId(latestRecord.getUserId())
                        .amount(latestRecord.getPrice())
                        .ledgerType(1)
                        .fundType(FundType.ADMIN_OUT_TIME_REBATE)
                        .remark("取码失败/超时退款")
                        .phoneNumber(latestRecord.getPhoneNumber())
                        .lineId(latestRecord.getLineId())
                        .projectId(latestRecord.getProjectId())
                        .build();

                BigDecimal balanceAfterRefund = ledgerService.createLedgerAndUpdateBalance(refundDto);
                latestRecord.setBalanceAfter(balanceAfterRefund);

                this.updateById(latestRecord); // 更新数据库
                cacheManager.cacheRecord(latestRecord);
                cacheManager.evictUser(latestRecord.getUserName());
                userService.updateUserStats(latestRecord.getUserId());
            }
        }

        // --- 3. 释放手机号逻辑 ---
        final String pId = latestRecord.getProjectId();
        final Integer lId = latestRecord.getLineId();
        final String phone = latestRecord.getPhoneNumber();
        final String apiId = latestRecord.getApiPhoneId();
        final boolean isFinalSuccess = (latestRecord.getStatus() == 2);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // 这里不需要改缓存，因为是外部 API 调用
                    Project project = projectService.getProject(pId, lId);
                    if (project != null) {
                        Map<String, String> releaseContext = new HashMap<>();
                        releaseContext.put("phone", phone);
                        releaseContext.put("id", apiId);
                        smsApiService.releasePhoneNumber(project, releaseContext, isFinalSuccess);
                        log.info("订单处理结束，已按[{}]状态触发释放手机号: {}",
                                isFinalSuccess ? "成功" : "失败/超时", phone);
                    }
                } catch (Exception e) {
                    log.warn("事务提交后释放手机号异常(不影响主业务), phone: {}, err: {}", phone, e.getMessage());
                }
            }
        });
    }


    @Override
    public IPage<NumberDTO> listUserNumbers(Long userId, String password, Integer statusFilter, Date startTime, Date endTime, IPage<NumberRecord> page) {
        if (userService.authenticate(userId, password) == null) return null;
        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NumberRecord::getUserId, userId);
        addCommonFilters(wrapper, statusFilter, startTime, endTime, null, null, null, null,null);
        IPage<NumberRecord> recordPage = this.page(page, wrapper);
        return recordPage.convert(this::convertToDTO);
    }

    @Override
    public IPage<NumberDTO> listUserNumbersByUSerName(String userName, String password, Integer statusFilter, Date startTime, Date endTime, IPage<NumberRecord> page) {
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) return null;

        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NumberRecord::getUserId, user.getId());
        addCommonFilters(wrapper, statusFilter, startTime, endTime, null, null, null, null, null);

        final long MAX_RECORDS_LIMIT = 50;
        long actualTotal = this.count(wrapper.clone());
        long effectiveTotal = Math.min(actualTotal, MAX_RECORDS_LIMIT);

        if (page.offset() >= MAX_RECORDS_LIMIT) {
            IPage<NumberRecord> resultPage = new Page<>(page.getCurrent(), page.getSize(), effectiveTotal);
            resultPage.setRecords(Collections.emptyList());
            return resultPage.convert(this::convertToDTO);
        }

        long remainingRecords = MAX_RECORDS_LIMIT - page.offset();
        if (page.getSize() > remainingRecords) {
            page.setSize(remainingRecords);
        }

        IPage<NumberRecord> recordPage = this.page(page, wrapper);
        recordPage.setTotal(effectiveTotal);

        return recordPage.convert(this::convertToDTO);
    }

    @Override
    public IPage<NumberRecord> listAllNumbers(Integer statusFilter, Date startTime, Date endTime,
                                              Long userId, String projectId, String phoneNumber, Integer charged,
                                              IPage<NumberRecord> page, String lineId, String userName) {
        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        if (userName != null && !userName.trim().isEmpty()) {
            List<Long> subUserIds = userService.list(new LambdaQueryWrapper<User>().like(User::getUserName, userName))
                    .stream().map(User::getId).toList();
            if (subUserIds.isEmpty()) {
                return page.setTotal(0).setRecords(Collections.emptyList());
            }
            wrapper.in(NumberRecord::getUserId, subUserIds);
        }
        addCommonFilters(wrapper, statusFilter, startTime, endTime, userId, projectId, phoneNumber, charged, lineId);
        return this.page(page, wrapper);
    }

    @Override
    public NumberRecord getRecordByPhone(String phone) {
        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(NumberRecord::getPhoneNumber,phone);
        return this.getOne(wrapper);
    }

    private boolean hasOngoingRecord(Long userId) {
        return this.count(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getUserId, userId)
                .in(NumberRecord::getStatus, 0, 1)) > 0;
    }

    private void addCommonFilters(LambdaQueryWrapper<NumberRecord> wrapper, Integer status, Date start, Date end,
                                  Long userId, String projectId, String phoneNumber, Integer charged,String lineId) {
        wrapper.eq(status != null, NumberRecord::getStatus, status);
        wrapper.ge(start != null, NumberRecord::getGetNumberTime, start);
        wrapper.le(end != null, NumberRecord::getGetNumberTime, end);
        wrapper.eq(userId != null, NumberRecord::getUserId, userId);
        wrapper.eq(StringUtils.hasText(projectId), NumberRecord::getProjectId, projectId);
        wrapper.eq(StringUtils.hasText(lineId), NumberRecord::getLineId, lineId);
        wrapper.like(StringUtils.hasText(phoneNumber), NumberRecord::getPhoneNumber, phoneNumber);
        wrapper.eq(charged != null, NumberRecord::getCharged, charged);
        wrapper.orderByDesc(NumberRecord::getGetNumberTime);
    }

    private NumberDTO convertToDTO(NumberRecord record) {
        NumberDTO dto = new NumberDTO();
        BeanUtils.copyProperties(record, dto);
        return dto;
    }

    @Override
    public List<String> getPhoneNumbersByProjectId(String projectId) {
        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(NumberRecord::getPhoneNumber);
        wrapper.eq(NumberRecord::getProjectId, projectId);
        List<NumberRecord> records = this.list(wrapper);
        return records.stream()
                .map(NumberRecord::getPhoneNumber)
                .collect(Collectors.toList());
    }
    @Override
    public IPage<ProjectStatisticsDTO> getStatisticsReport(Long operatorId, StatisticsQueryDTO queryDTO) {
        User operator = new User();
        if (operatorId != 0L) {
            operator = userService.getById(operatorId);
            // 建议：增加空指针判断，防止 ID 不存在时报错
            if (operator == null) throw new BusinessException("操作用户不存在");
        }

        // 区分管理员和代理商逻辑
        if (operatorId == 0L) {
            return generateAdminStatistics(queryDTO);
        } else if (operator != null && operator.getIsAgent() == 1) {
            return generateAgentStatistics(operatorId, queryDTO);
        } else {
            return new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        }
    }
    /**
     * 生成管理员统计报表
     * 修复：使用 AS 别名确保 Map Key 统一，避免驼峰配置导致取不到值
     */
    private IPage<ProjectStatisticsDTO> generateAdminStatistics(StatisticsQueryDTO queryDTO) {
        QueryWrapper<NumberRecord> wrapper = new QueryWrapper<>();
        applyFilters(wrapper, queryDTO);

        wrapper.groupBy("project_id", "line_id");

        // 【核心修改】：显式使用 AS 别名 (projectId, lineId)，确保 selectMaps 返回的 Key 是固定的
        wrapper.select(
                "project_id AS projectId",
                "line_id AS lineId",
                "COUNT(id) AS totalRequests",
                "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS successCount",
                // 处理金额 null 的情况
                "COALESCE(SUM(CASE WHEN charged = 1 THEN price ELSE 0 END), 0) AS totalRevenue",
                "COALESCE(SUM(CASE WHEN charged = 1 THEN cost_price ELSE 0 END), 0) AS totalCost"
        );

        Page<Map<String, Object>> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        // 注意：selectMapsPage 在有 Group By 时，total 计数可能会不准（统计的是组数还是行数取决于 MP 版本），通常足以应对报表分页
        IPage<Map<String, Object>> aggregatedDataPage = this.pageMaps(page, wrapper);

        if (CollectionUtils.isEmpty(aggregatedDataPage.getRecords())) {
            return new Page<>(queryDTO.getCurrent(), queryDTO.getSize(), aggregatedDataPage.getTotal());
        }

        Map<String, String> projectNames = projectService.list().stream()
                .collect(Collectors.toMap(Project::getProjectId, Project::getProjectName, (p1, p2) -> p1));

        List<ProjectStatisticsDTO> dtoList = processAggregatedData(aggregatedDataPage.getRecords(), null, projectNames);

        IPage<ProjectStatisticsDTO> resultPage = new Page<>(aggregatedDataPage.getCurrent(), aggregatedDataPage.getSize(), aggregatedDataPage.getTotal());
        resultPage.setRecords(dtoList);
        return resultPage;
    }

    /**
     * 生成代理商统计报表
     * 修复：使用 AS 别名，优化成本匹配逻辑
     */
    private IPage<ProjectStatisticsDTO> generateAgentStatistics(Long agentId, StatisticsQueryDTO queryDTO) {
        // 获取下级用户 ID 列表
        List<Long> subUserIds = userService.list(new LambdaQueryWrapper<User>().eq(User::getParentId, agentId))
                .stream().map(User::getId).collect(Collectors.toList());

        if (subUserIds.isEmpty()) return new Page<>(queryDTO.getCurrent(), queryDTO.getSize());

        // 获取代理商成本配置
        // key 建议统一转为 String 避免类型问题
        Map<String, BigDecimal> agentCostMap = userProjectLineService.list(
                new LambdaQueryWrapper<UserProjectLine>().eq(UserProjectLine::getUserId, agentId)
        ).stream().collect(Collectors.toMap(
                line -> line.getProjectId() + "-" + line.getLineId(),
                UserProjectLine::getAgentPrice,
                (v1, v2) -> v1 // 处理重复配置，取第一个
        ));

        QueryWrapper<NumberRecord> wrapper = new QueryWrapper<>();
        wrapper.in("user_id", subUserIds);
        applyFilters(wrapper, queryDTO);

        wrapper.groupBy("project_id", "line_id");

        // 【核心修改】：显式使用 AS 别名
        wrapper.select(
                "project_id AS projectId",
                "line_id AS lineId",
                "COUNT(id) AS totalRequests",
                "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS successCount",
                "COALESCE(SUM(CASE WHEN charged = 1 THEN price ELSE 0 END), 0) AS totalRevenue"
        );

        Page<Map<String, Object>> page = new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        IPage<Map<String, Object>> aggregatedDataPage = this.pageMaps(page, wrapper);

        if (CollectionUtils.isEmpty(aggregatedDataPage.getRecords())) {
            return new Page<>(queryDTO.getCurrent(), queryDTO.getSize(), aggregatedDataPage.getTotal());
        }

        Map<String, String> projectNames = projectService.list().stream()
                .collect(Collectors.toMap(Project::getProjectId, Project::getProjectName, (p1, p2) -> p1));

        List<ProjectStatisticsDTO> dtoList = processAggregatedData(aggregatedDataPage.getRecords(), agentCostMap, projectNames);

        IPage<ProjectStatisticsDTO> resultPage = new Page<>(aggregatedDataPage.getCurrent(), aggregatedDataPage.getSize(), aggregatedDataPage.getTotal());
        resultPage.setRecords(dtoList);
        return resultPage;
    }

    private void applyFilters(QueryWrapper<NumberRecord> wrapper, StatisticsQueryDTO queryDTO) {
        if (StringUtils.hasText(queryDTO.getProjectName())) {
            List<String> projectIds = projectService.list(
                    new LambdaQueryWrapper<Project>().like(Project::getProjectName, queryDTO.getProjectName())
            ).stream().map(Project::getProjectId).collect(Collectors.toList());
            if (projectIds.isEmpty()) { wrapper.eq("1", 0); return; }
            wrapper.in("project_id", projectIds);
        }
        if (StringUtils.hasText(queryDTO.getProjectId())) wrapper.eq("project_id", queryDTO.getProjectId());
        if (queryDTO.getLineId() != null) wrapper.eq("line_id", queryDTO.getLineId());
        LocalDateTime startTime = parseAndAdjustDateTime(queryDTO.getStartTime(), false);
        if (startTime != null) wrapper.ge("get_number_time", startTime);
        LocalDateTime endTime = parseAndAdjustDateTime(queryDTO.getEndTime(), true);
        if (endTime != null) wrapper.le("get_number_time", endTime);
    }

    private LocalDateTime parseAndAdjustDateTime(String dateTimeStr, boolean isEndDate) {
        if (!StringUtils.hasText(dateTimeStr)) return null;
        try {
            if (dateTimeStr.contains("T") && dateTimeStr.endsWith("Z")) {
                return ZonedDateTime.parse(dateTimeStr).toLocalDateTime();
            } else if (dateTimeStr.contains(" ")) {
                String standardizedStr = dateTimeStr.length() == 16 ? dateTimeStr + ":00" : dateTimeStr;
                return LocalDateTime.parse(standardizedStr.replace(" ", "T"));
            } else {
                LocalDate date = LocalDate.parse(dateTimeStr);
                return isEndDate ? date.atTime(23, 59, 59) : date.atStartOfDay();
            }
        } catch (DateTimeParseException e) {
            log.warn("无法解析的日期时间格式: '{}'", dateTimeStr, e);
            return null;
        }
    }

    /**
     * 处理聚合数据并转换为 DTO
     * 修复：从 Map 取值时使用别名，增加空值安全处理
     */
    private List<ProjectStatisticsDTO> processAggregatedData(List<Map<String, Object>> aggregatedData, Map<String, BigDecimal> agentCostMap, Map<String, String> projectNames) {
        // 使用 LinkedHashMap 保持数据库排序（通常按 Group By 顺序）
        Map<String, ProjectStatisticsDTO> projectReportMap = new LinkedHashMap<>();

        for (Map<String, Object> lineData : aggregatedData) {
            // 1. 安全获取 projectId 和 lineId，对应 SQL 中的 AS 别名
            String projectId = (String) lineData.get("projectId");
            Object lineIdObj = lineData.get("lineId");

            // 过滤无效数据，防止报错或合并到 null 分组
            if (!StringUtils.hasText(projectId)) continue;

            Integer lineId = 0;
            if (lineIdObj != null) {
                if (lineIdObj instanceof Number) {
                    lineId = ((Number) lineIdObj).intValue();
                } else {
                    try {
                        lineId = Integer.parseInt(lineIdObj.toString());
                    } catch (NumberFormatException ignored) {}
                }
            }

            // 2. 解析统计数值，增加空指针防御
            long totalRequests = getLongFromMap(lineData, "totalRequests");
            long successCount = getLongFromMap(lineData, "successCount");
            BigDecimal totalRevenue = getBigDecimalFromMap(lineData, "totalRevenue");

            // 3. 归类到 ProjectDTO
            ProjectStatisticsDTO projectDTO = projectReportMap.computeIfAbsent(projectId, k -> {
                ProjectStatisticsDTO dto = new ProjectStatisticsDTO();
                dto.setProjectId(k);
                dto.setProjectName(projectNames.getOrDefault(k, "未知项目(" + k + ")"));
                dto.setTotalRequests(0L);
                dto.setSuccessCount(0L);
                dto.setTotalRevenue(BigDecimal.ZERO);
                dto.setTotalCost(BigDecimal.ZERO);
                dto.setLineDetails(new ArrayList<>());
                return dto;
            });

            // 4. 计算成本
            BigDecimal lineTotalCost;
            if (agentCostMap != null) {
                // 代理商模式：查配置表
                String priceKey = projectId + "-" + lineId;
                BigDecimal agentPricePerSuccess = agentCostMap.getOrDefault(priceKey, BigDecimal.ZERO);
                lineTotalCost = agentPricePerSuccess.multiply(new BigDecimal(successCount));
            } else {
                // 管理员模式：取 SQL 结果
                lineTotalCost = getBigDecimalFromMap(lineData, "totalCost");
            }

            // 5. 构建 LineDTO
            LineStatisticsDTO lineDTO = new LineStatisticsDTO();
            lineDTO.setProjectId(projectId);
            lineDTO.setLineId(lineId);
            lineDTO.setProjectName(projectDTO.getProjectName()); // 冗余一份项目名方便前端
            lineDTO.setTotalRequests(totalRequests);
            lineDTO.setSuccessCount(successCount);
            lineDTO.setSuccessRate(totalRequests > 0 ? (double) successCount * 100.0 / totalRequests : 0.0);
            lineDTO.setTotalRevenue(totalRevenue);
            lineDTO.setTotalCost(lineTotalCost);
            lineDTO.setTotalProfit(totalRevenue.subtract(lineTotalCost));

            projectDTO.getLineDetails().add(lineDTO);

            // 6. 累加到项目总计
            projectDTO.setTotalRequests(projectDTO.getTotalRequests() + totalRequests);
            projectDTO.setSuccessCount(projectDTO.getSuccessCount() + successCount);
            projectDTO.setTotalRevenue(projectDTO.getTotalRevenue().add(totalRevenue));
            projectDTO.setTotalCost(projectDTO.getTotalCost().add(lineTotalCost));
        }

        // 7. 计算项目级汇总指标
        projectReportMap.values().forEach(p -> {
            p.setLineCount(p.getLineDetails().size());
            p.setSuccessRate(p.getTotalRequests() > 0 ? (double) p.getSuccessCount() * 100.0 / p.getTotalRequests() : 0.0);
            p.setTotalProfit(p.getTotalRevenue().subtract(p.getTotalCost()));
        });

        return new ArrayList<>(projectReportMap.values());
    }

    // 辅助方法：安全从 Map 获取 Long
    private long getLongFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return 0L;
        if (val instanceof Number) return ((Number) val).longValue();
        return Long.parseLong(val.toString());
    }

    // 辅助方法：安全从 Map 获取 BigDecimal
    private BigDecimal getBigDecimalFromMap(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return BigDecimal.ZERO;
        return new BigDecimal(val.toString());
    }

    @Override
    public IPage<NumberDTO> listSubordinateRecordsForAgent(Long agentId, SubordinateNumberRecordQueryDTO queryDTO) {
        List<Long> targetUserIds = new ArrayList<>();
        if (StringUtils.hasText(queryDTO.getUserName())) {
            User targetUser = userService.getOne(new LambdaQueryWrapper<User>().eq(User::getUserName, queryDTO.getUserName()));
            if (targetUser != null && agentId.equals(targetUser.getParentId())) targetUserIds.add(targetUser.getId());
            else return new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
        } else {
            List<User> subUsers = userService.list(new LambdaQueryWrapper<User>().select(User::getId).eq(User::getParentId, agentId));
            if (subUsers.isEmpty()) return new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
            targetUserIds = subUsers.stream().map(User::getId).collect(Collectors.toList());
        }

        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(NumberRecord::getUserId, targetUserIds);
        if (StringUtils.hasText(queryDTO.getProjectName())) {
            List<String> projectIds = projectService.list(new LambdaQueryWrapper<Project>().like(Project::getProjectName, queryDTO.getProjectName())).stream().map(Project::getProjectId).collect(Collectors.toList());
            if (projectIds.isEmpty()) return new Page<>(queryDTO.getCurrent(), queryDTO.getSize());
            wrapper.in(NumberRecord::getProjectId, projectIds);
        }
        wrapper.eq(StringUtils.hasText(queryDTO.getProjectId()), NumberRecord::getProjectId, queryDTO.getProjectId());
        wrapper.eq(queryDTO.getLineId() != null, NumberRecord::getLineId, queryDTO.getLineId());
        wrapper.like(StringUtils.hasText(queryDTO.getPhoneNumber()), NumberRecord::getPhoneNumber, queryDTO.getPhoneNumber());
        wrapper.eq(queryDTO.getStatus() != null, NumberRecord::getStatus, queryDTO.getStatus());
        wrapper.eq(queryDTO.getCharged() != null, NumberRecord::getCharged, queryDTO.getCharged());
        LocalDateTime startTime = parseAndAdjustDateTime(queryDTO.getStartTime(), false);
        LocalDateTime endTime = parseAndAdjustDateTime(queryDTO.getEndTime(), true);
        wrapper.ge(startTime != null, NumberRecord::getGetNumberTime, startTime);
        wrapper.le(endTime != null, NumberRecord::getGetNumberTime, endTime);
        wrapper.orderByDesc(NumberRecord::getGetNumberTime);
        return this.page(new Page<>(queryDTO.getCurrent(), queryDTO.getSize()), wrapper).convert(this::convertToDTO);
    }

    @Override
    public IPage<UserLineStatsDTO> getUserLineStats(UserLineStatsRequestDTO requestDTO, Long agentId) {
        // -----------------------------------------------------------------
        // 步骤 1: 处理用户关联过滤 (如果需要按用户名或代理商筛选)
        // -----------------------------------------------------------------
        List<Long> filterUserIds = null;
        boolean needUserFilter = StringUtils.hasText(requestDTO.getUserName()) || agentId != null;

        if (needUserFilter) {
            // 使用 Lambda 查询用户表
            List<User> users = userService.lambdaQuery()
                    .select(User::getId) // 只查ID，优化性能
                    .like(StringUtils.hasText(requestDTO.getUserName()), User::getUserName, requestDTO.getUserName())
                    .eq(agentId != null, User::getParentId, agentId)
                    .list();

            if (CollectionUtils.isEmpty(users)) {
                // 如果筛选条件下没有找到用户，直接返回空分页
                return new Page<>(requestDTO.getPage(), requestDTO.getSize());
            }
            filterUserIds = users.stream().map(User::getId).collect(Collectors.toList());
        }

        // -----------------------------------------------------------------
        // 步骤 2: 构建分组统计查询 (混合 Lambda 和 String Select)
        // -----------------------------------------------------------------
        // 注意：这里必须用 QueryWrapper 而不是 LambdaQueryWrapper，因为 .select() 需要写聚合函数
        QueryWrapper<NumberRecord> queryWrapper = new QueryWrapper<>();

        // 2.1 定义 Select 聚合字段
        // 使用数据库函数 SUM(CASE...) 来统计成功的验证码数
        queryWrapper.select(
                "user_id",
                "project_id",
                "line_id",
                "COUNT(*) as totalNumbers",
                "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) as totalCodes"
        );

        // 2.2 转换到 Lambda 模式添加 Where 条件 (保持类型安全)
        queryWrapper.lambda()
                .eq(StringUtils.hasText(requestDTO.getProjectId()), NumberRecord::getProjectId, requestDTO.getProjectId())
                .ge(requestDTO.getStartTime() != null, NumberRecord::getGetNumberTime, requestDTO.getStartTime())
                .le(requestDTO.getEndTime() != null, NumberRecord::getGetNumberTime, requestDTO.getEndTime())
                // 如果步骤1计算出了 ID 列表，这里进行 IN 查询
                .in(needUserFilter, NumberRecord::getUserId, filterUserIds);

        // 2.3 添加分组
        queryWrapper.groupBy("user_id", "project_id", "line_id");

        // 2.4 添加排序 (按取号量降序)
        queryWrapper.orderByDesc("totalNumbers");

        // -----------------------------------------------------------------
        // 步骤 3: 执行查询并转换结果 (selectMapsPage)
        // -----------------------------------------------------------------
        Page<Map<String, Object>> mapPage = new Page<>(requestDTO.getPage(), requestDTO.getSize());
        // 使用 Mybatis-Plus 的 selectMapsPage，返回 Map 列表
        IPage<Map<String, Object>> resultPage = this.baseMapper.selectMapsPage(mapPage, queryWrapper);

        // -----------------------------------------------------------------
        // 步骤 4: Map 转 DTO 并 补全用户名
        // -----------------------------------------------------------------
        List<UserLineStatsDTO> dtoList = new ArrayList<>();

        // 收集所有结果中的 userId，用于批量查询用户名
        Set<Long> resultUserIds = new HashSet<>();

        for (Map<String, Object> map : resultPage.getRecords()) {
            UserLineStatsDTO dto = new UserLineStatsDTO();
            // 注意：Map 的 Key 通常是 select 里的字段名
            Long uId = Long.valueOf(map.get("user_id").toString());
            dto.setUserId(uId);
            dto.setProjectId((String) map.get("project_id"));
            dto.setLineId((Integer) map.get("line_id"));
            // 处理 Long/BigDecimal 类型转换 (数据库返回的 Count/Sum 类型可能不同)
            dto.setTotalNumbers(new BigDecimal(map.get("totalNumbers").toString()).longValue());
            dto.setTotalCodes(new BigDecimal(map.get("totalCodes").toString()).longValue());

            // 计算成功率
            dto.calculateRate();

            dtoList.add(dto);
            resultUserIds.add(uId);
        }
        if (!resultUserIds.isEmpty()) {
            Map<Long, String> userMap = userService.listByIds(resultUserIds).stream()
                    .collect(Collectors.toMap(User::getId, User::getUserName));

            for (UserLineStatsDTO dto : dtoList) {
                dto.setUserName(userMap.getOrDefault(dto.getUserId(), "未知用户"));
            }
        }
        Page<UserLineStatsDTO> finalPage = new Page<>(requestDTO.getPage(), requestDTO.getSize());
        finalPage.setTotal(resultPage.getTotal());
        finalPage.setRecords(dtoList);

        return finalPage;
    }
    @Transactional(rollbackFor = Exception.class)
    @Override
    public int batchRefundByQuery(BatchRefundQueryDTO queryDTO) {
        if (queryDTO.getStartTime() == null || queryDTO.getEndTime() == null) {
            throw new BusinessException("必须指定退款的时间范围");
        }
        if (queryDTO.getCharged() == null || queryDTO.getStatus() == null ){
            throw new BusinessException(0,"请求参数中的状态码不正确");
        }

        // 1. 查询符合条件的记录
        // 条件：时间范围内 + 状态为0(待取码) + 扣费状态为1(已扣费)
        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(NumberRecord::getGetNumberTime, queryDTO.getStartTime())
                .le(NumberRecord::getGetNumberTime, queryDTO.getEndTime())
                .eq(NumberRecord::getStatus, queryDTO.getStatus()) // 0: 待取码 (根据你的代码逻辑)
                .eq(NumberRecord::getCharged, queryDTO.getCharged()); // 1: 已扣费

        if (StringUtils.hasText(queryDTO.getProjectId())) {
            wrapper.eq(NumberRecord::getProjectId, queryDTO.getProjectId());
        }

        List<NumberRecord> records = this.list(wrapper);
        if (CollectionUtils.isEmpty(records)) {
            return 0;
        }

        int successCount = 0;
        List<NumberRecord> recordsToUpdate = new ArrayList<>();

        // 2. 遍历每一条记录进行退款
        for (NumberRecord record : records) {
            try {
                // A. 执行资金退还 (使用统一的 createLedgerAndUpdateBalance)
                LedgerCreationDTO refundLedger = LedgerCreationDTO.builder()
                        .userId(record.getUserId())
                        .amount(record.getPrice()) // 退还该记录实际扣除的金额
                        .ledgerType(1) // 1: 入账 (加钱)
                        .fundType(FundType.ADMIN_OPERATION) // 假设你有 REFUND 枚举，如果没有，用 ADMIN_RECHARGE 或其他
                        .remark("记录异常批量退款，ID: " + record.getId())
                        .projectId(record.getProjectId())
                        .lineId(record.getLineId())
                        .phoneNumber(record.getPhoneNumber())
                        .build();

                userLedgerService.createLedgerAndUpdateBalance(refundLedger);
                record.setStatus(4);
                record.setCharged(2);
                record.setRemark(record.getRemark() + " [管理员批量退款]");
                recordsToUpdate.add(record);
                successCount++;
            } catch (Exception e) {
                log.error("记录 {} 退款失败: {}", record.getId(), e.getMessage());
                throw new BusinessException("退款中断，记录ID " + record.getId() + " 处理失败：" + e.getMessage());
            }
        }

        // 3. 批量更新记录状态
        if (!recordsToUpdate.isEmpty()) {
            this.updateBatchById(recordsToUpdate);
        }

        return successCount;
    }

    /**
     * 物理删除号码记录（按天数删除）
     *
     * @param operatorId   当前操作人ID
     * @param targetUserId 目标用户ID
     * @param days         保留天数
     * @param isAdmin      是否为管理员操作
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteNumberRecordByDays(Long operatorId, Long targetUserId, Integer days, boolean isAdmin) {
        // 1. 参数校验
        if (days == null || days < 0) {
            throw new BusinessException("天数参数非法");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deleteBeforeTime;
        // 2. 权限与天数限制
        if (!isAdmin) {
            // 普通用户校验
            if (targetUserId == null || !operatorId.equals(targetUserId)) {
                throw new BusinessException("权限不足：只能清理自己的记录");
            }
        }
        deleteBeforeTime = now.minusDays(days);
        // 3. 构造删除条件
        LambdaQueryWrapper<NumberRecord> wrapper = new LambdaQueryWrapper<>();
        if (targetUserId != null) {
            wrapper.eq(NumberRecord::getUserId, targetUserId);
        }
        // 核心条件：取号时间 <= 临界点
        wrapper.le(NumberRecord::getCreateTime, deleteBeforeTime);
        wrapper.notIn(NumberRecord::getStatus, 0, 1);
        // 4. 执行清理
        long count = this.count(wrapper);
        if (count == 0) {
            return;
        }
        int rows = baseMapper.delete(wrapper);
        // 5. 记录日志
        log.info("【号码记录清理】操作人: {}, 目标用户: {}, 保留天数: {}, 清理条数: {}, 临界时间: {}",
                isAdmin ? "管理员(" + operatorId + ")" : "用户(" + operatorId + ")",
                targetUserId == null ? "全系统" : targetUserId,
                days,
                rows,
                deleteBeforeTime);
    }

    @Override
    public CommonResultDTO<String> releasePhoneNumber(String userName, String password, String phoneNumber, String projectId, String lineId, boolean isSuccess) {
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户验证失败");
        NumberRecord record = this.getOne(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getUserId, user.getId())
                .eq(NumberRecord::getPhoneNumber, phoneNumber)
                .eq(NumberRecord::getProjectId, projectId)
                .in(NumberRecord::getStatus, 0, 1)
                .last("LIMIT 1"));

        if (record == null) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "未找到进行中的订单或已处理");
        }
        // 1. 获取项目配置
        Project project = projectService.getProject(projectId, Integer.valueOf(lineId));
        if (project == null) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR,"没有该项目");
        }
        // 2. 构造释放参数上下文
        Map<String, String> context = new HashMap<>();
        context.put("phone", phoneNumber);
        context.put("id", record.getApiPhoneId());
        context.put("token",project.getAuthTokenValue());
        try {
            smsApiService.releasePhoneNumber(project, context,isSuccess);
        } catch (Exception e) {
            log.error("调用第三方释放接口异常: {}", e.getMessage());
        }
        return CommonResultDTO.success("释放成功", phoneNumber);
    }
}