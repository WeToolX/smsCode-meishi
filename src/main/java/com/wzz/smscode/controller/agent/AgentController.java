// src/main/java/com/wzz/smscode/controller/agent/AgentController.java
package com.wzz.smscode.controller.agent;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzz.smscode.common.Constants;
import com.wzz.smscode.common.Result;
import com.wzz.smscode.dto.*;
import com.wzz.smscode.dto.CreatDTO.UserCreateDTO;
import com.wzz.smscode.dto.LoginDTO.AgentLoginDTO;
import com.wzz.smscode.dto.agent.AgentDashboardStatsDTO;
import com.wzz.smscode.dto.agent.AgentMyQuotaDTO;
import com.wzz.smscode.dto.agent.AgentProjectLineUpdateDTO;
import com.wzz.smscode.dto.number.NumberDTO;
import com.wzz.smscode.dto.project.SubUserProjectPriceDTO;
import com.wzz.smscode.dto.quota.UserQuotaItemDTO;
import com.wzz.smscode.dto.quota.UserQuotaLedgerItemDTO;
import com.wzz.smscode.dto.update.UserUpdateDtoByUser;
import com.wzz.smscode.entity.*;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.mapper.UserProjectQuotaLedgerMapper;
import com.wzz.smscode.service.*;
import jakarta.validation.constraints.NotNull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 代理后台接口控制器
 * <p>
 * 提供下级用户管理、资金操作等功能。
 * 所有接口（除登录外）都需要通过 Sa-Token 进行登录认证。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final Logger log = LogManager.getLogger(AgentController.class);
    @Autowired
    private UserService userService;
    @Autowired
    private UserProjectQuotaService userProjectQuotaService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private UserProjectQuotaLedgerMapper userProjectQuotaLedgerMapper;



    /**
     * 代理登录接口
     *
     * @param loginDTO 登录信息 (username, password)
     * @return 包含 Token 的 Result 对象
     */
    @PostMapping("/login")
    public Result<?> login(@RequestBody AgentLoginDTO loginDTO) {
        // 调用业务层进行登录验证
        User agent = userService.AgentLogin(loginDTO.getUsername(), loginDTO.getPassword());

        // 验证失败
        if (agent == null) {
            return Result.error("用户名或密码错误");
        }
        // 验证是否为代理
        if (agent.getIsAgent() != 1) {
            return Result.error(403, "权限不足，非代理用户");
        }
        // 登录成功，使用 Sa-Token 创建会话
        StpUtil.login(agent.getId());
        // 返回 Token 信息
        return Result.success("登录成功", StpUtil.getTokenValue());
    }

    /**
     * 查询所有下级用户列表（分页）
     */
    @SaCheckLogin // 使用注解，如果未登录，直接抛出异常，由全局异常处理器返回JSON
    @GetMapping("/listUsers") // 推荐使用更具体的 GetMapping
    public Result<?> listUsers(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(required = false) String userName,
            @RequestParam(defaultValue = "10") long size) {

        // 通过 Sa-Token 获取当前登录的代理ID
        long agentId = StpUtil.getLoginIdAsLong();

        // 可以在这里再次校验代理身份，但更推荐在 Sa-Token 的拦截器或全局逻辑中统一处理
        checkAgentPermission(agentId);

        IPage<User> pageRequest = new Page<>(page, size);
        IPage<User> subUsersPage = userService.listSubUsers(userName,agentId, pageRequest);

        return Result.success(subUsersPage);
    }
    @Autowired
    private SystemConfigService systemConfigService;
    /**
     * 获取公告接口
     */
    @GetMapping("/notice")
    public Result<?> getUserNotice(){
        SystemConfig config = systemConfigService.getConfig();
        return Result.success(config.getSystemNotice());
    }

    /**
     * 创建一个下级用户账号
     */
    @SaCheckLogin
    @PostMapping("/createUser")
    public Result<?> createUserbyAgent( @RequestBody UserCreateDTO userCreateDTO) {
//        log.info("createUserbyAgent：：{}",userCreateDTO);
        long agentId = StpUtil.getLoginIdAsLong();
        if (userCreateDTO.getUsername() == null || userCreateDTO.getPassword() == null) {
            return Result.error("用户名或者密码参数为空");
        }
        try {
            boolean success = userService.createUser(userCreateDTO, agentId);
            return success ? Result.success("创建成功") : Result.error("创建失败，请稍后重试");
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            // 记录业务异常信息，但返回通用错误提示
            log.warn("创建用户业务校验失败: {}", e.getMessage());
            return Result.error("创建失败，输入信息有误或权限不足");
        }catch (BusinessException e){
            return Result.error(e.getMessage());
        }
        catch (Exception e) {
            // 记录未预料到的系统异常
            log.error("创建用户时发生系统内部错误", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "创建用户时发生系统内部错误，请联系管理员");
        }
    }

    /**
     * 修改下级用户的信息
     */
    @SaCheckLogin
    @PostMapping("/updateUser")
    public Result<?> updateUser(@RequestBody UserUpdateDtoByUser userDTO) {
        long agentId = StpUtil.getLoginIdAsLong();
        try {
            // 权限校验已转移到 Service 层，Service 层需要使用 agentId 来判断权限
            boolean success = userService.updateUserByAgent(userDTO, agentId);
            return success ? Result.success("修改成功") : Result.error("信息无变化或修改失败");
        } catch (IllegalArgumentException | SecurityException e) {
            log.warn("修改用户信息业务校验失败: {}", e.getMessage());
            return Result.error("修改失败，提交的数据不合法或无权操作");
        }
    }

