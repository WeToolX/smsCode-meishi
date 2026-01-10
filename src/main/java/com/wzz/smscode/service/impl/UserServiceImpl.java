package com.wzz.smscode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.wzz.smscode.cacheManager.NumberRecordCacheManager;
import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.common.Constants;
import com.wzz.smscode.dto.BatchChargeRequestDTO;
import com.wzz.smscode.dto.CreatDTO.LedgerCreationDTO;
import com.wzz.smscode.dto.CreatDTO.UserCreateDTO;
import com.wzz.smscode.dto.EntityDTO.UserDTO;
import com.wzz.smscode.dto.LoginDTO.UserLoginDto;
import com.wzz.smscode.dto.ResultDTO.UserResultDTO;
import com.wzz.smscode.dto.agent.AgentDashboardStatsDTO;
import com.wzz.smscode.dto.agent.AgentProjectLineUpdateDTO;
import com.wzz.smscode.dto.project.ProjectPriceDTO;
import com.wzz.smscode.dto.project.ProjectPriceInfoDTO;
import com.wzz.smscode.dto.project.ProjectPriceSummaryDTO;
import com.wzz.smscode.dto.project.SubUserProjectPriceDTO;
import com.wzz.smscode.dto.update.UpdateUserDto;
import com.wzz.smscode.dto.update.UserUpdateDtoByUser;
import com.wzz.smscode.dto.update.UserUpdatePasswardDTO;
import com.wzz.smscode.entity.*;
import com.wzz.smscode.enums.FundType;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.mapper.NumberRecordMapper;
import com.wzz.smscode.mapper.UserMapper;
import com.wzz.smscode.service.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
    @Autowired @Lazy private ProjectService projectService;
    @Autowired private UserLedgerService ledgerService;
    @Autowired private UserMapper userMapper;
    @Autowired private NumberRecordMapper numberRecordMapper;
    @Autowired private UserProjectLineService userProjectLineService;
    @Autowired @Lazy private PriceTemplateService priceTemplateService;
    @Autowired private UserLedgerService userLedgerService;

    @Autowired
    private NumberRecordCacheManager cacheManager; // 注入缓存管理器




    @Override
    public User authenticate(Long userId, String password) {
        User user = this.getById(userId);
        if (user == null) return null;
        return user;
    }
    /**
     * 获取代理仪表盘的统计数据
     * @param agentId 代理的用户ID
     * @return 包含核心统计数据的DTO
     */
    @Override
    public AgentDashboardStatsDTO getAgentDashboardStats(Long agentId) {
        AgentDashboardStatsDTO statsDTO = new AgentDashboardStatsDTO();
        User agent = this.getById(agentId);
        if (agent == null) {
            throw new BusinessException("代理用户不存在");
        }
        statsDTO.setMyBalance(agent.getBalance());
        LambdaQueryWrapper<User> subUsersQuery = new LambdaQueryWrapper<User>().eq(User::getParentId, agentId);
        List<User> subUsers = this.list(subUsersQuery);
        if (subUsers.isEmpty()) {
            // 如果没有下级，直接返回默认值
            statsDTO.setTotalSubUsers(0L);
            statsDTO.setTodaySubUsersRecharge(BigDecimal.ZERO);
            statsDTO.setSubUsersCodeRate(0.0);
            return statsDTO;
        }
        // 3. 计算"我的下级总人数"
        statsDTO.setTotalSubUsers((long) subUsers.size());
        List<Long> subUserIds = subUsers.stream().map(User::getId).collect(Collectors.toList());
        // 4. 计算"我的下级所有人今日充值"
        LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
        LambdaQueryWrapper<UserLedger> ledgerWrapper = new LambdaQueryWrapper<>();
        ledgerWrapper.in(UserLedger::getUserId, subUserIds)
                .eq(UserLedger::getFundType, FundType.AGENT_RECHARGE.getCode())
                .eq(UserLedger::getLedgerType, 1)
                .ge(UserLedger::getTimestamp, todayStart)
                .le(UserLedger::getTimestamp, todayEnd);
        List<UserLedger> todayRechargeLedgers = userLedgerService.list(ledgerWrapper);
        BigDecimal totalRecharge = todayRechargeLedgers.stream()
                .map(UserLedger::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        statsDTO.setTodaySubUsersRecharge(totalRecharge);
        // 5. 计算"我的下级的回码率"
        long totalGetCount = subUsers.stream().mapToLong(User::getTotalGetCount).sum();
        long totalCodeCount = subUsers.stream().mapToLong(User::getTotalCodeCount).sum();
        if (totalGetCount == 0) {
            statsDTO.setSubUsersCodeRate(0.0);
        } else {
            double rate = ((double) totalCodeCount / totalGetCount) * 100.0;
            // 格式化为两位小数
            statsDTO.setSubUsersCodeRate(BigDecimal.valueOf(rate)
                    .setScale(2, RoundingMode.HALF_UP).doubleValue());
        }

        statsDTO.setTotalProfit(userLedgerService.getTotalProfitByUserId(agentId)==null?BigDecimal.ZERO:userLedgerService.getTotalProfitByUserId(agentId));

        return statsDTO;
    }

    @Override
    public User findAndLockById(Long userId) {
        return baseMapper.selectByIdForUpdate(userId);
    }

    /**
     * 核心加锁方法：通过用户名查询并锁定用户行
     */
    public User findAndLockByUserName(String userName) {
        return userMapper.selectByUserNameForUpdate(userName);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public User AgentLogin(String username, String password) {
        User user = findAndLockByUserName(username);
        if (user == null) {
            return null;
        }
        if (password.equals(user.getPassword())) {
            user.setLastLoginTime(LocalDateTime.now());
            boolean updateSuccess = this.updateById(user);
            if (!updateSuccess) {
                log.error("代理用户 {} 登录时间更新失败", username);
            }
            return user;
        }
        return null;
    }


    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateUserByAgent(UserUpdateDtoByUser userDTO, long operatorId) {
        User user = findAndLockById(userDTO.getId());
        if (user == null) throw new BusinessException(0, "该用户不存在！");

        Long oldTemplateId = user.getTemplateId();
        Long newTemplateId = userDTO.getTemplateId();

        user.setUserName(userDTO.getUsername());
        if (StringUtils.hasText(userDTO.getPassword())) {
            user.setPassword(userDTO.getPassword());
        }
        user.setIsAgent(userDTO.isAgent() ? 1 : 0);
        user.setStatus(userDTO.getStatus());

        // 更新黑名单
        if (userDTO.getBlacklistedProjects() != null) {
            user.setProjectBlacklist(String.join(",", userDTO.getBlacklistedProjects()));
        }

        // 更新模板关联
        if (newTemplateId != null && !newTemplateId.equals(oldTemplateId)) {
            // 校验新模板
            if (priceTemplateService.getById(newTemplateId) == null) {
                throw new BusinessException("新指定的价格模板不存在");
            }
            user.setTemplateId(newTemplateId);

            // 维护模板表中的 userId 列表
            if (oldTemplateId != null) {
                priceTemplateService.removeUserFromTemplate(oldTemplateId, user.getId());
            }
            priceTemplateService.addUserToTemplate(newTemplateId, user.getId());
        }

        cacheManager.evictUser(user.getUserName());
        return updateById(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePassWardByUserId(UpdateUserDto id) {
        User user = findAndLockById(id.getUserId());
        if (user==null){
            throw new BusinessException(0,"用户查询失败");
        }
        user.setPassword(id.getUserPassword());
        cacheManager.evictUser(user.getUserName());
        return updateById(user);
    }



    /**
     * 用户更新密码
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean updatePassWardByUserName(UserUpdatePasswardDTO updateUserDto) {
        // 1. 获取并锁定用户记录
        User user = findAndLockByUserName(updateUserDto.getUserName());
        if (user == null){
            throw new BusinessException(0,"没有该用户！");
        }

        // 2. 校验旧密码
        if (!user.getPassword().equals(updateUserDto.getOldPassword())){
            throw new BusinessException(0,"用户名旧密码不正确");
        }

        // 3. 设置新密码并更新数据库
        user.setPassword(updateUserDto.getNewPassword());
        boolean success = updateById(user);

        // 4. 【关键】如果更新成功，立即清除该用户的 Redis 缓存
        if (success) {
            // 清除缓存后，下次 authenticateUserByUserName 就会被迫查库，从而校验新密码
            cacheManager.evictUser(user.getUserName());
            log.info("用户 {} 修改密码成功，已同步清除 Redis 缓存", user.getUserName());
        }

        return success;
    }

    /**
     * 默认走缓存的登录验证（兼容原有逻辑）
     */
    @Override
    public User authenticateUserByUserName(String userName, String password) {
        // 默认传入 true
        return this.authenticateUserByUserName(userName, password, true);
    }

    /**
     * 带缓存控制的登录验证
     * @param useCache 是否走缓存：true-先查缓存，false-直接查数据库
     */
    @Override
    public User authenticateUserByUserName(String userName, String password, boolean useCache) {
        User user = null;
        if (useCache) {
            user = cacheManager.getUser(userName);
            log.info("从缓存获取{}信息：{}", userName, user);
            if (user != null) {
                // 校验密码
                if (!password.equals(user.getPassword())) {
                    return null;
                }
                // 校验状态
                if (user.getStatus() != 0) {
                    log.info("用户 {} 状态异常: {}", userName, user.getStatus());
                    return null;
                }
                // 缓存命中并校验通过，直接更新登录时间并返回
                updateLastLoginTime(user.getId(), userName);
                return user;
            }
        }

        // 2. 缓存未命中或强制不走缓存，查询数据库
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserName, userName);
        try {
            user = this.getOne(queryWrapper, true);
        } catch (TooManyResultsException | MyBatisSystemException e) {
            log.error("数据库数据异常：用户名 '{}' 存在重复记录", userName, e);
            throw new BusinessException(0, "账号状态异常，请联系管理员");
        }

        // 3. 数据库校验
        if (user == null || !password.equals(user.getPassword())) {
            return null;
        }
        if (user.getStatus() != 0) {
            return null;
        }
        updateLastLoginTime(user.getId(), userName);
        cacheManager.cacheUser(user);
        return user;
    }

    /**
     * 提取更新登录时间的私有方法，减少冗余
     */
    private void updateLastLoginTime(Long userId, String userName) {
        try {
            this.update(new LambdaUpdateWrapper<User>()
                    .eq(User::getId, userId)
                    .set(User::getLastLoginTime, LocalDateTime.now())
            );
        } catch (Exception e) {
            log.error("更新用户 {} 登录时间失败", userName, e);
        }
    }


    // 在 UserServiceImpl 类中添加以下方法

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteSubUsersBatch(List<Long> userIds, Long agentId) {
        if (CollectionUtils.isEmpty(userIds)) {
            throw new BusinessException("请选择要删除的用户");
        }

        // 1. 安全校验：查询这些ID中，真正属于该代理的下级用户ID
        // 防止代理恶意传入非自己下级的ID进行删除
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(User::getId, userIds)
                .eq(User::getParentId, agentId);

        List<User> validSubUsers = this.list(queryWrapper);

        if (CollectionUtils.isEmpty(validSubUsers)) {
            throw new BusinessException("未找到属于您的下级用户，删除失败");
        }

        // 提取验证通过的ID列表
        List<Long> validIds = validSubUsers.stream()
                .map(User::getId)
                .collect(Collectors.toList());

        // 2. 删除关联数据：用户项目价格配置 (UserProjectLine)
        // 如果不删除，该表会产生大量无主的死数据
        LambdaQueryWrapper<UserProjectLine> lineWrapper = new LambdaQueryWrapper<>();
        lineWrapper.in(UserProjectLine::getUserId, validIds);
        userProjectLineService.remove(lineWrapper);


        // 4. 批量删除用户主体
        boolean success = this.removeByIds(validIds);

        if (!success) {
            throw new BusinessException("删除用户操作失败");
        }

        log.info("代理 {} 批量删除了 {} 个下级用户，IDs: {}", agentId, validIds.size(), validIds);
    }

    @Override
    public CommonResultDTO<BigDecimal> getBalance(String userName, String password) {
        User user = authenticateUserByUserName(userName, password,false);
        if (user == null) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
        }
        return CommonResultDTO.success("查询成功", user.getBalance());
    }


    /* 新建一个简单 VO，用来装需要的一次性数据 */
    @Data
    @AllArgsConstructor
    private static class ProjMeta {
        String projectName;
        BigDecimal costPrice;
    }

    /**
     * 根据用户名查询用户列表。
     * @param userName 用户名
     * @return 用户列表。如果没有找到，返回一个空列表 (empty list)。
     */
    public List<User> listByUserName(String userName){
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserName, userName);
        return this.list(queryWrapper); // 使用 list() 方法代替 getOne()
    }



    @Transactional(rollbackFor = Exception.class)
    @Override
    public boolean createUser(UserCreateDTO dto, Long operatorId) {
        // 1. 校验操作员身份 (与您之前的代码相同)
        boolean isAdmin = (operatorId == 0L);
        User operator;
        if (isAdmin) {
            operator = new User();
            operator.setId(0L);
        } else {
            operator = this.getById(operatorId);
            if (operator == null) throw new BusinessException("操作员不存在");
            if (operator.getIsAgent() != 1) throw new BusinessException("无权创建用户");
        }
        List<User> existingUsers = this.listByUserName(dto.getUsername());

        // 校验模板是否存在
        if (dto.getTemplateId() == null) {
            throw new BusinessException("必须指定价格模板");
        }
        PriceTemplate template = priceTemplateService.getById(dto.getTemplateId());
        if (template == null) {
            throw new BusinessException("指定的价格模板不存在");
        }

        // 判断列表是否不为空
        if (!existingUsers.isEmpty()) {
            // 您甚至可以记录一个更详细的日志
            if (existingUsers.size() > 1) {
                log.warn("数据校验警告：数据库中存在 {} 个名为 '{}' 的重复用户！", existingUsers.size(), dto.getUsername());
            }
            throw new BusinessException(0, "该用户名已存在！");
        }

        // 2. 构造用户实体，余额初始化为0
        User user = new User();
        user.setUserName(dto.getUsername());
        user.setPassword(dto.getPassword()); // 注意：密码应该加密存储
        Long targetParentId = operatorId; // 默认为当前操作员(管理员是0)

        // 如果是管理员操作，并且前端传来了指定的 parentId
        if (isAdmin && dto.getParentId() != null && dto.getParentId() != 0L) {
            User assignedAgent = this.getById(dto.getParentId());
            if (assignedAgent == null || assignedAgent.getIsAgent() != 1) {
                throw new BusinessException("指定的归属代理不存在或不具备代理权限");
            }
            targetParentId = dto.getParentId();
        }

        user.setParentId(targetParentId);
        user.setIsAgent(dto.getIsAgent() ? 1 : 0);
        user.setStatus(0);
        user.setBalance(BigDecimal.ZERO);

        log.info("创建的用户：{}", user);

        // 设置模板ID
        user.setTemplateId(dto.getTemplateId());

        // 设置黑名单 (List -> String)
        if (dto.getBlacklistedProjects() != null && !dto.getBlacklistedProjects().isEmpty()) {
            user.setProjectBlacklist(String.join(",", dto.getBlacklistedProjects()));
        }

        if (!this.save(user)) {
            throw new RuntimeException("创建用户失败");
        }

        // 4. [核心改造] 处理价格配置
//        processAndSaveUserPrices(user, dto.getProjectPrices(), operator);
        // 【需求实现】将用户ID保存到模板数据库中的用户ID列表中
        priceTemplateService.addUserToTemplate(user.getTemplateId(), user.getId());

        BigDecimal initialBalance = dto.getInitialBalance();
        if (initialBalance != null && initialBalance.compareTo(BigDecimal.ZERO) > 0) {
            try {
                // 5.1 为新用户充值
                LedgerCreationDTO userRechargeDto = LedgerCreationDTO.builder()
                        .userId(user.getId())
                        .amount(initialBalance)
                        .ledgerType(1)
                        .fundType(FundType.AGENT_RECHARGE)
                        .remark("新用户初始充值")
                        .build();
                ledgerService.createLedgerAndUpdateBalance(userRechargeDto);

                // 5.2 如果操作员不是管理员，则从操作员账户扣款
                if (!isAdmin) {
                    LedgerCreationDTO operatorDeductDto = LedgerCreationDTO.builder()
                            .userId(operatorId)
                            .amount(initialBalance)
                            .ledgerType(0)
                            .fundType(FundType.AGENT_DEDUCTION)
                            .remark("为下级用户 " + user.getUserName() + " 充值")
                            .build();
                    ledgerService.createLedgerAndUpdateBalance(operatorDeductDto);
                }
            } catch (BusinessException e) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                log.error("为新用户 {} 分配初始资金失败: {}", user.getUserName(), e.getMessage());
                throw new BusinessException("为新用户分配初始资金失败: " + e.getMessage());
            } catch (Exception e) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                log.error("为新用户 {} 分配初始资金时发生系统错误", user.getUserName(), e);
                throw new RuntimeException("系统错误，创建用户资金记录失败", e);
            }
        }
        return true;
    }




    @Override
    public IPage<User> listSubUsers(String userName, Long operatorId, IPage<User> page) {
        // 1. 基础查询条件
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getParentId, operatorId); // 限制只能查自己的下级

        if (userName != null && !userName.trim().isEmpty()) {
            wrapper.like(User::getUserName, userName);
        }

        wrapper.orderByDesc(User::getCreateTime);

        // 2. 执行分页查询
        IPage<User> userPage = this.page(page, wrapper);
        List<User> records = userPage.getRecords();

        // 3. 填充模板名称 (批量查询优化)
        if (!CollectionUtils.isEmpty(records)) {
            // A. 提取所有非空的模板ID
            Set<Long> templateIds = records.stream()
                    .map(User::getTemplateId)
                    .filter(id -> id != null && id != 0L)
                    .collect(Collectors.toSet());

            // B. 批量查询模板表
            if (!CollectionUtils.isEmpty(templateIds)) {
                // 只查询 ID 和 Name 字段，减少数据传输
                List<PriceTemplate> templates = priceTemplateService.list(
                        new LambdaQueryWrapper<PriceTemplate>()
                                .in(PriceTemplate::getId, templateIds)
                                .select(PriceTemplate::getId, PriceTemplate::getName)
                );

                // C. 转为 Map<ID, Name>
                Map<Long, String> templateMap = templates.stream()
                        .collect(Collectors.toMap(PriceTemplate::getId, PriceTemplate::getName));

                // D. 回填数据
                for (User user : records) {
                    Long tid = user.getTemplateId();
                    if (tid != null && templateMap.containsKey(tid)) {
                        user.setTemplateName(templateMap.get(tid));
                    }
                }
            }
        }

        return userPage;
    }



    /**
     * [无需修改] 此方法已正确使用统一资金服务接口
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public CommonResultDTO<?> chargeUser(Long targetUserId, BigDecimal amount, Long operatorId, boolean isRecharge) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return CommonResultDTO.error(-5, "金额必须为正数");
        }

        User targetUser = this.getById(targetUserId);
        if (targetUser == null) return CommonResultDTO.error(-5, "目标用户不存在");

        boolean isAdmin = (operatorId == 0L);
        User operator;
        if (!isAdmin) {
            operator = this.getById(operatorId);
            if (operator == null) return CommonResultDTO.error(-5, "操作员不存在");
            if (!Objects.equals(targetUser.getParentId(), operatorId)) {
                return CommonResultDTO.error(-5, "无权操作该用户");
            }
        }

        try {

            // 1. 操作目标用户
            LedgerCreationDTO targetLedger = LedgerCreationDTO.builder()
                    .userId(targetUserId)
                    .amount(amount) // 金额始终为正
                    .ledgerType(isRecharge ? 1 : 0) // 1-入账, 0-出账
                    .fundType(isRecharge ? FundType.AGENT_RECHARGE : FundType.AGENT_DEDUCTION)
                    .remark((isRecharge ? "上级充值: " : "上级扣款: ") + operatorId)
                    .build();
            ledgerService.createLedgerAndUpdateBalance(targetLedger);

            // 2. 如果操作员不是管理员，则反向操作操作员自己的账户
            if (!isAdmin) {
                LedgerCreationDTO operatorLedger = LedgerCreationDTO.builder()
                        .userId(operatorId)
                        .amount(amount) // 金额始终为正
                        .ledgerType(isRecharge ? 0 : 1) // 代理是反向操作：给别人充值=自己扣款
                        .fundType(isRecharge ? FundType.AGENT_DEDUCTION : FundType.AGENT_RECHARGE)
                        .remark((isRecharge ? "为下级充值: " : "从下级扣款: ") + targetUserId)
                        .build();
                ledgerService.createLedgerAndUpdateBalance(operatorLedger);
            }
        } catch (BusinessException e) {
            log.info("出现错误：{}", e.getMessage());
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            throw new BusinessException(0,e.getMessage());
        } catch (Exception e) {
            log.error("资金操作失败，未知异常", e);
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "系统内部错误，操作失败");
        }

        return CommonResultDTO.success("操作成功");
    }

    @Override
    public CommonResultDTO<?> rechargeUser(Long targetUserId, BigDecimal amount, Long operatorId) {
        return chargeUser(targetUserId, amount, operatorId, true);
    }

    @Override
    public CommonResultDTO<?> deductUser(Long targetUserId, BigDecimal amount, Long operatorId) {
        return chargeUser(targetUserId, amount, operatorId, false);
    }






    @Scheduled(cron = "0 0 0 * * ?")
    @Override
    public void resetDailyStatsAllUsers() {
        log.info("开始执行每日统计数据重置任务...");
        boolean success = this.update(new LambdaUpdateWrapper<User>()
                .set(User::getDailyGetCount, 0)
                .set(User::getDailyCodeCount, 0)
                .set(User::getDailyCodeRate, 0.0)
        );
        log.info("每日统计数据重置任务完成，结果: {}", success);
    }



    /**
     * [已废弃 -> 移除]
     * 安全地更新用户余额（带悲观锁）的方法已被移除。
     * 所有资金变动均通过 UserLedgerService.createLedgerAndUpdateBalance() 实现，
     * 该方法内部已包含锁和事务管理，确保了数据一致性。
     */
    // private void updateBalanceWithLock(Long userId, BigDecimal amount) { ... }
    /**
     * [重构] 将 User 实体转换为 UserDTO，价格从新表获取
     */
    public UserDTO convertToDTO(User user) {
        if (user == null) return null;
        UserDTO dto = new UserDTO();
        BeanUtils.copyProperties(user, dto);
        dto.setUserId(user.getId());
        dto.setIsAgent(user.getIsAgent() != null && user.getIsAgent() == 1);
        return dto;
    }

    /**
     * [重构] 校验为下级设置的价格是否合规
     */
    private void validateProjectPrices(List<ProjectPriceDTO> pricesToSet, User operator) {
        if (CollectionUtils.isEmpty(pricesToSet)) {
            return;
        }
        // 假设 operator.getId() 为 0L 是管理员的约定
        boolean isAdmin = (operator.getId() != null && operator.getId() == 0L);
        Map<String, ProjectPriceSummaryDTO> priceSummaries = projectService.getAllProjectPriceSummaries();

        Map<String, BigDecimal> operatorPrices = Collections.emptyMap();
        if (!isAdmin) {
            List<UserProjectLine> operatorLines = userProjectLineService.getLinesByUserId(operator.getId());
            if (!CollectionUtils.isEmpty(operatorLines)) {
                operatorPrices = operatorLines.stream().collect(Collectors.toMap(
                        line -> line.getProjectId() + "-" + line.getLineId(),
                        UserProjectLine::getAgentPrice
                ));
            }
        }

        for (ProjectPriceDTO priceDto : pricesToSet) {
            String priceKey = priceDto.getProjectId() + "-" + priceDto.getLineId();
            if(priceDto.getPrice()==null){
                throw new BusinessException(0,"用户项目价格配置不能为空");
            }
            BigDecimal priceToSet = priceDto.getPrice();
            String projectId = String.valueOf(priceDto.getProjectId());

            ProjectPriceSummaryDTO summary = priceSummaries.get(projectId);
            if (summary == null) {
                log.warn("价格校验警告: 项目 '{}' 没有找到价格摘要信息，跳过此项校验。", projectId);
                continue;
            }

            // 根据操作员角色进行价格校验
            if (isAdmin) {
                // 管理员的价格不得低于项目最低价
                if (priceToSet.compareTo(summary.getMinPrice()) < 0) {
                    throw new BusinessException(String.format("价格设置错误：线路 %s 的价格 %s 不能低于项目最低价 %s", priceKey, priceToSet, summary.getMinPrice()));
                }
            } else { // 代理商
                BigDecimal operatorCost = operatorPrices.get(priceKey);
                if (operatorCost == null) {
                    throw new BusinessException(String.format("价格设置错误：您没有线路 %s 的价格配置，无法为下级设置", priceKey));
                }
                if (priceToSet.compareTo(operatorCost) < 0) {
                    throw new BusinessException(String.format("价格设置错误：线路 %s 的价格 %s 不能低于您的成本价 %s", priceKey, priceToSet, operatorCost));
                }
            }
            // 统一校验：所有角色的设置价格都不能高于项目最高价
            if (priceToSet.compareTo(summary.getMaxPrice()) > 0) {
                throw new BusinessException(String.format("价格设置错误：线路 %s 的价格 %s 不能高于项目最高价 %s", priceKey, priceToSet, summary.getMaxPrice()));
            }
        }
        log.info("操作员 {} 为下级设置的价格已通过校验。", operator.getId());
    }

    /**
     * [重构] 为新用户生成初始价格配置
     */
    private List<ProjectPriceDTO> generateInitialPricesFor(User operator) {
        boolean isAdmin = (operator.getId() != null && operator.getId() == 0L);

        if (!isAdmin) {
            List<UserProjectLine> operatorLines = userProjectLineService.getLinesByUserId(operator.getId());
            if (!CollectionUtils.isEmpty(operatorLines)) {
                log.info("为新用户生成初始价格：成功设置为项目最高价");
                return operatorLines.stream().map(line -> {
                    ProjectPriceDTO dto = new ProjectPriceDTO();
                    Project project = projectService.getProject(line.getProjectId(), Integer.valueOf(line.getLineId()));
                    dto.setProjectId(Long.parseLong(line.getProjectId()));
                    dto.setLineId(Long.parseLong(line.getLineId()));
                    dto.setPrice(project.getPriceMax());
                    return dto;
                }).collect(Collectors.toList());
            }
        }

        log.info("操作员 {} 无价格配置或为管理员，为新用户生成初始价格：采用系统默认最高价策略。", operator.getId());
        Map<String, ProjectPriceSummaryDTO> priceSummaries = projectService.getAllProjectPriceSummaries();
        return projectService.list().stream()
                .map(line -> {
                    ProjectPriceSummaryDTO summary = priceSummaries.get(line.getProjectId());
                    BigDecimal price = (summary != null && summary.getMaxPrice() != null) ? summary.getMaxPrice() : line.getCostPrice();
                    ProjectPriceDTO dto = new ProjectPriceDTO();
                    dto.setProjectId(Long.parseLong(line.getProjectId()));
                    dto.setLineId(Long.parseLong(line.getLineId()));
                    dto.setPrice(price);
                    return dto;
                }).collect(Collectors.toList());
    }

    /**
     * 通过用户名查询用户
     */
    @Override
    public User getByUserName(String userName){
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUserName,userName);

        return this.getOne(queryWrapper);
    }

    @Override
    public Boolean login(UserLoginDto userLoginDto) {
        User user = getByUserName(userLoginDto.getUsername());
        if (user == null) return false;
        if (user.getPassword().equals(userLoginDto.getPassword())) {
            return true;
        }
        return false;
    }

    @Override
    public Boolean regist(UserResultDTO userDTO) {
        User user = getByUserName(userDTO.getUserName());
        if (user != null) {
            throw new BusinessException(0,"用户已经注册过了");
        }
        User uu = new User();
        //todo
        BeanUtil.copyProperties(userDTO, uu);
        return userMapper.insert(uu)>0;
    }

    /**
     * 【新-重构】处理代理为用户充值的业务逻辑
     * 通过调用统一的账本服务来确保事务和数据一致性。
     * 1. 扣除代理余额
     * 2. 增加用户余额
     * 3. 为双方创建账本记录
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void rechargeUserFromAgentBalance(Long targetUserId, BigDecimal amount, Long agentId) {
        // 步骤 0: 校验金额必须为正数
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("操作金额必须为正数");
        }

        // 步骤 1: 获取代理和目标用户信息，用于权限校验和记录备注
        // 注意: 此处不需要加锁(FOR UPDATE)，因为加锁操作由下游的 createLedgerAndUpdateBalance 方法完成
        User agent = this.getById(agentId);
        User targetUser = this.getById(targetUserId);

        // 步骤 2: 进行严格的权限和状态校验
        if (agent == null || agent.getIsAgent() != 1) {
            throw new SecurityException("操作员权限不足或非代理用户");
        }
        if (targetUser == null) {
            throw new BusinessException("目标用户不存在");
        }
        // 确保目标用户是该代理的直接下级
        if (!agentId.equals(targetUser.getParentId())) {
            throw new SecurityException("无权操作非下级用户");
        }

        // 步骤 3: 代理账户出账
        // 构建代理扣款的请求DTO
        LedgerCreationDTO agentDeductRequest = LedgerCreationDTO.builder()
                .userId(agentId)
                .amount(amount)
                .ledgerType(0) // 0-出账
                .fundType(FundType.AGENT_DEDUCTION) // 假设有此类型：代理为下级充值扣款
                .remark(String.format("为用户 %s 充值", targetUser.getUserName()))
                .build();

        // 调用核心服务，该方法会检查代理余额是否充足，如果不足会抛出异常，整个事务回滚
        userLedgerService.createLedgerAndUpdateBalance(agentDeductRequest);

        // 步骤 4: 用户账户入账
        // 构建用户充值的请求DTO
        LedgerCreationDTO userRechargeRequest = LedgerCreationDTO.builder()
                .userId(targetUserId)
                .amount(amount)
                .ledgerType(1) // 1-入账
                .fundType(FundType.AGENT_RECHARGE) // 假设有此类型：代理充值
                .remark(String.format("收到代理 %s 的充值", agent.getUserName()))
                .build();

        // 调用核心服务
        userLedgerService.createLedgerAndUpdateBalance(userRechargeRequest);
    }

    /**
     * 【新-重构】处理代理从用户扣款的业务逻辑
     * 通过调用统一的账本服务来确保事务和数据一致性。
     * 1. 扣除用户余额
     * 2. 增加代理余额
     * 3. 为双方创建账本记录
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deductUserToAgentBalance(Long targetUserId, BigDecimal amount, Long agentId) {
        // 步骤 0: 校验金额
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("操作金额必须为正数");
        }

        // 步骤 1: 获取用户信息用于校验和备注
        User agent = this.getById(agentId);
        User targetUser = this.getById(targetUserId);

        // 步骤 2: 权限和状态校验
        if (agent == null || agent.getIsAgent() != 1) {
            throw new SecurityException("操作员权限不足或非代理用户");
        }
        if (targetUser == null) {
            throw new BusinessException("目标用户不存在");
        }
        if (!agentId.equals(targetUser.getParentId())) {
            throw new SecurityException("无权操作非下级用户");
        }

        // 步骤 3: 用户账户出账
        // 构建用户被扣款的请求DTO
        LedgerCreationDTO userDeductRequest = LedgerCreationDTO.builder()
                .userId(targetUserId)
                .amount(amount)
                .ledgerType(0) // 0-出账
                .fundType(FundType.AGENT_DEDUCTION) // 假设有此类型：代理扣款
                .remark(String.format("被代理 %s 扣款", agent.getUserName()))
                .build();

        // 调用核心服务，该方法会检查用户余额是否充足
        userLedgerService.createLedgerAndUpdateBalance(userDeductRequest);

        // 步骤 4: 代理账户入账
        // 构建代理收款的请求DTO
        LedgerCreationDTO agentAddRequest = LedgerCreationDTO.builder()
                .userId(agentId)
                .amount(amount)
                .ledgerType(1) // 1-入账
                .fundType(FundType.AGENT_RECHARGE) // 假设有此类型：从下级扣款入账
                .remark(String.format("从用户 %s 扣款", targetUser.getUserName()))
                .build();

        // 调用核心服务
        userLedgerService.createLedgerAndUpdateBalance(agentAddRequest);
    }

    @Transactional
    @Override
    public void updateAgentProjectConfig(Long agentId, AgentProjectLineUpdateDTO updateDTO) {
        log.info("更新项目配置接口：{}",updateDTO);
        // 1. 查找对应的用户项目线路记录
        UserProjectLine userProjectLine = userProjectLineService.getById(updateDTO.getUserProjectLineId());

        // 2. 安全校验：确保记录存在，并且属于当前操作的代理用户
        if (userProjectLine == null) {
            throw new BusinessException("操作失败：该项目不存在。");
        }

        boolean needsUpdate = false; // 标记是否需要执行数据库更新操作

        // 3. 按需更新字段
        // 3.1 检查是否需要更新价格
        if (updateDTO.getAgentPrice() != null) {
            // 如果价格字段被传递了，执行价格校验逻辑
//            Project project = projectService.getById(userProjectLine.getProjectTableId());
            Project project = projectService.getProject(updateDTO.getProjectId(), Integer.valueOf(updateDTO.getLineId()));
            if (project == null) {
                log.error("数据不一致：UserProjectLine ID {} 对应的项目在线路表(project)中未找到", userProjectLine.getProjectTableId());
                throw new BusinessException("更新失败：项目基础信息不存在。");
            }

            BigDecimal newPrice = updateDTO.getAgentPrice();
            BigDecimal costPrice = userProjectLine.getCostPrice();
            BigDecimal maxPrice = project.getPriceMax();

            // 价格逻辑校验
            if (newPrice.compareTo(costPrice) < 0) {
                throw new BusinessException("更新失败：售价不能低于成本价 " + costPrice);
            }
            if (maxPrice != null && maxPrice.compareTo(BigDecimal.ZERO) > 0 && newPrice.compareTo(maxPrice) > 0) {
                throw new BusinessException("更新失败：售价不能高于系统最高限价 " + maxPrice);
            }

            // 设置新价格并标记需要更新
            userProjectLine.setAgentPrice(newPrice);
            needsUpdate = true;
        }

        // 3.2 检查是否需要更新备注
        if (updateDTO.getRemark() != null) {
            // 如果备注字段被传递了，直接更新
            userProjectLine.setRemark(updateDTO.getRemark());
            needsUpdate = true;
        }
        if(updateDTO.getProjectId() != null) {
            userProjectLine.setProjectId(updateDTO.getProjectId());
            needsUpdate = true;
        }

        if (updateDTO.getLineId() != null) {
            userProjectLine.setLineId(updateDTO.getLineId());
            needsUpdate = true;
        }

        // 3.3 如果未来有其他字段，在这里添加类似的 if (updateDTO.getOtherField() != null) { ... } 逻辑块

        // 4. 如果有任何字段被修改，则执行数据库更新
        if (needsUpdate) {
            boolean updated = userProjectLineService.updateById(userProjectLine);
            if (!updated) {
                throw new BusinessException("更新失败，请稍后重试。");
            }
        }
        // 如果没有任何字段需要更新，则方法静默成功，不执行任何数据库操作
    }

    @Override
    public IPage<SubUserProjectPriceDTO> getSubUsersProjectPrices(String userName,Long agentId, Page<User> page) {
        // 1. 校验操作者身份 (逻辑不变)
        User agent = this.getById(agentId);
        if (agent == null || agent.getIsAgent() != 1) {
            throw new BusinessException("当前用户不是代理或用户不存在");
        }

        // 2.【核心改造】分页查询该代理的下级用户
        LambdaQueryWrapper<User> subUsersQuery = new LambdaQueryWrapper<User>()
                .eq(User::getParentId, agentId);
        if (userName != null) {
            subUsersQuery.like(User::getUserName, userName);
        }
        // IPage<User> subUserPage = this.page(new Page<>(page.getCurrent(), page.getSize()), subUsersQuery);
        IPage<User> subUserPage = this.page(page, subUsersQuery);


        List<User> subUsersOnCurrentPage = subUserPage.getRecords();

        if (CollectionUtils.isEmpty(subUsersOnCurrentPage)) {
            // 如果当前页没有数据，直接返回一个空的Page对象，但保留总数等信息
            return new Page<SubUserProjectPriceDTO>().setTotal(subUserPage.getTotal());
        }

        // 3. 提取【当前页】所有下级的用户ID
        List<Long> subUserIds = subUsersOnCurrentPage.stream().map(User::getId).collect(Collectors.toList());

        // 4. 一次性查询【当前页】用户的所有项目线路配置 (逻辑不变)
        List<UserProjectLine> allLines = userProjectLineService.getLinesByUserIds(subUserIds,null);

        // 5. 将线路配置按用户ID分组 (逻辑不变)
        Map<Long, List<UserProjectLine>> linesByUserId = allLines.stream()
                .collect(Collectors.groupingBy(UserProjectLine::getUserId));

        // 6. 构建【当前页】的DTO结果列表
        List<SubUserProjectPriceDTO> dtoList = subUsersOnCurrentPage.stream().map(subUser -> {
            SubUserProjectPriceDTO dto = new SubUserProjectPriceDTO();
            dto.setUserId(subUser.getId());
            dto.setUserName(subUser.getUserName());
            List<UserProjectLine> userLines = linesByUserId.getOrDefault(subUser.getId(), Collections.emptyList());

            List<ProjectPriceInfoDTO> prices = userLines.stream().map(line -> {
                Project project = projectService.getProject(line.getProjectId(), Integer.valueOf(line.getLineId()));
                ProjectPriceInfoDTO priceInfo = new ProjectPriceInfoDTO();
                priceInfo.setId(line.getId());
                priceInfo.setProjectName(line.getProjectName());
                priceInfo.setProjectId(line.getProjectId());
                priceInfo.setLineId(line.getLineId());
                priceInfo.setPrice(line.getAgentPrice());
                priceInfo.setCostPrice(line.getCostPrice());
                // 建议增加 project 的非空判断
                priceInfo.setMaxPrice(project != null && project.getPriceMax() != null ? project.getPriceMax() : BigDecimal.ZERO);
                priceInfo.setMinPrice(project != null && project.getPriceMin() != null ? project.getPriceMin() : BigDecimal.ZERO);
                return priceInfo;
            }).collect(Collectors.toList());

            dto.setProjectPrices(prices);
            return dto;
        }).collect(Collectors.toList());

        // 7.【核心改造】创建并返回 IPage<SubUserProjectPriceDTO>
        // MyBatis-Plus分页查询返回的是 IPage<Entity>，我们需要转换为 IPage<DTO>
        // 创建一个新的Page对象，将分页信息(total, size, current)拷贝过去，并设置records为我们处理好的DTO列表
        IPage<SubUserProjectPriceDTO> resultPage = new Page<>(subUserPage.getCurrent(), subUserPage.getSize(), subUserPage.getTotal());
        resultPage.setRecords(dtoList);

        return resultPage;
    }

    /**
     * 【新增】根据用户名模糊查询用户ID列表
     * @param username 用户名
     * @return 匹配的用户ID列表
     */
    @Override
    public List<Long> findUserIdsByUsernameLike(String username) {
        if (username == null || username.trim().isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        // 使用 like 实现模糊查询，并只 select id 字段以提高效率

        queryWrapper.like(User::getUserName, username).select(User::getId);
        List<User> users = this.list(queryWrapper);
        if (CollectionUtils.isEmpty(users)) {
            return Collections.emptyList();
        }
        // 从用户列表中提取ID
        return users.stream().map(User::getId).collect(Collectors.toList());
    }

    @Override
    public boolean delectByuserId(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BusinessException(0,"查询不到要删除的用户");
        }

        return userMapper.deleteById(user) >0;
    }


    /**
     * 根据号码记录表重新计算并更新用户的统计数据。
     * 这个方法是幂等的，可以安全地重复调用。
     *
     * @param userId 需要更新统计数据的用户ID
     */
    @Override
    public void updateUserStats(Long userId) {

        User user = findAndLockById(userId);
        if (user == null) return;
        // 1. 定义时间范围：今天零点
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        // 2. 查询总的取号次数
        long totalGetCount = numberRecordMapper.selectCount(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getUserId, userId));

        // 查询总的成功次数 (状态为2且验证码不为空)
        long totalCodeCount = numberRecordMapper.selectCount(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getUserId, userId)
                .eq(NumberRecord::getStatus, 2)       // 状态2表示成功
                .isNotNull(NumberRecord::getCode)    // 并且 code 字段不为 NULL
                .ne(NumberRecord::getCode, ""));      // 并且 code 字段不为空字符串

        // 3. 查询今天的取号次数
        long dailyGetCount = numberRecordMapper.selectCount(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getUserId, userId)
                .ge(NumberRecord::getGetNumberTime, startOfToday));

        // 查询今天的成功次数 (状态为2且验证码不为空)
        long dailyCodeCount = numberRecordMapper.selectCount(new LambdaQueryWrapper<NumberRecord>()
                .eq(NumberRecord::getUserId, userId)
                .eq(NumberRecord::getStatus, 2)       // 状态2表示成功
                .isNotNull(NumberRecord::getCode)    // 并且 code 字段不为 NULL
                .ne(NumberRecord::getCode, "")      // 并且 code 字段不为空字符串
                .ge(NumberRecord::getGetNumberTime, startOfToday));

        // 4. 计算回码率 (注意处理除以零的情况)
        BigDecimal totalCodeRate = (totalGetCount > 0)
                ? new BigDecimal(totalCodeCount).divide(new BigDecimal(totalGetCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal dailyCodeRate = (dailyGetCount > 0)
                ? new BigDecimal(dailyCodeCount).divide(new BigDecimal(dailyGetCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 5. 使用 LambdaUpdateWrapper 一次性原子更新 User 表
        user.setTotalGetCount((int) totalGetCount);
        user.setTotalCodeCount((int) totalCodeCount);
        user.setDailyGetCount((int) dailyGetCount);
        user.setDailyCodeCount((int) dailyCodeCount);
        user.setTotalCodeRate(totalCodeRate.doubleValue());
        user.setDailyCodeRate(dailyCodeRate.doubleValue());

        this.updateById(user);
        log.info("用户 {} 的统计数据已更新。今日取码/成功: {}/{}, 总计取码/成功: {}/{}",
                userId, dailyGetCount, dailyCodeCount, totalGetCount, totalCodeCount);
    }

    /**
     * [新增] 处理并执行多级代理返款的核心方法
     * <p>
     * 此方法会从触发业务的用户开始，沿着 parentId 链向上遍历。
     * 在每一层，它会计算上级代理的利润（下级售价 - 上级售价），
     * 并将这部分利润作为返款，通过创建入账流水的方式记入上级代理的余额。
     * 整个过程由 Spring 事务管理，保证数据一致性。
     *
     * @param successfulRecord 成功完成并已扣费的号码记录
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void processRebates(NumberRecord successfulRecord) {
        log.info("开始为记录ID {} 处理上级代理返款...", successfulRecord.getId());

        // 1. 获取初始信息
        BigDecimal lastLevelPrice = successfulRecord.getPrice(); // 这是最终用户支付的价格
        String projectId = successfulRecord.getProjectId();
        Integer lineId = successfulRecord.getLineId();
        User currentUser = this.getById(successfulRecord.getUserId());

        if (currentUser == null) {
            log.error("返款流程失败：找不到ID为 {} 的初始用户。", successfulRecord.getUserId());
            return;
        }

        // 2. 循环向上追溯代理链，直到顶层代理（parentId 为 0 或 null）
        while (currentUser.getParentId() != null && currentUser.getParentId() != 0L) {
            Long parentId = currentUser.getParentId();
            User parentUser = this.getById(parentId);

            if (parentUser.getTemplateId() == null) {
                log.warn("代理 {} 无模板配置，跳过返点", parentId);
                break;
            }

            PriceTemplateItem parentItem = priceTemplateService.getPriceConfig(
                    parentUser.getTemplateId(),
                    successfulRecord.getProjectId(),
                    successfulRecord.getLineId()
            );

            if (parentItem == null) {
                // 上级没有配置该项目，可能意味着无法获利或数据异常
                break;
            }

            BigDecimal parentPrice = parentItem.getPrice(); // 上级的拿货价

            // 4. 计算返款金额（即利润）
            BigDecimal rebateAmount = lastLevelPrice.subtract(parentPrice);

            // 5. 如果利润为正数，则为上级代理执行返款
            if (rebateAmount.compareTo(BigDecimal.ZERO) > 0) {
                log.info("为代理 {} (ID: {}) 返款: {}。计算方式: (下级价格){} - (本级价格){}",
                        parentUser.getUserName(), parentId, rebateAmount, lastLevelPrice, parentPrice);

                // 6. 创建返款账本记录，并更新代理余额（由 createLedgerAndUpdateBalance 方法原子化完成）
                LedgerCreationDTO rebateLedger = LedgerCreationDTO.builder()
                        .userId(parentId)
                        .amount(rebateAmount)
                        .ledgerType(1) // 1-入账
                        .fundType(FundType.ADMIN_REBATE) // 使用我们新定义的返款类型
                        .remark(String.format("下级用户 %s 业务成功，返款", currentUser.getUserName()))
                        .phoneNumber(successfulRecord.getPhoneNumber())
                        .code(successfulRecord.getCode())
                        .projectId(projectId)
                        .lineId(lineId)
                        .build();
                try {
                    // 调用统一的账本服务，它会处理余额更新和流水记录
                    ledgerService.createLedgerAndUpdateBalance(rebateLedger);
                } catch (Exception e) {
                    log.error("为代理 {} (ID: {}) 返款时失败，事务将回滚。错误: {}", parentUser.getUserName(), parentId, e.getMessage());
                    // 向上抛出异常，以确保整个事务（包括初始用户的扣费）都能回滚
                    throw new BusinessException("为代理 " + parentUser.getUserName() + " 返款失败：" + e.getMessage());
                }
            } else {
                log.warn("代理 {} (ID: {}) 的返款金额为零或负数 ({})，不执行返款。下级价格: {}, 本级价格: {}",
                        parentUser.getUserName(), parentId, rebateAmount, lastLevelPrice, parentPrice);
            }

            // 7. 更新变量，为下一轮循环做准备
            currentUser = parentUser;
            lastLevelPrice = parentPrice;
        }

        log.info("记录ID {} 的返款流程处理完毕。", successfulRecord.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public int batchFundOperation(BatchChargeRequestDTO dto, Long operatorId) {
        // 1. 基础校验
        if (CollectionUtils.isEmpty(dto.getUserIds())) {
            throw new BusinessException("请至少选择一个用户");
        }
        if (dto.getAmount() == null || dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("金额必须为正数");
        }

        boolean isRecharge = Boolean.TRUE.equals(dto.getIsRecharge());
        boolean isAdmin = (operatorId == 0L);

        // 如果是代理操作，先获取代理信息
        User operator = null;
        if (!isAdmin) {
            operator = this.findAndLockById(operatorId);
            if (operator == null) throw new BusinessException("操作员不存在");
            if (operator.getIsAgent() != 1) throw new BusinessException("无权操作");

            // 如果是充值，先检查代理余额是否足够支付所有人的总和
            if (isRecharge) {
                BigDecimal totalNeed = dto.getAmount().multiply(new BigDecimal(dto.getUserIds().size()));
                if (operator.getBalance().compareTo(totalNeed) < 0) {
                    throw new BusinessException("您的余额不足以支付本次批量充值，共需: " + totalNeed);
                }
            }
        }

        int successCount = 0;

        // 2. 循环处理每一个用户
        for (Long targetUserId : dto.getUserIds()) {
            // 防止操作自己
            if (targetUserId.equals(operatorId)) {
                continue;
            }

            // --- A. 操作目标用户 ---
            LedgerCreationDTO targetLedger = LedgerCreationDTO.builder()
                    .userId(targetUserId)
                    .amount(dto.getAmount())
                    .ledgerType(isRecharge ? 1 : 0) // 1入账，0出账
                    // 根据操作类型选择资金类型
                    .fundType(isRecharge ? FundType.AGENT_RECHARGE : FundType.AGENT_DEDUCTION)
                    .remark((StringUtils.hasText(dto.getRemark()) ? dto.getRemark() : "") +
                            (isRecharge ? " [批量充值]" : " [批量扣款]"))
                    .build();

            // 调用核心账本服务 (会自动处理加锁、余额计算、流水写入)
            userLedgerService.createLedgerAndUpdateBalance(targetLedger);

            // --- B. 如果是代理操作，需反向操作代理账户 ---
            if (!isAdmin) {
                // 如果是给下级充值(isRecharge=true)，代理自己要扣钱(ledgerType=0)
                // 如果是扣下级钱(isRecharge=false)，代理自己要加钱(ledgerType=1)
                LedgerCreationDTO operatorLedger = LedgerCreationDTO.builder()
                        .userId(operatorId)
                        .amount(dto.getAmount())
                        .ledgerType(isRecharge ? 0 : 1)
                        .fundType(isRecharge ? FundType.AGENT_DEDUCTION : FundType.AGENT_RECHARGE)
                        .remark("对用户 " + targetUserId + (isRecharge ? " 批量充值扣除" : " 批量扣款回收"))
                        .build();

                userLedgerService.createLedgerAndUpdateBalance(operatorLedger);
            }

            successCount++;
        }

        return successCount;
    }

}