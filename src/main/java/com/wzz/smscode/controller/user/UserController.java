package com.wzz.smscode.controller.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.common.Constants;
import com.wzz.smscode.common.Result;
import com.wzz.smscode.dto.*;
import com.wzz.smscode.dto.EntityDTO.UserDTO;
import com.wzz.smscode.dto.LoginDTO.UserLoginDto;
import com.wzz.smscode.dto.ResultDTO.UserResultDTO;
import com.wzz.smscode.dto.number.NumberDTO;
import com.wzz.smscode.dto.project.SelectProjectDTO;
import com.wzz.smscode.dto.quota.UserQuotaItemDTO;
import com.wzz.smscode.dto.quota.UserQuotaLedgerItemDTO;
import com.wzz.smscode.dto.update.UserUpdatePasswardDTO;
import com.wzz.smscode.entity.*;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.mapper.UserProjectQuotaLedgerMapper;
import com.wzz.smscode.moduleService.PhoneNumberFilterService;
import com.wzz.smscode.service.*;
import com.wzz.smscode.service.impl.UserServiceImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户接口控制器
 * 该接口 GET/POST 自适应
 * <p>
 * 面向普通用户，提供取号、取码、查询信息等核心功能。
 * 所有接口均需要提供 userId 和 password 进行身份验证。
 * @author qiexi
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LogManager.getLogger(UserController.class);
    @Autowired
    private NumberRecordService numberRecordService;

    @Autowired
    private UserService userService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private SystemConfigService systemConfigService;
    /**
     * 获取手机号码
     *
     * @param userName    用户姓名
     * @param password  用户密码
     * @param projectId 项目ID
     * @param lineId    线路ID
     * @return CommonResultDTO，成功时 data 为手机号字符串
     */
    @RequestMapping(value = "/getNumber", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<String> getNumber(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam String projectId,
             @RequestParam String lineId) {
        return numberRecordService.getNumber(userName, password, projectId, lineId);
    }

    /**
     * 获取验证码
     *
     * @param userName      用户名称
     * @param password    用户密码
     * @param phoneNumber 获取到的手机号码
     * @return CommonResultDTO，成功时 data 为验证码字符串
     */
    @RequestMapping(value = "/getCode", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<String> getCode(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam String phoneNumber,
            @RequestParam String projectId,
            @RequestParam String lineId) {

        return numberRecordService.getCode(userName, password, phoneNumber,projectId,lineId);
    }

    /**
     * 释放手机号
     * 逻辑：调用第三方释放接口
     */
    @RequestMapping(value = "/releaseNumber", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<String> releaseNumber(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam String phoneNumber,
            @RequestParam String projectId,
            @RequestParam String lineId,
            @RequestParam String isSuccess) {

        // 1. 参数校验
        if (!Arrays.asList("0", "1").contains(isSuccess)) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "isSuccess 只能传 0 或 1");
        }

        // 2. 转换布尔
        boolean needRelease = "1".equals(isSuccess);

        try {
            // 3. 调用服务
            return numberRecordService.releasePhoneNumber(userName, password, phoneNumber,
                    projectId, lineId, needRelease);
        } catch (BusinessException e) {
            // 4. 业务异常
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, e.getMessage());
        } catch (Exception e) {
            // 5.  Unexpected 异常记录日志
            log.error("releaseNumber unexpected error", e);
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "系统繁忙，请稍后重试");
        }
    }

    /**
     * 更新密码
     */
    @PostMapping("/update/passward")
    public Result<?> updateByUserIdToPassWard(@RequestBody UserUpdatePasswardDTO updateUserDto ){
        try{
            Boolean is_ok = userService.updatePassWardByUserName(updateUserDto);
            if (is_ok){
                return Result.success("更新成功");
            }
            return Result.error("更新失败！");
        }catch (BusinessException e){
            return Result.error(e.getMessage());
        }
    }

    /**
     * 获取公告接口
     */
    @GetMapping("/notice")
    public Result<?> getUserNotice(){
        SystemConfig config = systemConfigService.getConfig();
        return Result.success(config.getSystemNotice());
    }



    /**
     * 查询账户余额
     *
     * @param userName   用户名称
     * @param password 用户密码
     * @return CommonResultDTO，成功时 data 为余额
     */
    @RequestMapping(value = "/getBalance", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<Long> getBalance(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam String projectId,
            @RequestParam String lineId) {
        return userService.getBalance(userName, password, projectId, lineId);
    }

    /**
     * 用户登录并获取详细信息
     *
     * @param userName   用户ID
     * @param password 用户密码
     * @return CommonResultDTO，成功时 data 为 UserDTO
     */
    @RequestMapping(value = "/info", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<UserDTO> getUserInfo(
            @RequestParam String userName,
            @RequestParam String password) {
        User user = userService.authenticateUserByUserName(userName, password,false);
        if (user == null) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
        }
        if (user.getStatus()==1){
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED,"该用户已被禁用");
        }
        // 调用在 UserServiceImpl 中定义的 DTO 转换方法
        UserDTO userDTO = ((UserServiceImpl) userService).convertToDTO(user);
        return CommonResultDTO.success("登录成功", userDTO);
    }

    @Autowired
    private UserProjectLineService userProjectLineService;

    @Autowired
    private UserProjectQuotaService userProjectQuotaService;

    @Autowired
    private UserProjectQuotaLedgerMapper userProjectQuotaLedgerMapper;

    /**
     * 旧账本接口已下线
     */
    @RequestMapping(value = "/ledger/list", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<?> listLedger(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "旧金额账本接口已下线，请使用配额流水接口");
    }

    /**
     * 查询用户项目线路配额列表
     *
     * @param userName 用户名
     * @param password 密码
     * @param projectId 项目ID（可选）
     * @param lineId 线路ID（可选）
     */
    @RequestMapping(value = "/quotas", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<List<UserQuotaItemDTO>> listUserQuotas(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String lineId) {
        try {
            User user = userService.authenticateUserByUserName(userName, password, false);
            if (user == null) {
                return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
            }
            List<UserQuotaItemDTO> data = buildUserQuotaItems(user.getId(), user.getUserName(), projectId, lineId);
            return CommonResultDTO.success("查询成功", data);
        } catch (BusinessException e) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, e.getMessage());
        } catch (Exception e) {
            log.error("用户查询配额列表失败，userName={}", userName, e);
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "查询配额列表失败");
        }
    }

    /**
     * 查询用户项目线路配额流水
     *
     * @param userName 用户名
     * @param password 密码
     * @param projectId 项目ID（可选）
     * @param lineId 线路ID（可选）
     * @param ledgerType 流水类型（可选，1-入账，0-出账）
     * @param startTime 开始时间（可选）
     * @param endTime 结束时间（可选）
     * @param page 页码
     * @param size 每页条数
     */
    @RequestMapping(value = "/quota-ledgers", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<Page<UserQuotaLedgerItemDTO>> listUserQuotaLedgers(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String lineId,
            @RequestParam(required = false) Integer ledgerType,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        try {
            User user = userService.authenticateUserByUserName(userName, password, false);
            if (user == null) {
                return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
            }
            Page<UserQuotaLedgerItemDTO> result = pageUserQuotaLedgers(
                    user.getId(),
                    user.getUserName(),
                    projectId,
                    lineId,
                    ledgerType,
                    startTime,
                    endTime,
                    page,
                    size
            );
            return CommonResultDTO.success("查询成功", result);
        } catch (BusinessException e) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, e.getMessage());
        } catch (Exception e) {
            log.error("用户查询配额流水失败，userName={}", userName, e);
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "查询配额流水失败");
        }
    }

    /**
     * 查询号码获取记录
     *
     * @param userName    用户名称
     * @param password  用户密码
     * @param status    状态过滤 (可选)
     * @param startTime 开始时间 (可选, 格式: yyyy-MM-dd HH:mm:ss)
     * @param endTime   结束时间 (可选, 格式: yyyy-MM-dd HH:mm:ss)
     * @param page      当前页码 (默认 1)
     * @param size      每页数量 (默认 10)
     * @return CommonResultDTO，data 为分页后的 NumberDTO 列表
     */
    @RequestMapping(value = "/listNumbers", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<IPage<NumberDTO>> listNumbers(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date startTime,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") Date endTime,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {

        IPage<NumberRecord> pageRequest = new Page<>(page, size);
        IPage<NumberDTO> resultPage = numberRecordService.listUserNumbersByUSerName(userName, password, status, startTime, endTime, pageRequest);

        if (resultPage == null) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户名或密码错误");
        }
        return CommonResultDTO.success("查询成功", resultPage);
    }

    /**
     * 查询用户项目列表
     *
     * @param userName    用户名称
     * @param password  用户密码
     * @return CommonResultDTO，data 为该用户有权访问的项目列表
     */
    @RequestMapping(value = "/by-user/listProjects", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<?> listProjects(
            @RequestParam String userName,
            @RequestParam String password) {
        // 1. 身份验证
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
        }
        // 2. 仅返回当前用户“有剩余配额”的项目，且确保 projectId 字段可用。
        List<SelectProjectDTO> projects = projectService.listUserProjects(user.getId());
        return CommonResultDTO.success("查询成功", projects);
    }

    /**
     * 查询某项目的可用线路列表
     *
     * @param userName    用户ID
     * @param password  用户密码
     * @param projectId 项目ID
     * @return CommonResultDTO，data 为可用的线路列表，每个元素包含 lineId 和 lineName
     */
    @RequestMapping(value = "/listProjectLines", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<?> listProjectLines(
            @RequestParam String userName,
            @RequestParam String password,
            @RequestParam String projectId) {
        // 1. 权限校验
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
        }

        // 2. 调用新的 service 方法，获取包含 id 和 name 的 Map 列表
        List<Map<String, Object>> lines = projectService.listLinesWithCamelCaseKeyFor(user.getId(),projectId);

        // 3. 将查询结果返回
        return CommonResultDTO.success("查询成功", lines);
    }

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public CommonResultDTO<?> userLogin(@RequestBody UserLoginDto userLoginDto){
        Boolean is_true = userService.login(userLoginDto);
        if (is_true){
            return CommonResultDTO.success("用户登录成功");
        }
        else {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED,"用户认证失败！");
        }
    }
    /**
     * 用户注册（前端不需要使用）
     */
    @PostMapping("/regist")
    public CommonResultDTO<?> userRegist(@RequestBody UserResultDTO userDTO){
        Boolean is_true = userService.regist(userDTO);
        if (is_true){
            return CommonResultDTO.success("用户注册成功");
        }else {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED,"用户注册失败！");
        }
    }


    private final RestTemplate restTemplate = new RestTemplate();
    /**
     * 反向代理
     * 传入地址，我来请求，返回响应
     */
    @PostMapping("/request/url")
    public String requestUrl(@RequestBody RequestUrlDTO requestUrlDTO) {
        String  urlStr = requestUrlDTO.getData();
        if (urlStr == null || (urlStr = urlStr.trim()).isEmpty()) {
            return "请求的URL不能为空";
        }
        if (urlStr.startsWith("\"") && urlStr.endsWith("\"")) {
            urlStr = urlStr.substring(1, urlStr.length() - 1);
        }
        try {
            String responseBody = restTemplate.getForObject(urlStr, String.class);
            return responseBody;
        } catch (Exception e) {
            return "代理请求失败: " + e.getMessage();
        }
    }

    @Autowired
    private PhoneNumberFilterService phoneNumberFilterService;
    /**
     * 直接查询手机号码状态 (原始数据)
     * <p>
     * 该接口不依赖于项目配置，直接调用底层的号码筛选服务。
     * 适用于需要获取号码原始状态（如 "新号", "封禁" 等）的场景。
     */
    @PostMapping("/checkPhoneNumberState")
    public CommonResultDTO<String> checkPhoneNumberState(@RequestBody RequestUrlDTO requestUrlDTO) {
        // 1. 身份验证
//        User user = userService.authenticateUserByUserName(requestUrlDTO.getUserName(), requestUrlDTO.getPassword());
//        if (user == null) {
//            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户ID或密码错误");
//        }

        // 2. 获取系统配置中的全局API Token
//        SystemConfig systemConfig = systemConfigService.getConfig();
//        String token = systemConfig.getFilterApiKey();
//        if (!StringUtils.hasText(token)) {
//            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR,"系统配置错误：未找到号码筛选服务的API Token");
//        }

        try {
            String state = phoneNumberFilterService.checkPhoneNumberState(requestUrlDTO.getToken(), requestUrlDTO.getCpid(), requestUrlDTO.getPhone(), "86")
                    .block(); // 阻塞等待异步操作完成
            if (state != null) {
                return CommonResultDTO.success("查询成功", state);
            } else {
                return CommonResultDTO.error(Constants.ERROR_NO_CODE,"检测失败");
            }
        } catch (BusinessException e) {
            log.info(e.getMessage());
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR,"检测失败");
        }
    }

    /**
     * 旧账本清理接口已下线
     */
    @RequestMapping(value = "/ledger/clear", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<String> clearLedger(
            @RequestParam String userName,
            @RequestParam String password) {
        return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "旧金额账本清理接口已下线");
    }

    /**
     * 清理历史号码获取记录
     *
     * @param userName 用户名
     * @param password 密码
     */
    @RequestMapping(value = "/number/clear", method = {RequestMethod.GET, RequestMethod.POST})
    public CommonResultDTO<String> clearNumberRecords(
            @RequestParam String userName,
            @RequestParam String password) {

        SystemConfig systemConfig = systemConfigService.getConfig();

        Integer days = Integer.valueOf(systemConfig.getUserDeleteDataDay());

        // 1. 验证天数参数
        if (days == null || days <= 0) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "天数参数错误");
        }

        // 2. 身份验证
        User user = userService.authenticateUserByUserName(userName, password);
        if (user == null) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "用户名密码错误！");
        }

        if (user.getStatus() == 1) {
            return CommonResultDTO.error(Constants.ERROR_AUTH_FAILED, "该用户已被禁用");
        }

        try {
            // 3. 执行删除逻辑
            numberRecordService.deleteNumberRecordByDays(user.getId(), user.getId(), days, false);

            log.info("用户 {} 清理了 {} 天前的号码记录", userName, days);
            return CommonResultDTO.success("号码记录清理成功", "已清理 " + days + " 天前的非进行中记录");
        } catch (BusinessException e) {
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, e.getMessage());
        } catch (Exception e) {
            log.error("清理号码记录异常: ", e);
            return CommonResultDTO.error(Constants.ERROR_SYSTEM_ERROR, "系统繁忙");
        }
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
            quotaMap.put(
                    quota.getProjectId() + "_" + quota.getLineId(),
                    quota.getAvailableCount() == null ? 0L : quota.getAvailableCount()
            );
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
            Long userId,
            String userName,
            String projectId,
            String lineId,
            Integer ledgerType,
            LocalDateTime startTime,
            LocalDateTime endTime,
            long page,
            long size) {
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
                    : operatorNameMap.getOrDefault(entity.getOperatorId(), "系统"));
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