//    /**
//     * 修改下级用户的信息并且更新用户项目价格配置
//     */
//    @SaCheckLogin
//    @PostMapping("/updateUser")
//    public Result<?> updateUserByUserProjectLineConfug(@RequestBody UserUpdateDtoByUser userDTO) {
//        long agentId = StpUtil.getLoginIdAsLong();
//        try {
////            boolean success = userService.updateUserByAgent(userDTO, agentId);
//
////            return success ? Result.success("修改成功") : Result.error("信息无变化或修改失败");
//        } catch (IllegalArgumentException | SecurityException e) {
//            log.warn("修改用户信息业务校验失败: {}", e.getMessage());
//            return Result.error("修改失败，提交的数据不合法或无权操作");
//        }
//    }

    /**
     * 为下级用户充值
     */
    @SaCheckLogin
    @PostMapping("/rechargeUser")
    public Result<?> rechargeUser(
            @RequestParam Long targetUserId,
            @RequestParam String projectId,
            @RequestParam String lineId,
            @RequestParam Long count) {

        long agentId = StpUtil.getLoginIdAsLong();
        checkAgentPermission(agentId);
        log.info("为下级用户充值{}::{}", agentId, targetUserId);
        try {
            // 业务逻辑委托给 Service 层，Service 层会进行权限和余额等校验
            userService.rechargeUserFromAgentBalance(targetUserId, projectId, lineId, count, agentId);
            return Result.success("充值成功");
        }catch (BusinessException e){
            return Result.success("充值失败",e.getMessage());
        }
    }

    /**
     * 扣减下级用户余额
     */
    @SaCheckLogin
    @PostMapping("/deductUser")
    public Result<?> deductUser(
            @RequestParam Long targetUserId,
            @RequestParam String projectId,
            @RequestParam String lineId,
            @RequestParam Long count) {
        long agentId = StpUtil.getLoginIdAsLong();
        checkAgentPermission(agentId);
        try {
            userService.deductUserToAgentBalance(targetUserId, projectId, lineId, count, agentId);
            return Result.success("扣款成功");
        }
        catch (BusinessException e){
            return Result.success("扣款失败",e.getMessage());
        }
    }

    /**
     * 查看某个下级用户的资金账本
     */
    @SaCheckLogin
    @GetMapping("/viewUserLedger") // 推荐使用更具体的 GetMapping
    public Result<?> viewUserLedger(
            @RequestParam Long targetUserId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.error("旧金额账本接口已下线，请使用项目线路配额账本接口");
    }

    /**
     * 检查当前登录用户是否为有效代理 (内部使用)
     * 如果不是，则直接抛出异常
     * @param agentId 代理ID
     */
    private void checkAgentPermission(Long agentId) {
        User agent = userService.getById(agentId);
        // 在 SaCheckLogin 后，agent 一般不会为 null，但做个健壮性检查
        if (agent == null || agent.getIsAgent() != 1) {
            // 抛出异常，让全局异常处理器捕获，可以自定义一个业务异常
            throw new SecurityException("权限不足，非代理用户");
        }
    }

    /**
     * 代理查看下级用户配额明细
     */
    @SaCheckLogin
    @GetMapping("/user/quotas")
    public Result<?> getSubUserQuotas(
            @RequestParam Long targetUserId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String lineId) {
        try {
            Long agentId = StpUtil.getLoginIdAsLong();
            checkAgentPermission(agentId);
            User targetUser = validateSubordinateUser(agentId, targetUserId);
            List<UserQuotaItemDTO> list = buildUserQuotaItems(targetUser.getId(), targetUser.getUserName(), projectId, lineId);
            return Result.success("查询成功", list);
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("代理查询下级用户配额失败，targetUserId={}", targetUserId, e);
            return Result.error("查询用户配额失败");
        }
    }

    /**
     * 代理查看下级用户配额流水（可按项目+线路筛选）
     */
    @SaCheckLogin
    @GetMapping("/user/quota-ledgers")
    public Result<?> getSubUserQuotaLedgers(
            @RequestParam Long targetUserId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String lineId,
            @RequestParam(required = false) Integer ledgerType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        try {
            Long agentId = StpUtil.getLoginIdAsLong();
            checkAgentPermission(agentId);
            User targetUser = validateSubordinateUser(agentId, targetUserId);
            Page<UserQuotaLedgerItemDTO> result = pageUserQuotaLedgers(
                    targetUser.getId(), targetUser.getUserName(),
                    projectId, lineId, ledgerType, startTime, endTime, page, size
            );
            return Result.success("查询成功", result);
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("代理查询下级用户配额流水失败，targetUserId={}", targetUserId, e);
            return Result.error("查询用户配额流水失败");
        }
    }

    /**
     * 查询当前代理登录用户的所有下级的资金流水表（分页）
     *
     * @param targetUserId (可选) 指定下级用户ID，用于筛选特定用户的流水
     * @param startTime    (可选) 查询起始时间
     * @param endTime      (可选) 查询结束时间
     * @param page         当前页码 默认 1
     * @param size         每页大小 默认 10
     * @return 分页后的资金流水 DTO 列表
     */
    @SaCheckLogin
    @GetMapping("/subordinate-ledgers")
    public Result<?> viewAllSubordinateLedgers(
            @RequestParam(required = false) Long targetUserId,
            @RequestParam(required = false) Date startTime,
            @RequestParam(required = false) Date endTime,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) Integer fundType,    // 新增：接收 fundType 参数
            @RequestParam(required = false) Integer ledgerType,  // 新增：接收 ledgerType 参数
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.error("旧金额账本接口已下线，请使用项目线路配额账本接口");
    }

    /**
     * 获取代理仪表盘统计数据
     * @return 包含余额、下级总数、今日下级充值、下级回码率的 Result 对象
     */
    @SaCheckLogin
    @GetMapping("/dashboard-stats")
    public Result<AgentDashboardStatsDTO> getDashboardStats() {
        long agentId = StpUtil.getLoginIdAsLong();
        try {
            // 调用 Service 层获取统计数据
            AgentDashboardStatsDTO stats = userService.getAgentDashboardStats(agentId);
            return Result.success(stats);
        } catch (BusinessException e) {
            log.warn("获取代理 {} 仪表盘数据业务异常: {}", agentId, e.getMessage());
            return Result.error(e.getMessage());
        }
    }
    /**
     * 查询当前代理用户的项目价格配置
     */
