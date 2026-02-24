package com.wzz.smscode.controller.admin;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.common.Constants;
import com.wzz.smscode.common.Result;
import com.wzz.smscode.dto.*;
import com.wzz.smscode.dto.CreatDTO.UserCreateDTO;
import com.wzz.smscode.dto.EntityDTO.UserDTO;
import com.wzz.smscode.dto.LoginDTO.AdminLoginDTO;
import com.wzz.smscode.dto.number.NumberDTO;
import com.wzz.smscode.dto.project.ProjectPriceInfoDTO;
import com.wzz.smscode.dto.project.SubUserProjectPriceDTO;
import com.wzz.smscode.dto.update.UpdateUserDto;
import com.wzz.smscode.dto.update.UserUpdateDtoByUser;
import com.wzz.smscode.entity.*;
import com.wzz.smscode.enums.AuthType;
import com.wzz.smscode.enums.RequestType;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.moduleService.ModuleUtil;
import com.wzz.smscode.moduleService.SmsApiService;
import com.wzz.smscode.service.*;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 管理员后台接口控制器
 * <p>
 * 提供系统级的用户管理、资金监控、系统配置等功能。
 * 所有接口都需要管理员ID和密码进行验证。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    @Value("${admin.username}")
    private String username;

    @Value("${admin.password}")
    private String password;

    @Autowired private UserService userService;
    @Autowired private UserProjectQuotaService userProjectQuotaService;
    @Autowired private NumberRecordService numberRecordService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private ProjectService projectService;


    @Autowired
    @Lazy
    private PriceTemplateService priceTemplateService;

    private final ErrorLogService errorLogService;
    public AdminController(ErrorLogService errorLogService) {
        this.errorLogService = errorLogService;
    }

    /**
     * 管理员用户登录
     */
    @PostMapping("/login")
    public Result<?> adminLogin(@RequestBody AdminLoginDTO adminLoginDTO){
        if (username.equals(adminLoginDTO.getUsername()) && password.equals(adminLoginDTO.getPassword())){
            Map<String,String> loginData = new HashMap<>();
            StpUtil.login("0"); // 登录ID为 "0"，代表管理员
            loginData.put("token",StpUtil.getTokenValue());
            return Result.success("管理员登录成功，返回token", StpUtil.getTokenValue());
        }else {
            return Result.error("用户名密码不正确！");
        }
    }


    /**
     * 列出用户列表。
     * 根据提供的参数，可以查询所有用户或特定用户的直接下级。
     *
     * 修改点：
     * 1. parentId 参数改为 parentName，通过上级用户名查询下级。
     * 2. 返回结果中填充 parentName 字段。
     *
     * @param parentName 可选参数，指定上级用户的用户名
     * @param userName   可选参数，模糊搜索当前用户的用户名
     * @param page       页码，默认为1
     * @param size       每页显示的记录数，默认为10
     * @return 返回用户列表，包含上级用户名信息
     */
    @RequestMapping(value = "/listUsers", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<?> listUsers(
            @RequestParam(required = false) String parentName,
            @RequestParam(required = false) String userName,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {

        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();

        // 1. 如果传入了上级用户名，先查询该上级用户的ID
        if (StringUtils.hasText(parentName)) {
            User parentUser = userService.getOne(new LambdaQueryWrapper<User>()
                    .eq(User::getUserName, parentName)
                    .last("LIMIT 1")); // 保证只查一条，提高效率

            if (parentUser == null) {
                // 如果指定的上级用户不存在，则其下级肯定也不存在，直接返回空分页
                return Result.success("查询成功", new Page<User>(page, size));
            }
            // 设置查询条件：parent_id = 查出来的ID
            queryWrapper.eq(User::getParentId, parentUser.getId());
        }

        // 2. 处理当前用户名的模糊查询
        if (StringUtils.hasText(userName)) {
            queryWrapper.like(User::getUserName, userName);
        }

        // 按创建时间倒序
        queryWrapper.orderByDesc(User::getCreateTime);

        // 3. 执行分页查询
        IPage<User> pageRequest = new Page<>(page, size);
        IPage<User> userPage = userService.page(pageRequest, queryWrapper);

        List<User> records = userPage.getRecords();

        // 4. 数据填充 (上级用户名 & 模板名称)
        if (!CollectionUtils.isEmpty(records)) {
            // --- 提取 ID 集合 ---
            Set<Long> parentIds = new HashSet<>();
            Set<Long> templateIds = new HashSet<>();

            for (User user : records) {
                if (user.getParentId() != null && user.getParentId() != 0L) {
                    parentIds.add(user.getParentId());
                }
                if (user.getTemplateId() != null && user.getTemplateId() != 0L) {
                    templateIds.add(user.getTemplateId());
                }
            }

            // --- A. 批量查询并填充上级用户名 ---
            Map<Long, String> parentMap = new HashMap<>();
            if (!CollectionUtils.isEmpty(parentIds)) {
                List<User> parents = userService.list(new LambdaQueryWrapper<User>()
                        .in(User::getId, parentIds)
                        .select(User::getId, User::getUserName));
                parentMap = parents.stream().collect(Collectors.toMap(User::getId, User::getUserName));
            }

            // --- B. 批量查询并填充模板名称 (新增逻辑) ---
            Map<Long, String> templateMap = new HashMap<>();
            if (!CollectionUtils.isEmpty(templateIds)) {
                // 使用 PriceTemplateService 批量查询
                List<PriceTemplate> templates = priceTemplateService.list(new LambdaQueryWrapper<PriceTemplate>()
                        .in(PriceTemplate::getId, templateIds)
                        .select(PriceTemplate::getId, PriceTemplate::getName)); // 只查ID和Name优化性能

                templateMap = templates.stream()
                        .collect(Collectors.toMap(PriceTemplate::getId, PriceTemplate::getName));
            }

            // --- C. 遍历回填数据 ---
            for (User user : records) {
                // 回填上级名称
                Long pid = user.getParentId();
                if (pid != null && parentMap.containsKey(pid)) {
                    user.setParentName(parentMap.get(pid));
                }

                // 回填模板名称 (新增逻辑)
                Long tid = user.getTemplateId();
                if (tid != null && templateMap.containsKey(tid)) {
                    user.setTemplateName(templateMap.get(tid));
                }
            }
        }

        return Result.success("查询成功", userPage);
    }
    /**
     * 创建新用户或代理账户
     * @param userCreateDTO
     * @return
     */
    @PostMapping("/createUser")
    public Result<?> createUser(@RequestBody UserCreateDTO userCreateDTO) {
        try {
            // + 管理员操作，operatorId 直接传入 0L
            boolean success = userService.createUser(userCreateDTO, 0L);
            return success ? Result.success("创建成功") : Result.error(-5, "创建失败");
        }catch (IllegalArgumentException | SecurityException | IllegalStateException  e) {
            // 记录业务异常信息，但返回通用错误提示
            log.warn("创建用户业务校验失败: {}", e.getMessage());
            return Result.error("创建失败，输入信息有误或权限不足");
        } catch (BusinessException e) {
            log.error("创建用户时发生系统业务内部错误", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, e.getMessage());
        } catch (Exception e) {
            // 记录未预料到的系统异常
            log.error("创建用户时发生系统内部错误", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "创建用户时发生系统内部错误，请联系管理员");
        }
    }


    /**
     * 获取所有代理商列表（用于下拉选择）
     */
    @GetMapping("/listAllAgents")
    public Result<List<User>> listAllAgents() {
        // 查询所有 is_agent = 1 的用户
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getIsAgent, 1)
                .eq(User::getStatus, 0) // 可选：只查询状态正常的代理
                .orderByDesc(User::getCreateTime);
        // 为了安全和传输效率，这里最好只返回 ID 和 UserName，或者使用特定的 DTO
        // 但为了简便，这里暂时返回 User 实体，前端取 id 和 userName 即可
        List<User> agents = userService.list(queryWrapper);
        return Result.success(agents);
    }

    /**
     * 编辑用户信息
     * @param userDTO
     * @return
     */
    @PostMapping("/updateUser")
    public Result<?> updateUser(@RequestBody UserUpdateDtoByUser userDTO) {
        log.info("编辑用户信息接口： updateUser: {}", userDTO);
        try {
            boolean success = userService.updateUserByAgent(userDTO, 0L);
            return success ? Result.success("修改成功") : Result.error(-5, "信息无变化或修改失败");
        } catch (Exception e) {
            return Result.error(-5, e.getMessage());
        }
    }

    /**
     * 根据用户id更新密码
     */
    @PostMapping("/update/passward")
    public Result<?> updateByUserIdToPassWard(@RequestBody UpdateUserDto updateUserDto ){
        try{
            Boolean is_ok = userService.updatePassWardByUserId(updateUserDto);
            if (is_ok){
                return Result.success("更新成功");
            }
            return Result.error("更新失败！");
        }catch (BusinessException e){
            return Result.error(e.getMessage());
        }
    }

    /**
     * 为用户充值（系统资金）
     * @param targetUserId 目标用户ID
     * @param amount 充值金额
     * @return
     */
    @PostMapping("/rechargeUser")
    public CommonResultDTO<?> rechargeUser(
            //- @RequestParam Long adminId,
            //- @RequestParam String password,
            @RequestParam Long targetUserId,
            @RequestParam String projectId,
            @RequestParam String lineId,
            @RequestParam Long count) {
        // + 管理员操作，operatorId 直接传入 0L
        return userService.rechargeUser(targetUserId, projectId, lineId, count, 0L);
    }

    /**
     * 扣减用户余额（回收系统）
     * @param targetUserId 目标用户ID
     * @param amount 扣款金额
     * @return
     */
    @PostMapping("/deductUser")
    public CommonResultDTO<?> deductUser(
            //- @RequestParam Long adminId,
            //- @RequestParam String password,
            @RequestParam Long targetUserId,
            @RequestParam String projectId,
            @RequestParam String lineId,
            @RequestParam Long count) {
        // + 管理员操作，operatorId 直接传入 0L
        return userService.deductUser(targetUserId, projectId, lineId, count, 0L);
    }

    /**
     * 删除/禁用用户账户
     * @param targetUserId 目标用户ID
     * @return
     */
    @PostMapping("/deleteUser")
    public Result<?> deleteUser(
            //- @RequestParam Long adminId,
            //- @RequestParam String password,
            @RequestParam Long targetUserId) {
        // 采用软删除，将用户状态设置为-1（禁用）
        UserDTO userDTO = new UserDTO();
        userDTO.setUserId(targetUserId);
        userDTO.setStatus(-1); // -1 代表禁用
        try {
            // + 管理员操作，operatorId 直接传入 0L
//            boolean success = userService.updateUser(userDTO, 0L);
            boolean success = userService.delectByuserId(userDTO.getUserId());

            return success ? Result.success("用户删除成功") : Result.error(-5, "删除失败");
        } catch (BusinessException e) {
            return Result.error(-5, e.getMessage());
        }
    }

    // --- 账本与记录查询 ---




    /**
     * 查看指定用户的账本明细
     * @param targetUserId 目标用户 ID
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param page 页码
     * @param size 每页数量
     * @return
     */
    @RequestMapping(value = "/viewUserLedger", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<?> viewUserLedger(
            //- @RequestParam Long adminId,
            //- @RequestParam String password,
            @RequestParam Long targetUserId,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.error("旧金额账本接口已下线，请使用项目线路配额账本接口");
    }

    /**
     * 查看全局账本记录
     * @param username 用户名（模糊查询）
     * @param filterByUserId 用户ID
     * @param remark 备注（模糊查询）
     * @param fundType 资金类型（0-业务扣费, 1-后台操作）
     * @param ledgerType 账本类型（1-入账，0-出账）
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param page 页码
     * @param size 每页数量
     * @return
     */
    @RequestMapping(value = "/viewAllLedger", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<?> viewAllLedger(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) Long filterByUserId,
            @RequestParam(required = false) String remark,
            @RequestParam(required = false) Integer fundType, // 新增：资金类型
            @RequestParam(required = false) Integer ledgerType, // 新增：账本类型
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.error("旧金额账本接口已下线，请使用项目线路配额账本接口");
    }

    /**
     * 查看某用户的号码记录
     * @param targetUserId 目标用户ID
     * @param status 状态过滤
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param page 页码
     * @param size 每页数量
     * @return
     */
    @RequestMapping(value = "/viewUserNumbers", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<IPage<NumberDTO>> viewUserNumbers(
            //- @RequestParam Long adminId,
            //- @RequestParam String password,
            @RequestParam Long targetUserId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {

        IPage<NumberRecord> pageRequest = new Page<>(page, size);
        IPage<NumberDTO> resultPage = numberRecordService.listUserNumbers(targetUserId, null, status, startTime, endTime, pageRequest);
        return Result.success("查询成功", resultPage);
    }

    /**
     * 查询号码记录
     * @param status 状态
     * @param startTime 开始时间
     * @param endTime 结束时间
     * @param page 页面
     * @param size 页小
     * @param userId 用户id
     * @param projectId 项目id
     * @param lineId 线路id
     * @param phoneNumber 手机号码
     * @param charged 扣费状态
     * @return
     */
    @RequestMapping(value = "/viewAllNumbers", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<IPage<NumberRecord>> viewAllNumbers(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long userId, //用户id
            @RequestParam(required = false) String projectId, //项目id
            @RequestParam(required = false) String lineId, //线路id
            @RequestParam(required = false) String phoneNumber, // 用于模糊查询
            @RequestParam(required = false) Integer charged, //扣费状态
            @RequestParam(required = false) String userName // 用于模糊查询
    ) {

        IPage<NumberRecord> pageRequest = new Page<>(page, size);
        // 调用服务层方法，并传入所有参数
        IPage<NumberRecord> resultPage = numberRecordService.listAllNumbers(
                status, startTime, endTime, userId, projectId, phoneNumber, charged, pageRequest,lineId,userName
        );

        return Result.success("查询成功", resultPage);
    }


    // --- 系统统计与配置 ---

    @Data
    private static class SystemStatsDTO {
        /**
         * 总共用户数
         */
        private Long totalUsers;
        /**
         * 总代理数
         */
        private Long totalAgents;
        /**
         * 总取号数
         */
        private Long totalNumbersReceived;
        /**
         * 总取码数
         */
        private Long totalCodesReceived;
        /**
         * 总体回码率
         */
        private String overallCodeRate;
        /**
         * 近24小时取号数
         */
        private Long last24hNumbers;
        /**
         * 近24小时取码数
         */

        private Long last24hCodes;

        /**
         * 近24小时回码率
         */
        private String last24hCodeRate;

        /**
         * 总余额
         */
        private String totalPrice;

        // 新增：平台总利润
        private BigDecimal totalProfit;
    }

    /**
     * 获取系统的总体统计数据。
     *
     * @return 返回一个包含系统统计数据的Result对象。如果管理员验证失败或无权限，则返回错误信息；否则返回成功信息及统计数据。
     */
    @GetMapping("/statistics")
    public Result<?> getStatistics() {

//        if (checkAdminPermission(adminId, password).getStatus() != 0) {
//            return Result.error(Constants.ERROR_AUTH_FAILED, "管理员验证失败或无权限");
//        }

        SystemStatsDTO stats = new SystemStatsDTO();
        stats.setTotalUsers(userService.count());
        stats.setTotalAgents(userService.count(new LambdaQueryWrapper<User>().eq(User::getIsAgent, 1)));

        stats.setTotalNumbersReceived(numberRecordService.count());
        stats.setTotalCodesReceived(numberRecordService.count(new LambdaQueryWrapper<NumberRecord>().eq(NumberRecord::getStatus, 2)));
        if (stats.getTotalNumbersReceived() > 0) {
            stats.setOverallCodeRate(
                    BigDecimal.valueOf(stats.getTotalCodesReceived())
                            .divide(BigDecimal.valueOf(stats.getTotalNumbersReceived()), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP) + "%"
            );
        } else {
            stats.setOverallCodeRate("0.00%");
        }

        LocalDateTime yesterday = LocalDateTime.now().minusHours(24);
        stats.setLast24hNumbers(numberRecordService.count(new LambdaQueryWrapper<NumberRecord>().ge(NumberRecord::getGetNumberTime, yesterday)));
        stats.setLast24hCodes(numberRecordService.count(new LambdaQueryWrapper<NumberRecord>().eq(NumberRecord::getStatus, 2).ge(NumberRecord::getGetNumberTime, yesterday)));
        if (stats.getLast24hNumbers() > 0) {
            stats.setLast24hCodeRate(
                    BigDecimal.valueOf(stats.getLast24hCodes())
                            .divide(BigDecimal.valueOf(stats.getLast24hNumbers()), 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP) + "%"
            );
        } else {
            stats.setLast24hCodeRate("0.00%");
        }
        // 统计全系统剩余配额总量，替代旧 balance 汇总。
        QueryWrapper<UserProjectQuota> quotaQueryWrapper = new QueryWrapper<>();
        quotaQueryWrapper.select("IFNULL(sum(available_count), 0) as totalCount");
        Map<String, Object> quotaMap = userProjectQuotaService.getMap(quotaQueryWrapper);
        long totalQuota = 0L;
        if (quotaMap != null && quotaMap.get("totalCount") != null) {
            totalQuota = Long.parseLong(quotaMap.get("totalCount").toString());
        }

        // 旧金额利润字段已弃用，统一返回0。
        stats.setTotalProfit(BigDecimal.ZERO);
        // 为兼容前端旧字段 totalPrice，这里返回“总剩余配额数”。
        stats.setTotalPrice(String.valueOf(totalQuota));

        return Result.success("统计数据获取成功", stats);
    }

    /**
     * 获取公告接口
     */
    @GetMapping("/get/user/notice")
    public Result<?> getUserNotice(){
        SystemConfig config = systemConfigService.getConfig();
        return Result.success(config.getSystemNotice());
    }

    /**
     * 获取系统配置信息。
     * @return 返回包含系统配置的Result对象。如果验证失败或无权限，则返回错误信息；否则返回获取到的系统配置信息。
     */
    @RequestMapping(value = "/getConfig", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<SystemConfig> getConfig() {
        return Result.success("获取成功", systemConfigService.getConfig());
    }

    /**
     * 更新系统配置信息。

     * @param config 系统配置信息，必须提供
     * @return 返回操作结果，成功则返回包含"更新成功"的消息；失败则根据情况返回错误代码和相应的错误消息
     */
    @PostMapping("/updateConfig")
    public Result<?> updateConfig(
             @RequestBody SystemConfig config) {
        try {
            boolean success = systemConfigService.updateConfig(config);
            return success ? Result.success("更新成功") : Result.error(-5, "更新失败");
        } catch (BusinessException e) {
            return Result.error(-5, e.getMessage());
        }
    }

    /**
     * 根据手机号获取验证码()
     */
    @PostMapping("/by-phone/get/code/{phone}")
    public Result<?> getCodeByPhone(@PathVariable String phone){
        NumberRecord numberRecord = numberRecordService.getRecordByPhone(phone);
        return Result.success(numberRecord);
    }





    /**
     * 查询错误日志列表。
     *
     * @return 返回一个Result对象，包含查询结果或错误信息。如果管理员验证失败或无
     * */
    @RequestMapping(value = "/listErrorLogs", method = {RequestMethod.GET, RequestMethod.POST})
    public Result<?> listErrorLogs(@RequestParam(defaultValue = "1") long page,
                                   @RequestParam(defaultValue = "10") long size) {
        Page pageRequest = new Page<>(page, size);
        IPage<ErrorLog> resultPage = errorLogService.page(pageRequest);

        return Result.success(resultPage);
    }

    /**
     * 获取特定错误日志的详情。
     *
     * @param errorId 错误日志的唯一标识符，用于定位具体的错误日志记录。
     * @return 返回一个Result对象，包含请求的结果。如果管理员验证失败或无权限访问，则返回相应的错误信息；当前实现中，此方法直接返回功能尚未实现的信息。
     */
    @GetMapping("/getErrorDetail")
    public Result<?> getErrorDetail( @RequestParam Long errorId) {
        return Result.success(errorLogService.getErrorDetail(errorId));
    }


    @Data
    public static class DailyStatsDTO {
        private String date;
        private Long numberCount;
        private Long codeCount;
    }

    /**
     * 返回每日号码/验证码数量趋势
     * @param days 查询最近 N 天的数据，默认为 30
     * @return 包含每日统计数据的列表
     */
    @GetMapping("/dailyStatsTrend")
    public Result<List<DailyStatsDTO>> getDailyStatsTrend(@RequestParam(defaultValue = "30") int days) {
        try {
            LocalDateTime startTime = LocalDateTime.now().minusDays(days);
            QueryWrapper<NumberRecord> queryWrapper = new QueryWrapper<>();
            queryWrapper.select(
                            "DATE(get_number_time) as date",
                            "count(id) as numberCount", // 统计总取号数
                            "count(code) as codeCount"   // 统计 code 字段非空的记录数，即成功获取验证码的数量
                    )
                    .ge("get_number_time", startTime)
                    .groupBy("date")
                    .orderByAsc("date");

            List<Map<String, Object>> resultMaps = numberRecordService.listMaps(queryWrapper);

            List<DailyStatsDTO> dailyStats = resultMaps.stream().map(map -> {
                DailyStatsDTO dto = new DailyStatsDTO();
                // 获取日期
                Object dateObj = map.get("date");
                dto.setDate(dateObj != null ? dateObj.toString() : "");

                // 获取取号总数 (数据库返回的可能是 Long 或 BigInteger, 用 Number 类型接收最稳妥)
                Object numberCountObj = map.getOrDefault("numberCount", 0);
                dto.setNumberCount(((Number) numberCountObj).longValue());

                // 获取成功验证码数
                Object codeCountObj = map.getOrDefault("codeCount", 0);
                dto.setCodeCount(((Number) codeCountObj).longValue());

                return dto;
            }).collect(Collectors.toList());

            return Result.success("查询成功", dailyStats);
        } catch (Exception e) {
            log.error("查询每日趋势数据失败", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，查询趋势数据失败");
        }
    }

    /**
     * 获取项目认证类型枚举
     * @return
     */
    @GetMapping("/enum/auth-types")
    public Result<?> getAuthTypes() {
        List<Map<String, String>> aa = Arrays.stream(AuthType.values())
                .map(authType -> {
                    Map<String, String> map = new HashMap<>();
                    // key "value" 对应枚举的 value 字段
                    map.put("value", authType.getValue());
                    // key "description" 对应枚举的 description 字段
                    map.put("description", authType.getDescription());
                    return map;
                })
                .toList();
        return Result.success(aa);
    }

    /**
     * 获取项目请求类型枚举
     * @return
     */
    @GetMapping("/enum/request-types")
    public Result<?> getRequestTypes() {
        List<Map<String, String>> aa = Arrays.stream(RequestType.values())
                .map(authType -> {
                    Map<String, String> map = new HashMap<>();
                    // key "value" 对应枚举的 value 字段
                    map.put("value", authType.getCode());
                    // key "description" 对应枚举的 description 字段
                    map.put("description", authType.getDesc());
                    return map;
                })
                .toList();

        return Result.success(aa);
    }

    @Autowired
    private UserProjectLineService userProjectLineService;
    /**
     * 分页并按条件筛选获取用户及其项目价格配置
     *
     * @param pageNum   当前页码
     * @param pageSize  每页数量
     * @param userName  用户名 (可选, 模糊查询)
     * @param projectId 项目ID (可选, 精确查询)
     * @param lineId    线路ID (可选, 精确查询)
     * @return 返回一个包含分页和筛选结果的用户项目配置列表
     */
    @GetMapping("/user-project-prices")
    public Result<?> getAllUserProjectPrices(
            @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "10") long pageSize,
            @RequestParam(required = false) String userName,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long lineId
    ) {
        try {
            // 1. 创建 MyBatis-Plus 分页对象
            IPage<UserProjectLine> page = new Page<>(pageNum, pageSize);

            // 2. 构建动态查询条件
            QueryWrapper<UserProjectLine> queryWrapper = new QueryWrapper<>();

            // 2.1 处理用户名筛选
            if (StringUtils.hasText(userName)) {
                // 根据用户名模糊查询出对应的用户ID列表
                QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
                userQueryWrapper.lambda().like(User::getUserName, userName);
                List<User> matchedUsers = userService.list(userQueryWrapper);

                // 如果没有找到匹配的用户，直接返回空结果，避免查询 user_project_line 表
                if (CollectionUtils.isEmpty(matchedUsers)) {
                    return Result.success("查询成功", new Page<>(pageNum, pageSize, 0));
                }

                Set<Long> userIds = matchedUsers.stream().map(User::getId).collect(Collectors.toSet());
                queryWrapper.lambda().in(UserProjectLine::getUserId, userIds);
            }

            // 2.2 处理项目ID筛选
            if (projectId != null) {
                queryWrapper.lambda().eq(UserProjectLine::getProjectId, projectId);
            }

            // 2.3 处理线路ID筛选
            if (lineId != null) {
                queryWrapper.lambda().eq(UserProjectLine::getLineId, lineId);
            }

            // 可选：添加默认排序，让结果更稳定
            queryWrapper.lambda().orderByDesc(UserProjectLine::getCreateTime);

            // 3. 执行带条件的分页查询
            IPage<UserProjectLine> pagedResult = userProjectLineService.page(page, queryWrapper);
            List<UserProjectLine> currentPageLines = pagedResult.getRecords();

            if (CollectionUtils.isEmpty(currentPageLines)) {
                return Result.success("查询成功", pagedResult); // 返回空的分页结果
            }

            // 4. 按 userId 进行分组
            Map<Long, List<UserProjectLine>> groupedByUserId = currentPageLines.stream()
                    .collect(Collectors.groupingBy(UserProjectLine::getUserId));

            // 5. 提取当前页所有相关的用户ID
            Set<Long> userIdsOnPage = groupedByUserId.keySet();

            // 6. 一次性查询所有相关用户信息
            List<User> users = userService.listByIds(userIdsOnPage);
            Map<Long, String> userIdToNameMap = users.stream()
                    .collect(Collectors.toMap(User::getId, User::getUserName));

            // 7. 组装成 SubUserProjectPriceDTO 列表
            List<SubUserProjectPriceDTO> resultList = new ArrayList<>();
            for (Map.Entry<Long, List<UserProjectLine>> entry : groupedByUserId.entrySet()) {
                Long userId = entry.getKey();
                List<UserProjectLine> userLines = entry.getValue();

                SubUserProjectPriceDTO subUserDto = new SubUserProjectPriceDTO();
                subUserDto.setUserId(userId);
                subUserDto.setUserName(userIdToNameMap.getOrDefault(userId, "未知用户"));

                List<ProjectPriceInfoDTO> priceInfoList = userLines.stream().map(line -> {
                    ProjectPriceInfoDTO priceDto = new ProjectPriceInfoDTO();
                    priceDto.setId(line.getId());
                    priceDto.setUserProjectLineTableId(line.getId());
                    priceDto.setProjectName(line.getProjectName());
                    priceDto.setProjectId(line.getProjectId());
                    priceDto.setLineId(line.getLineId());
                    priceDto.setPrice(line.getAgentPrice());
                    priceDto.setCostPrice(line.getCostPrice());
                    return priceDto;
                }).collect(Collectors.toList());

                subUserDto.setProjectPrices(priceInfoList);
                resultList.add(subUserDto);
            }

            // 8. 创建新的分页结果对象，返回处理后的数据
            IPage<SubUserProjectPriceDTO> finalPageResult = new Page<>(pageNum, pageSize, pagedResult.getTotal());
            finalPageResult.setRecords(resultList);

            return Result.success("查询成功", finalPageResult);
        } catch (Exception e) {
            log.error("分页并筛选用户项目配置失败", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，查询配置失败");
        }
    }

    /**
     * 编辑或更新指定用户的项目价格配置
     * @param subUserProjectPriceDTO 包含用户ID和该用户全新的项目价格配置列表
     * @return 返回操作结果
     */
    @PostMapping("/user-project-prices/update")
    public Result<?> updateUserProjectPrices(@RequestBody SubUserProjectPriceDTO subUserProjectPriceDTO) {
        try {
            // 调用包含事务处理的 Service 方法
            boolean success = userProjectLineService.updateUserProjectLines(subUserProjectPriceDTO,0L);
            return success ? Result.success("更新成功") : Result.error("更新失败或数据无变化");
        } catch (BusinessException e) {
            log.warn("编辑用户项目配置业务校验失败: {}", e.getMessage());
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("编辑用户项目配置时发生系统内部错误", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，更新失败");
        }
    }

    /**
     * 更新用户项目价格配置表
     */
    @PostMapping("/userProjectLine/update")
    public Result<?> updateByUserProjectLine(@RequestBody ProjectPriceInfoDTO projectPriceInfoDTO) {
        log.info("updateByUserProjectLine: {}", projectPriceInfoDTO);
        try {
            if (projectPriceInfoDTO.getUserProjectLineTableId() == null) {
                return Result.error("更新失败：缺少必要的配置ID");
            }
            UserProjectLine userProjectLineToUpdate = new UserProjectLine();
            BeanUtils.copyProperties(projectPriceInfoDTO, userProjectLineToUpdate);

            // 手动设置不一致或需要特殊处理的属性
            userProjectLineToUpdate.setId(projectPriceInfoDTO.getUserProjectLineTableId());
             userProjectLineToUpdate.setAgentPrice(projectPriceInfoDTO.getPrice());

            // 3. 调用 Service 层进行更新
            boolean isSuccess = userProjectLineService.updateUserProjectLinesById(userProjectLineToUpdate);

            // 4. 返回结果
            if (isSuccess) {
                return Result.success("更新成功");
            } else {
                return Result.error("更新失败");
            }
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("更新用户项目价格配置失败", e);
            return Result.error("系统内部错误，更新失败");
        }
    }

    /**
     * 根据用户id分页查询账本记录
     */
    @GetMapping("/get/user-id/leader/")
    public Result<?> getUserProjectLineById(@RequestParam(defaultValue = "1") long pageNum,
                                            @RequestParam(defaultValue = "10") long pageSize,
                                            @RequestParam(required = false) Long userId) {
        return Result.error("旧金额账本接口已下线，请使用项目线路配额账本接口");
    }

    /**
     * 数据报表
     * 管理员端
     * @param queryDTO 查询参数，包含分页和筛选条件
     */
    @PostMapping("/get/data")
    public Result<?> getData(@RequestBody StatisticsQueryDTO queryDTO){ // 使用 DTO 接收参数
        try{
            // 调用 service，传入分页和筛选条件，注意这里我们假设管理员ID仍然是 0L
            IPage<ProjectStatisticsDTO> reportPage = numberRecordService.getStatisticsReport(0L, queryDTO);
            return Result.success(reportPage);
        }catch (BusinessException e) {
            return Result.error(e.getMessage());
        }
    }




    /**
     * 创建价格模板
     * @param createDTO 模板信息
     * @return 操作结果
     */
    @PostMapping("/price-templates")
    public Result<?> createPriceTemplate(@RequestBody PriceTemplateCreateDTO createDTO) {
        try {
            boolean success = priceTemplateService.saveOrUpdateTemplate(createDTO,0L);
            return success ? Result.success("创建成功") : Result.error("创建失败");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("创建价格模板失败", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，创建失败");
        }
    }

    /**
     * 获取所有价格模板
     * @return 模板列表
     */
    @GetMapping("/price-templates")
    public Result<List<PriceTemplateResponseDTO>> getAllPriceTemplates() {
        try {
            List<PriceTemplateResponseDTO> templates = priceTemplateService.listTemplatesByCreator(-1L);
            return Result.success("查询成功", templates);
        } catch (Exception e) {
            log.error("查询价格模板失败", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，查询失败");
        }
    }

    /**
     * 更新价格模板
     * @param templateId 模板ID
     * @param updateDTO 更新后的模板信息
     * @return 操作结果
     */
    @PostMapping("/price-templates/{templateId}")
    public Result<?> updatePriceTemplate(@PathVariable Long templateId, @RequestBody PriceTemplateCreateDTO updateDTO) {
        try {
            boolean success = priceTemplateService.updateTemplate(templateId, updateDTO,0L);
            return success ? Result.success("更新成功") : Result.error("更新失败");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("更新价格模板失败", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，更新失败");
        }
    }

    /**
     * 删除价格模板
     * @param templateId 模板ID
     * @return 操作结果
     */
    @GetMapping("/price-templates/{templateId}")
    public Result<?> deletePriceTemplate(@PathVariable Long templateId) {
        try {
            boolean success = priceTemplateService.deleteTemplate(templateId,0L);
            return success ? Result.success("删除成功") : Result.error("删除失败");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("删除价格模板失败", e);
            return Result.error(Constants.ERROR_SYSTEM_ERROR, "系统错误，删除失败");
        }
    }

    /**
     * 管理员根据下级用户ID获取其项目价格配置列表
     * @param userId 下级用户的ID
     * @return 该用户的项目价格配置列表
     */
//    @SaCheckLogin
    @GetMapping("/user/project-prices/get")
    public Result<?> getSubUserProjectPricesByUserId(@RequestParam @NotNull Long userId) {
        long agentId = -1;
        try {
//            agentId = StpUtil.getLoginIdAsLong();
            // 1. 校验用户是否存在
            User targetUser = userService.getById(userId);
            if (targetUser == null) {
                return Result.error("用户不存在");
            }
            // 2. 权限校验：超级管理员
//            if (!Objects.equals(0L, agentId)) {
//                return Result.error("无权查看该用户的项目配置");
//            }
            // 3. 查询数据
            List<UserProjectLine> userProjectLines = userProjectLineService.getLinesByUserId(userId);
            if (userProjectLines == null || userProjectLines.isEmpty()) {
                return Result.success("查询成功，该用户暂无项目配置", Collections.emptyList());
            }
            return Result.success("查询成功", userProjectLines);
        } catch (BusinessException e) {
            log.warn("代理 [{}] 查询下级 [{}] 项目配置失败: {}", agentId, userId, e.getMessage());
            return Result.error(e.getMessage());
        }
    }
    @Autowired private SmsApiService smsApiService;
    /**
     *
     * @return 操作结果
     */
    @PostMapping("/project/Balance")
    public Result<?> projectBalance(@RequestBody PublicDTO updateDTO) {
        if (updateDTO.getProjectId() == null || updateDTO.getLineId() == null) {
            return Result.error("查询参数不能为空");
        }
        try {
            Project project = projectService.getProject(updateDTO.getProjectId(), updateDTO.getLineId());
            if (project == null) {
                return Result.error("项目不存在");
            }
            String blance = smsApiService.getApiBalance(project, null);
            if (blance == null) {
                return Result.error("余额为空");
            }
            return Result.success(blance);
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        }
    }
    /**
     * 管理员-获取用户线路统计数据
     * 统计维度：用户 + 项目 + 线路
     */
    @PostMapping("/stats/user-line")
    public Result<?> getUserLineStats(@RequestBody UserLineStatsRequestDTO requestDTO) {
        try {
            // 管理员查询所有，agentId 传 null
            IPage<UserLineStatsDTO> stats = numberRecordService.getUserLineStats(requestDTO, null);
            return Result.success("查询成功", stats);
        } catch (Exception e) {
            log.error("获取用户线路统计失败", e);
            return Result.error("获取统计数据失败");
        }
    }

    /**
     * 手动触发项目登录
     * @param projectId 项目ID
     * @param lineId 线路ID (可选，通过 @RequestParam 传参)
     */
    @PostMapping("/project/login")
    public Result<?> manualLogin(@RequestParam String projectId,
                                               @RequestParam String lineId) {
        try {
            String token = smsApiService.manualLogin(projectId, lineId);
            return Result.success(token, "登录成功");
        } catch (Exception e) {
            return Result.error("登录失败: " + e.getMessage());
        }
    }

    /**
     * 获取所有可用模板 (用于创建用户时的下拉框)
     */
    @GetMapping("/template/list")
    public Result<?> listTemplates() {
        try{
            LambdaQueryWrapper<PriceTemplate> wrapper = new LambdaQueryWrapper<>();
            return Result.success(priceTemplateService.list(wrapper));
        }catch (BusinessException e){
            return Result.error(e.getMessage());
        }
    }

    /**
     * 获取用户的配置信息
     * 返回：关联的模板ID (templateId) 和 项目黑名单 (blacklist)
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
            result.put("templateId", user.getTemplateId());
            result.put("blacklist", user.getProjectBlacklist());
            if (user.getTemplateId() != null) {
                PriceTemplate template = priceTemplateService.getById(user.getTemplateId());
                if (template != null) {
                    result.put("templateName", template.getName());
                }
            }
            return Result.success("查询成功", result);
        } catch (Exception e) {
            log.error("获取用户配置信息失败: userId={}", userId, e);
            return Result.error("系统错误，获取配置失败");
        }
    }

    /**
     * 获取指定价格模板的详细配置项
     * @param templateId 模板ID
     */
    @GetMapping("/price-templates/{templateId}/items")
    public Result<?> getTemplateItems(@PathVariable Long templateId) {
        try {
            List<PriceTemplateItem> items = priceTemplateService.getTemplateItems(templateId);
            return Result.success(items);
        } catch (Exception e) {
            log.error("获取模板详情失败", e);
            return Result.error("获取模板详情失败");
        }
    }

    /**
     * 批量资金操作（充值/扣款）
     * 支持管理员给用户批量充值/扣款
     */
    @PostMapping("/batch/fund/operate")
    public Result<?> batchFundOperate(@RequestBody BatchChargeRequestDTO batchDTO) {
        try {
            Long operatorId = 0L;
            int count = userService.batchFundOperation(batchDTO, operatorId);
            return Result.success("批量操作成功，共处理 " + count + " 个用户");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("批量资金操作失败", e);
            return Result.error("系统内部错误，操作失败");
        }
    }
    /**
     * 根据时间段批量退款（针对已扣费但状态为待取码的记录）
     */
    @PostMapping("/batch/refund/by-date")
    public Result<?> batchRefundByDate(@RequestBody BatchRefundQueryDTO queryDTO) {
        try {
            int count = numberRecordService.batchRefundByQuery(queryDTO);
            return Result.success("批量退款完成，共处理 " + count + " 条记录");
        } catch (BusinessException e) {
            return Result.error(e.getMessage());
        } catch (Exception e) {
            log.error("批量退款失败", e);
            return Result.error("系统错误");
        }
    }

    /**
     * 管理员：物理清理账本记录
     *
     * @param days         保留天数（如传30，则删除30天前的记录）
     * @param targetUserId 目标用户ID（可选。如果不传，则清理全系统所有人的记录）
     */
    @SaCheckLogin // 确保已登录管理员
    @PostMapping("/ledger/clear-physical")
    public Result<?> clearLedgerPhysical(
            @RequestParam @NotNull Integer days,
            @RequestParam(required = false) Long targetUserId) {
        return Result.error("旧金额账本清理接口已下线，无需执行");
    }

    /**
     * 管理员：物理清理号码记录
     *
     * @param days         保留天数
     * @param targetUserId 目标用户ID（可选）
     */
    @SaCheckLogin
    @PostMapping("/number/clear-physical")
    public Result<?> clearNumberRecordPhysical(
            @RequestParam @NotNull Integer days,
            @RequestParam(required = false) Long targetUserId) {
        if (days < 0) {
            return Result.error("天数不能为负数");
        }
        try {
            Long adminId = Long.valueOf(StpUtil.getLoginId().toString());
            numberRecordService.deleteNumberRecordByDays(adminId, targetUserId, days, true);
            String scope = (targetUserId == null) ? "全系统" : "用户ID:" + targetUserId;
            log.warn("【系统维护】管理员 {} 执行了号码记录物理清理，范围: {}, 保留天数: {}", adminId, scope, days);

            return Result.success("清理成功", "已清理 " + scope + " " + days + " 天前的非进行中记录");
        } catch (Exception e) {
            log.error("管理员清理号码记录失败", e);
            return Result.error("清理失败：" + e.getMessage());
        }
    }

}