//    @GetMapping("/get/by-agent/project")
//    public Result<?> getByAgenrToProject(){
//        try{
//            StpUtil.checkLogin();
//            Long agentId = StpUtil.getLoginIdAsLong();
//            List<AgentProjectPriceDTO> agentProjectPrices = userService.getAgentProjectPrices(agentId);
//            if (agentProjectPrices.isEmpty()){
//                return Result.success("获取成功，暂无数据");
//            }
//            return Result.success("获取成功",agentProjectPrices);
//        }catch (BusinessException e){
//            return Result.error("获取失败！");
//        }
//    }

    /**
     * 分页查询代理商下级用户的项目价格
     * 前端请求示例: /user/get/by-agent/project?page=1&size=10
     * @return Result<?> 包含分页数据
     */
    @GetMapping("/get/by-agent/project")
    public Result<?> getSubUsersProjectPrices(@RequestParam(required = false) String page, // 改为 String
                                              @RequestParam(required = false) String userName,
                                              @RequestParam(required = false) String size) {
        try {
            // 登录校验
            StpUtil.checkLogin();
            Long agentId = StpUtil.getLoginIdAsLong();

            long pageNum = 1L;
            if (page != null && !page.trim().isEmpty()) {
                try {
                    pageNum = Long.parseLong(page);
                } catch (NumberFormatException e) {
                    // 如果转换失败，可以记录警告并使用默认值
                    log.warn("page 参数 '{}' 格式不正确，将使用默认值 1", page);
                    pageNum = 1L; // 使用默认值
                }
            }

            long pageSize = 10L;
            if (size != null && !size.trim().isEmpty()) {
                try {
                    pageSize = Long.parseLong(size);
                } catch (NumberFormatException e) {
                    log.warn("size 参数 '{}' 格式不正确，将使用默认值 10", size);
                    pageSize = 10L; // 使用默认值
                }
            }
            // 1. 手动创建 MyBatis-Plus 的 Page 对象
            //    将前端传递的 DTO 参数转换成 Service 层需要的 Page 对象
            Page<User> pagea = new Page<>(pageNum, pageSize);

            // 2. 调用 Service 层，方法签名保持不变
            IPage<SubUserProjectPriceDTO> resultPage = userService.getSubUsersProjectPrices(userName,agentId, pagea);

            if (resultPage.getTotal() == 0) {
                return Result.success("查询成功，暂无下级用户或价格数据", resultPage);
            }
            return Result.success("查询成功", resultPage);

        } catch (BusinessException e) {
            log.error("查询下级项目价格失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        }
    }

    /**
     * 更新当前代理用户的项目线路配置（如价格、备注等）传入的是项目配置表的id不是项目id和线路id
     */
    @PostMapping("/update/by-agent/project-config")
    public Result<?> updateAgentProjectConfig(@RequestBody AgentProjectLineUpdateDTO updateDTO) {
        try {
            StpUtil.checkLogin();
            Long agentId = StpUtil.getLoginIdAsLong();
            userService.updateAgentProjectConfig(agentId, updateDTO);
            return Result.success("配置更新成功");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        }
    }

    @Autowired
    private UserProjectLineService userProjectLineService;

    /**
     * 查询代理本人在各项目线路下的剩余配额
     */
    @SaCheckLogin
    @GetMapping("/my/quotas")
    public Result<?> getMyQuotas(
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String lineId) {
        try {
            Long agentId = StpUtil.getLoginIdAsLong();

            // 1. 查询代理本人已存在的配额记录，作为主数据源（必须覆盖“仅有配额但无配置”的场景）
            LambdaQueryWrapper<UserProjectQuota> quotaQuery = new LambdaQueryWrapper<UserProjectQuota>()
                    .eq(UserProjectQuota::getUserId, agentId)
                    .eq(StringUtils.hasText(projectId), UserProjectQuota::getProjectId, projectId)
                    .eq(StringUtils.hasText(lineId), UserProjectQuota::getLineId, lineId);
            List<UserProjectQuota> quotaList = userProjectQuotaService.list(quotaQuery);
            Map<String, Long> quotaMap = new HashMap<>();
            for (UserProjectQuota quota : quotaList) {
                String key = quota.getProjectId() + "_" + quota.getLineId();
                quotaMap.put(key, quota.getAvailableCount() == null ? 0L : quota.getAvailableCount());
            }

            // 2. 获取代理的项目线路配置，并与配额记录做并集，确保“有配额必可见”
            Map<String, UserProjectLine> lineMap = new LinkedHashMap<>();
            List<UserProjectLine> myLines = userProjectLineService.getLinesByUserId(agentId);
            if (myLines != null) {
                for (UserProjectLine line : myLines) {
                    if (!StringUtils.hasText(line.getProjectId()) || !StringUtils.hasText(line.getLineId())) {
                        continue;
                    }
                    if (StringUtils.hasText(projectId) && !projectId.equals(line.getProjectId())) {
                        continue;
                    }
                    if (StringUtils.hasText(lineId) && !lineId.equals(line.getLineId())) {
                        continue;
                    }
                    lineMap.putIfAbsent(line.getProjectId() + "_" + line.getLineId(), line);
                }
            }
            for (UserProjectQuota quota : quotaList) {
                String pid = quota.getProjectId();
                String lid = quota.getLineId();
                if (!StringUtils.hasText(pid) || !StringUtils.hasText(lid)) {
                    continue;
                }
                String key = pid + "_" + lid;
                if (!lineMap.containsKey(key)) {
                    UserProjectLine fallback = new UserProjectLine();
                    fallback.setProjectId(pid);
                    fallback.setLineId(lid);
                    lineMap.put(key, fallback);
                }
            }

            if (lineMap.isEmpty()) {
                return Result.success("查询成功", Collections.emptyList());
            }

            // 3. 查询线路名称（lineName），用于前端展示
            Set<String> projectIds = lineMap.values().stream()
                    .map(UserProjectLine::getProjectId)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toSet());
            Map<String, Project> projectMetaMap = new HashMap<>();
            if (!projectIds.isEmpty()) {
                List<Project> projectList = projectService.list(
                        new LambdaQueryWrapper<Project>()
                                .in(Project::getProjectId, projectIds)
                );
                for (Project project : projectList) {
                    if (!StringUtils.hasText(project.getProjectId()) || !StringUtils.hasText(project.getLineId())) {
                        continue;
                    }
                    String key = project.getProjectId() + "_" + project.getLineId();
                    projectMetaMap.putIfAbsent(key, project);
                }
            }

            // 4. 组装返回数据：没有配额记录的线路默认0
            List<AgentMyQuotaDTO> result = new ArrayList<>();
            for (UserProjectLine line : lineMap.values()) {
                String key = line.getProjectId() + "_" + line.getLineId();
                AgentMyQuotaDTO dto = new AgentMyQuotaDTO();
                dto.setProjectId(line.getProjectId());
                dto.setProjectName(line.getProjectName());
                dto.setLineId(line.getLineId());
                dto.setAvailableCount(quotaMap.getOrDefault(key, 0L));

                Project meta = projectMetaMap.get(key);
                if (meta != null) {
                    if (!StringUtils.hasText(dto.getProjectName())) {
                        dto.setProjectName(meta.getProjectName());
                    }
                    dto.setLineName(meta.getLineName());
                }
                result.add(dto);
            }
            result.sort(Comparator
                    .comparing(AgentMyQuotaDTO::getProjectId, Comparator.nullsLast(String::compareTo))
                    .thenComparing(AgentMyQuotaDTO::getLineId, Comparator.nullsLast(String::compareTo)));

            return Result.success("查询成功", result);
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("查询代理本人配额失败", e);
            return Result.error("查询配额失败");
        }
    }

    /**
     * 查询当前登录代理自己的项目价格
     */
    @GetMapping("/project/price")
    public Result<?> getProjectPrice() {
        //todo 从模板查询代理的价格配置
        try {
            StpUtil.checkLogin();
            long agentId = StpUtil.getLoginIdAsLong();
            List<UserProjectLine> sss = userProjectLineService.getLinesByUserId(agentId);
            return Result.success("查询成功！",sss);
        }catch (BusinessException e){
            return Result.error(e.getMessage());
        }
    }

    @Autowired
    private NumberRecordService numberRecordService;
    /**
     * 数据报表
     */
    @PostMapping("/get/data")
    public Result<?> getData(@RequestBody StatisticsQueryDTO queryDTO){
//        log.info("查询参数：{}",queryDTO);
        try{
            StpUtil.checkLogin();
            Long id  = StpUtil.getLoginIdAsLong();
            IPage<ProjectStatisticsDTO> reportPage = numberRecordService.getStatisticsReport(id, queryDTO);
            return Result.success(reportPage);
        }catch (BusinessException e) {
            return Result.error(e.getMessage());
        }
    }



    /**
     * 分页查询代理下级用户的取号记录（支持多条件筛选）
     *
     * @param queryDTO 包含分页信息和筛选条件的请求体对象。
     *                 可筛选字段: userName(下级用户名), projectName(项目名), projectId, lineId 等。
     * @return 分页后的取号记录列表 (NumberDTO)
     */
    @SaCheckLogin
    @PostMapping("/subordinate-number-records")
    public Result<?> listSubordinateNumberRecords(@RequestBody SubordinateNumberRecordQueryDTO queryDTO) {
        long agentId = StpUtil.getLoginIdAsLong();
        checkAgentPermission(agentId); // 校验代理身份
        try {
            IPage<NumberDTO> resultPage = numberRecordService.listSubordinateRecordsForAgent(agentId, queryDTO);
            return Result.success("查询成功", resultPage);
        } catch (BusinessException e) {
            log.error("代理 [{}] 查询下级取号记录时发生系统内部错误", agentId, e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, e.getMessage());
        }
    }
    /**
     * 查询代理总利润
     */
    @GetMapping("/by-user/totalProfit")
    public Result<?> getTotalProfit() {
        return Result.error("旧金额利润接口已下线，当前模式不再统计金额利润");
    }

    /**
     * 代理-获取下级用户线路统计数据
     * 只能看到自己下级的数据
     */
    @SaCheckLogin
    @PostMapping("/stats/user-line")
    public Result<?> getSubUserLineStats(@RequestBody UserLineStatsRequestDTO requestDTO) {
        try {
            StpUtil.checkLogin();
            Long agentId = StpUtil.getLoginIdAsLong();
            IPage<UserLineStatsDTO> stats = numberRecordService.getUserLineStats(requestDTO, agentId);
            return Result.success("查询成功", stats);
        } catch (Exception e) {
            log.error("代理获取下级统计失败", e);
            return Result.error("获取统计数据失败");
        }
    }

    /**
     * 查询代理自己的账本记录 (对应图片中的筛选功能)
     *
     * @param userName   用户名
     * @param remark     备注
     * @param startTime  开始时间
     * @param endTime    结束时间
     * @param fundType   资金类型
     * @param ledgerType 账本类型
     * @param page       页码
     * @param size       每页条数
     */
    @SaCheckLogin
    @GetMapping("/my-ledger")
    public Result<?> getMyLedger(
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) String remark,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(required = false) Integer fundType,
            @RequestParam(required = false) Integer ledgerType,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.error("旧金额账本接口已下线，请使用项目线路配额账本接口");
    }

    /**
     * 代理批量删除下级用户
     * @param userIds 用户ID列表
     */
    @SaCheckLogin
    @PostMapping("/deleteUsers")
    public Result<?> deleteUsersBatch(@RequestBody List<Long> userIds) {
        long agentId = StpUtil.getLoginIdAsLong();
        // 再次校验代理权限
        checkAgentPermission(agentId);

        if (userIds == null || userIds.isEmpty()) {
            return Result.error("参数不能为空");
        }

        try {
            userService.deleteSubUsersBatch(userIds, agentId);
            return Result.success("删除成功");
        } catch (BusinessException e) {
            log.warn("代理 {} 删除用户失败: {}", agentId, e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("代理 {} 批量删除用户系统异常", agentId, e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，删除失败");
        }
    }
    /**
     * 获取用户的配置信息
     * 返回：项目黑名单 (blacklist)
     *
     * @param userId 用户ID
     * @return Map包含配置信息
     */
    @GetMapping("/user/config-info")
    public Result<?> getUserConfigInfo(@RequestParam @NotNull Long userId) {
        try {
            User user = userService.getById(userId);
            if (user == null) {
                return Result.error("用户不存在");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("blacklist", user.getProjectBlacklist());
            return Result.success("查询成功", result);
        } catch (Exception e) {
            log.error("获取用户配置信息失败: userId={}", userId, e);
            return Result.error("系统错误，获取配置失败");
        }
    }

    /**
     * 代理：清理自己的历史账本记录
     */
    @SaCheckLogin
    @PostMapping("/ledger/clear")
    public Result<?> clearMyLedger() {
        return Result.error("旧金额账本清理接口已下线，无需执行");
    }

    /**
     * 代理：清理自己的历史号码记录
     *
     */
    @SaCheckLogin
    @PostMapping("/number/clear")
    public Result<?> clearMyNumberRecords() {
        SystemConfig systemConfig = systemConfigService.getConfig();
        Integer days = Integer.valueOf(systemConfig.getUserDeleteDataDay());
        try {
            long agentId = StpUtil.getLoginIdAsLong();
            numberRecordService.deleteNumberRecordByDays(agentId, agentId, days, false);
            log.info("代理 {} 清理了自己 {} 天前的号码记录", agentId, days);
            return Result.success("个人号码记录清理成功");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("代理清理号码记录异常: ", e);
            return Result.error("系统繁忙");
        }
    }

    /**
     * 校验目标用户是否为当前代理的直属下级
     */
    private User validateSubordinateUser(Long agentId, Long targetUserId) {
        if (targetUserId == null || targetUserId <= 0) {
            throw new BusinessException("目标用户参数非法");
        }
        User targetUser = userService.getById(targetUserId);
        if (targetUser == null) {
            throw new BusinessException("目标用户不存在");
        }
        if (!agentId.equals(targetUser.getParentId())) {
            throw new BusinessException("无权查看非下级用户配额");
        }
        return targetUser;
    }

    /**
     * 构建用户配额明细（配额表 + 用户项目线路配置并集）
     */
    private List<UserQuotaItemDTO> buildUserQuotaItems(Long userId, String userName, String projectId, String lineId) {
        LambdaQueryWrapper<UserProjectQuota> quotaQuery = new LambdaQueryWrapper<UserProjectQuota>()
                .eq(UserProjectQuota::getUserId, userId)
                .eq(StringUtils.hasText(projectId), UserProjectQuota::getProjectId, projectId)
                .eq(StringUtils.hasText(lineId), UserProjectQuota::getLineId, lineId);
        List<UserProjectQuota> quotaList = userProjectQuotaService.list(quotaQuery);
        Map<String, Long> quotaMap = new HashMap<>();
        for (UserProjectQuota quota : quotaList) {
            quotaMap.put(quota.getProjectId() + "_" + quota.getLineId(),
                    quota.getAvailableCount() == null ? 0L : quota.getAvailableCount());
        }

        Map<String, UserProjectLine> lineMap = new LinkedHashMap<>();
        List<UserProjectLine> userLines = userProjectLineService.getLinesByUserId(userId);
        if (userLines != null) {
            for (UserProjectLine line : userLines) {
                if (!StringUtils.hasText(line.getProjectId()) || !StringUtils.hasText(line.getLineId())) {
                    continue;
                }
                if (StringUtils.hasText(projectId) && !projectId.equals(line.getProjectId())) {
                    continue;
                }
                if (StringUtils.hasText(lineId) && !lineId.equals(line.getLineId())) {
                    continue;
                }
                lineMap.putIfAbsent(line.getProjectId() + "_" + line.getLineId(), line);
            }
        }
        for (UserProjectQuota quota : quotaList) {
            String key = quota.getProjectId() + "_" + quota.getLineId();
            if (!lineMap.containsKey(key)) {
                UserProjectLine fallback = new UserProjectLine();
                fallback.setProjectId(quota.getProjectId());
                fallback.setLineId(quota.getLineId());
                lineMap.put(key, fallback);
            }
        }

        if (lineMap.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> projectIds = lineMap.values().stream()
                .map(UserProjectLine::getProjectId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, Project> projectMetaMap = buildProjectMetaMap(projectIds);

        List<UserQuotaItemDTO> result = new ArrayList<>();
        for (UserProjectLine line : lineMap.values()) {
            String key = line.getProjectId() + "_" + line.getLineId();
            UserQuotaItemDTO dto = new UserQuotaItemDTO();
            dto.setUserId(userId);
            dto.setUserName(userName);
            dto.setProjectId(line.getProjectId());
            dto.setProjectName(line.getProjectName());
            dto.setLineId(line.getLineId());
            dto.setAvailableCount(quotaMap.getOrDefault(key, 0L));
            Project meta = projectMetaMap.get(key);
            if (meta != null) {
                if (!StringUtils.hasText(dto.getProjectName())) {
                    dto.setProjectName(meta.getProjectName());
                }
                dto.setLineName(meta.getLineName());
            }
            result.add(dto);
        }
        result.sort(Comparator.comparing(UserQuotaItemDTO::getProjectId, Comparator.nullsLast(String::compareTo))
                .thenComparing(UserQuotaItemDTO::getLineId, Comparator.nullsLast(String::compareTo)));
        return result;
    }

    /**
     * 分页查询用户配额流水
     */
    private Page<UserQuotaLedgerItemDTO> pageUserQuotaLedgers(
            Long userId, String userName,
            String projectId, String lineId, Integer ledgerType,
            LocalDateTime startTime, LocalDateTime endTime,
            long page, long size) {
        LambdaQueryWrapper<UserProjectQuotaLedger> queryWrapper = new LambdaQueryWrapper<UserProjectQuotaLedger>()
                .eq(UserProjectQuotaLedger::getUserId, userId)
                .eq(StringUtils.hasText(projectId), UserProjectQuotaLedger::getProjectId, projectId)
                .eq(StringUtils.hasText(lineId), UserProjectQuotaLedger::getLineId, lineId)
                .eq(ledgerType != null, UserProjectQuotaLedger::getLedgerType, ledgerType)
                .ge(startTime != null, UserProjectQuotaLedger::getTimestamp, startTime)
                .le(endTime != null, UserProjectQuotaLedger::getTimestamp, endTime)
                .orderByDesc(UserProjectQuotaLedger::getTimestamp);

        Page<UserProjectQuotaLedger> entityPage = new Page<>(page, size);
        userProjectQuotaLedgerMapper.selectPage(entityPage, queryWrapper);

        Set<String> projectIds = entityPage.getRecords().stream()
                .map(UserProjectQuotaLedger::getProjectId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
        Map<String, Project> projectMetaMap = buildProjectMetaMap(projectIds);

        Set<Long> operatorIds = entityPage.getRecords().stream()
                .map(UserProjectQuotaLedger::getOperatorId)
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .collect(Collectors.toSet());
        Map<Long, String> operatorNameMap = new HashMap<>();
        if (!operatorIds.isEmpty()) {
            operatorNameMap = userService.listByIds(operatorIds).stream()
                    .collect(Collectors.toMap(User::getId, User::getUserName));
        }

        List<UserQuotaLedgerItemDTO> dtoRecords = new ArrayList<>();
        for (UserProjectQuotaLedger entity : entityPage.getRecords()) {
            UserQuotaLedgerItemDTO dto = new UserQuotaLedgerItemDTO();
            dto.setId(entity.getId());
            dto.setBizNo(entity.getBizNo());
            dto.setUserId(entity.getUserId());
            dto.setUserName(userName);
            dto.setOperatorId(entity.getOperatorId());
            dto.setOperatorName(entity.getOperatorId() != null && entity.getOperatorId() == 0L
                    ? "管理员"
                    : operatorNameMap.getOrDefault(entity.getOperatorId(), "未知"));
            dto.setProjectId(entity.getProjectId());
            dto.setLineId(entity.getLineId());
            dto.setChangeCount(entity.getChangeCount());
            dto.setLedgerType(entity.getLedgerType());
            dto.setCountBefore(entity.getCountBefore());
            dto.setCountAfter(entity.getCountAfter());
            dto.setRemark(entity.getRemark());
            dto.setTimestamp(entity.getTimestamp());

            String key = entity.getProjectId() + "_" + entity.getLineId();
            Project meta = projectMetaMap.get(key);
            if (meta != null) {
                dto.setProjectName(meta.getProjectName());
                dto.setLineName(meta.getLineName());
            }
            dtoRecords.add(dto);
        }

        Page<UserQuotaLedgerItemDTO> result = new Page<>(page, size);
        result.setTotal(entityPage.getTotal());
        result.setRecords(dtoRecords);
        return result;
    }

    /**
     * 构建项目元数据映射
     */
    private Map<String, Project> buildProjectMetaMap(Set<String> projectIds) {
        Map<String, Project> projectMetaMap = new HashMap<>();
        if (projectIds == null || projectIds.isEmpty()) {
            return projectMetaMap;
        }
        List<Project> projectList = projectService.list(
                new LambdaQueryWrapper<Project>().in(Project::getProjectId, projectIds)
        );
        for (Project project : projectList) {
            if (!StringUtils.hasText(project.getProjectId()) || !StringUtils.hasText(project.getLineId())) {
                continue;
            }
            projectMetaMap.putIfAbsent(project.getProjectId() + "_" + project.getLineId(), project);
        }
        return projectMetaMap;
    }

}
