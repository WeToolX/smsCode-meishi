package com.wzz.smscode.moduleService;

import com.wzz.smscode.common.CommonResultDTO;
import com.wzz.smscode.dto.ApiConfig.ApiConfig;
import com.wzz.smscode.entity.Project;
import com.wzz.smscode.entity.SystemConfig;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.service.ProjectService;
import com.wzz.smscode.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.jayway.jsonpath.JsonPath;


import org.bouncycastle.jce.provider.BouncyCastleProvider;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.Security;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.annotation.JsonInclude;

@Service
@RequiredArgsConstructor
@Slf4j
public class SmsApiService {
    private final WebClient webClient; // 从WebClientConfig注入
    private final SystemConfigService systemConfigService;
    private final ModuleUtil moduleUtil;
    // 变量替换正则：匹配 {{variable}}
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");

    /**
     * 变量替换工具
     * 将字符串中的 {{key}} 替换为 context 中的 value
     */
    private String replaceVariables(String input, Map<String, String> context) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim(); // 获取 {{ key }} 中的 key
            String value = context.getOrDefault(key, ""); // 如果找不到，替换为空字符串，或者保留原样视需求而定
            // 对 value 进行转义，防止 value 中包含 $ 导致报错
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 第一步：获取手机号
     * 流程：
     * 1. 检查是否需要登录（如果有Token且未过期可跳过，这里简化为每次检查或由配置决定）
     * 2. 执行获取手机号请求
     * 3. 返回包含手机号、ID等信息的 Map
     */
    public Map<String, String> getPhoneNumber(Project project, String... dynamicParams) {
        log.info("开始为项目 [{} - {}] 获取手机号", project.getProjectName(), project.getLineId());

        if (Boolean.TRUE.equals(project.getAesSpecialApiStatus())) {
            log.info("执行 AES 加密特殊接口获取手机号...");
            return getPhoneNumberAesSpecial(project);
        }

        if (Boolean.TRUE.equals(project.getSpecialApiStatus())) {
            log.info("开始为特殊api项目 [{} - {}] 执行获取手机号的特殊接口", project.getProjectName(), project.getLineId());
            return getPhoneNumberSpecial(project);
        }

        // 1. 初始化上下文变量 (Context)
        Map<String, String> context =null;
        try {
            context =moduleUtil.getApiToken(project, dynamicParams);
        }catch (Exception e) {
            log.error("项目 [{} - {}] 获取Token出现错误, 错误：{}", project.getProjectName(), project.getLineId(), e);
            throw new BusinessException("接口登录失败，无法执行后续操作");
        }

        // 3. 执行获取手机号接口
        if (project.getGetNumberConfig() == null) {
            throw new BusinessException("项目未配置获取手机号接口");
        }
        // 执行请求，executeApi 会将提取到的变量放入 context
        moduleUtil.executeApi(project.getGetNumberConfig(), context);
        // 4. 检查结果
        // 前端配置提取规则时，必须将手机号提取为 "phone"，ID提取为 "id" (约定优于配置)
        if (!context.containsKey("phone")) {
            log.error("获取手机号接口执行成功，但未提取到 phone 变量。当前Context: {}", context);
            throw new BusinessException("未获取到手机号，请尝试重新获取");
        }

        return context;
    }


    /**
     * 获取api的余额
     */
    public String getApiBalance(Project project, String... dynamicParams){
        // 1. 初始化上下文变量 (Context)
        Map<String, String> context =null;

        if (Boolean.TRUE.equals(project.getSpecialApiStatus())) {
            return getApiBalanceSpecial(project);
        }
        if (Boolean.TRUE.equals(project.getAesSpecialApiStatus())) {
            return getApiBalanceAesSpecial(project);
        }
        try {
            context =moduleUtil.getApiToken(project, dynamicParams);
        }catch (Exception e) {
            log.error("项目 [{} - {}] 获取Token出现错误, 错误：{}", project.getProjectName(), project.getLineId(), e);
            throw new BusinessException("接口登录失败，无法执行后续操作");
        }
        // 3. 执行获取手机号接口
        if (project.getGetBalanceConfig() == null) {
            throw new BusinessException("项目未配置获取余额接口");
        }
        // 执行请求，executeApi 会将提取到的变量放入 context
        moduleUtil.executeApi(project.getGetBalanceConfig(), context);
        if (!context.containsKey("balance")) {
            log.error("获取余额接口执行成功，但未提取到 balance 变量。当前Context: {}", context);
            throw new BusinessException("未获取到余额，请检查提取规则配置");
        }
        return context.get("balance");
    }

    /**
     * 第二步：获取验证码
     * @param project 项目配置
     * @param identifierParams 上一步获取到的所有变量 (包含 phone, id, token 等)
     */

    public String getVerificationCode(Project project, Map<String, String> identifierParams , Supplier<Boolean> stopCondition) {

        if (Boolean.TRUE.equals(project.getAesSpecialApiStatus())) {
            log.info("检测到开启 AES 特殊 API，进入 AES 轮询流程...");
            return pollForAesVerificationCode(project, identifierParams, stopCondition);
        }
        if (Boolean.TRUE.equals(project.getSpecialApiStatus())) {
            log.info("检测到开启  特殊 API，进入流程...");
            return getVerificationCodeSpecial(project, identifierParams, false);
        }
        log.info("开始获取验证码，参数: {}", identifierParams);

        // 1. 准备上下文
        Map<String, String> context = new HashMap<>(identifierParams);
        // 确保 Token 存在
        if (!context.containsKey("token") && StringUtils.hasText(project.getAuthTokenValue())) {
            context.put("token", project.getAuthTokenValue());
        }
        ApiConfig config = project.getGetCodeConfig();
        if (config == null) {
            throw new BusinessException("项目未配置获取验证码接口");
        }

        // 2. 轮询逻辑 (10分钟)
        long startTime = System.currentTimeMillis();
        long timeout = 5 * 60 * 1000L;
        int attempts = 0;

        // 只要当前时间减去开始时间小于超时时间，就继续轮询
        while (System.currentTimeMillis() - startTime < timeout) {
            if (stopCondition != null && stopCondition.get()) {
                log.info("检测到外部终止条件（如：已通过其他途径获码或订单已关闭），停止轮询。");
                throw new BusinessException("已获取验证码码或订单已关闭，请前往号码记录中查询");
            }
            attempts++;
            try {
                moduleUtil.executeApi(config, context);
                String code = context.get("code"); // 约定提取变量名为 code
                if (StringUtils.hasText(code) && !"null".equalsIgnoreCase(code.trim())) {
                    if (code != null && code.matches("^\\d{4,8}$")) {
                        log.info("成功获取验证码: {}, 耗时: {}ms", code, System.currentTimeMillis() - startTime);
                        return code;
                    }
                }
                log.info("未获取到验证码，第 {} 次尝试，已耗时 {}ms...", attempts, System.currentTimeMillis() - startTime);
                Thread.sleep(1000); // 3秒轮询一次
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("获取验证码被中断");
            } catch (Exception e) {
                log.warn("轮询中发生异常: {}", e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        long totalTime = System.currentTimeMillis() - startTime;
        log.error("获取验证码超时，总耗时: {}ms, 总尝试次数: {}", totalTime, attempts);
        throw new BusinessException("获取验证码超时(5分钟未获取到)");
    }


    /**
     * 第三步：释放手机号
     * @param project 项目配置
     * @param identifierParams 包含 phone 和 id 的上下文参数
     */
    public boolean releasePhoneNumber(Project project, Map<String, String> identifierParams,boolean isSuccess) {
        String phone = identifierParams.get("phone");
        String id = identifierParams.get("id");
        log.info("开始释放项目 [{}] 的手机号: {}, 关联ID: {}", project.getProjectName(), phone, id);
        if (Boolean.TRUE.equals(project.getAesSpecialApiStatus())) {
            log.info("执行 AES 加密特殊接口释放手机号...");
            return releasePhoneNumberAesSpecial(project, id,isSuccess);
        }
        if (project.getDeletePhoneConfig() == null || project.getDeletePhoneConfig().getUrl() == null) {
            log.warn("项目 [{}] 未配置释放手机号接口，跳过执行。", project.getProjectName());
            return false;
        }
        try {
            Map<String, String> context = new HashMap<>(identifierParams);
            // --- 核心改动：从项目配置中读取自定义的值 ---
            String statusValue = isSuccess
                    ? (StringUtils.hasText(project.getReleaseSuccessStatus()) ? project.getReleaseSuccessStatus() : "1")
                    : (StringUtils.hasText(project.getReleaseFailStatus()) ? project.getReleaseFailStatus() : "0");
            String msgValue = isSuccess
                    ? (StringUtils.hasText(project.getReleaseSuccessMsg()) ? project.getReleaseSuccessMsg() : "success")
                    : (StringUtils.hasText(project.getReleaseFailMsg()) ? project.getReleaseFailMsg() : "fail");
            // 将自定义的值放入 context，用于替换 ApiRequestEditor 里的 {{releaseStatus}} 和 {{releaseMsg}}
            context.put("releaseStatus", statusValue);
            context.put("releaseMsg", msgValue);
            // 注入 Token
            Map<String, String> tokenContext = moduleUtil.getApiToken(project);
            context.putAll(tokenContext);
            // 执行请求
            moduleUtil.executeApi(project.getDeletePhoneConfig(), context);
            log.info("释放接口调用完成，状态值: {}, 消息内容: {}", statusValue, msgValue);
            //todo 目前不使用通用的释放接口 默认直接返回fasle
            return false;
        } catch (Exception e) {
            log.error("释放手机号接口执行异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     *单次尝试获取验证码 (无轮询)
     * 用于用户手动刷新或状态检查
     *
     * @param project 项目配置
     * @param identifierParams 上下文参数
     * @return Optional<String>
     */
    public Optional<String> fetchVerificationCodeOnce(Project project, Map<String, String> identifierParams) {
         log.info("执行单次验证码获取, 参数: {}", identifierParams);

        if (Boolean.TRUE.equals(project.getAesSpecialApiStatus())) {
            String code = getVerificationCodeAesSpecial(project, identifierParams);
            return Optional.ofNullable(code);
        }

        Map<String, String> context = new HashMap<>(identifierParams);
        if (!context.containsKey("token") && StringUtils.hasText(project.getAuthTokenValue())) {
            context.put("token", project.getAuthTokenValue());
        }

        ApiConfig config = project.getGetCodeConfig();
        if (config == null) {
            log.warn("项目未配置获取验证码接口");
            return Optional.empty();
        }
        try {
            log.info("进入单次验证码获取，{}，{}",config,context);
            moduleUtil.executeApi(config, context);
            String code = context.get("code");
            if (StringUtils.hasText(code) && !"null".equalsIgnoreCase(code.trim())) {
                log.info("单次获取成功: {}", code);
                return Optional.of(code);
            }
        } catch (Exception e) {
            log.warn("单次获取验证码API执行异常: {}", e.getMessage());
        }
        return Optional.empty();
    }


    /**
     * 调用外部API检查手机号码是否可用。
     * 此实现现在完全依赖于系统配置中的筛选API信息，并增加了服务器轮询和动态参数构建。
     *
     * @param project     项目配置。
     * @param phoneNumber 需要检查的手机号码。
     * @param countryCode 国家区号，如果为null或空，则默认为 "86"。
     * @return 返回一个 Mono<Boolean>。如果号码可用，返回 true；否则返回 false。
     */
    public Mono<Boolean> checkPhoneNumberAvailability(Project project, String phoneNumber, String countryCode) {
        //获取系统配置并检查功能总开关
        SystemConfig systemConfig = systemConfigService.getConfig();
        if (project.getEnableFilter() == null || !project.getEnableFilter() || !systemConfig.getEnableNumberFiltering()) {
            log.info("系统配置：{},项目 [{}] 未开启号码筛选功能，默认号码 [{}] 可用。",systemConfig.getEnableNumberFiltering(), project.getProjectName(), phoneNumber);
            return Mono.just(true); // 功能未开启，默认可用
        }
        // 从系统配置中获取服务器地址列表
        List<String> servers = getServerList();
        if (servers.isEmpty()) {
            log.info("号码筛选功能已开启，但系统未配置任何有效的筛选服务器地址。号码 [{}] 将被视为不可用。", phoneNumber);
            return Mono.just(false);
        }
        String token = systemConfig.getFilterApiKey();
        String cpid = project.getSelectNumberApiRequestValue();
        if (!StringUtils.hasText(token) || !StringUtils.hasText(cpid)) {
            log.info("项目 [{}] 配置错误：缺少必要的API Token或CPID。", project.getProjectName());
            return Mono.error(new BusinessException("项目配置不完整，无法进行号码筛选"));
        }
        final String finalCountryCode = StringUtils.hasText(countryCode) ? countryCode : "86";
        //响应式服务器轮询与请求
        return Flux.fromIterable(servers)
                .concatMapDelayError(serverIp -> {
                    // 为当前服务器构建请求
                    URI requestUri = buildRequestUri(serverIp, phoneNumber, token, cpid, finalCountryCode);
                    log.info("正在尝试服务器 [{}] 筛选号码 [{}]...", serverIp, phoneNumber);
                    return webClient.get()
                            .uri(requestUri)
                            .retrieve()
                            .bodyToMono(String.class)
                            .timeout(Duration.ofSeconds(50)) // 根据建议设置45-60秒超时
                            .flatMap(responseBody -> {
                                log.info("服务器 [{}] 响应: {}", serverIp, responseBody);
                                boolean isAvailable = parseAvailabilityResponse(responseBody, cpid);
                                log.info("号码 [{}] 在服务器 [{}] 的筛选结果: {}", phoneNumber, serverIp, isAvailable ? "可用" : "不可用");
                                return Mono.just(isAvailable);
                            })
                            .doOnError(e -> log.warn("访问服务器 [{}] 失败: {}. 正在尝试下一个...", serverIp, e.getMessage()))
                            .onErrorResume(e -> Mono.empty()); // 如果当前服务器请求失败，返回空Mono，以便concatMap继续处理下一个服务器
                })
                .next() // 只取第一个成功的结果
                .defaultIfEmpty(false); // 如果所有服务器都尝试失败，则默认返回 false (不可用)
    }

    /**
     * 根据文档构建请求URI
     */
    private URI buildRequestUri(String serverIp, String phone, String token, String cpid, String cnty) {
        String baseUrl = serverIp.startsWith("http") ? serverIp : "http://" + serverIp;
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/check")
                .queryParam("phone", phone)
                .queryParam("token", token)
                .queryParam("cpid", cpid);
        if (StringUtils.hasText(cnty) && !"86".equals(cnty)) {
            builder.queryParam("cnty", cnty);
        }
        return builder.build().toUri();
    }


    /**
     * 解析API响应，判断号码是否可用。
     *
     * @param responseBody API返回的JSON字符串。
     * @param cpid         项目ID，用于特殊逻辑判断（如快手）。
     * @return 如果可用，返回 true，否则返回 false。
     */
    private boolean parseAvailabilityResponse(String responseBody, String cpid) {
        if (!StringUtils.hasText(responseBody)) {
            return false;
        }
        try {
            Map<String, Object> responseJson = JsonPath.parse(responseBody).read("$");
            Object codeObj = responseJson.get("code");
            if (!(codeObj instanceof Number) || ((Number) codeObj).intValue() != 0) {
                return false;
            }
            Object stateObj = responseJson.get("state");
            if (!(stateObj instanceof String)) {
                return false;
            }
            // 3. URL解码
            String encodedState = (String) stateObj;
            String state = URLDecoder.decode(encodedState, StandardCharsets.UTF_8.name());
            log.info("解码后的state值为: '{}'", state);
            // 定义不可用的关键词正则：包含 "封禁"、"老号"、"已绑"、"已注"、"异常" 任意一个即视为不可用
            // .* 表示匹配前后任意字符
            String unavailableRegex = ".*(封禁|老号|已绑|已注|异常).*";
            // 定义明确的新号正则
            String newAccountRegex = ".*(新号|未注).*";
            if ("ks".equalsIgnoreCase(cpid)) {
                if (state.matches(unavailableRegex)) {
                    log.info("检测到不良状态关键词，判定不可用: {}", state);
                    return false;
                }
                return true;
            }
            // 默认逻辑：必须明确包含 "新号" 或 "未注" 才是 true，其他情况（包括老号）均为 false
            return state.matches(newAccountRegex);

        } catch (Exception e) {
            log.error("解析API响应JSON失败，响应体: '{}'。错误: {}", responseBody, e.getMessage());
            return false;
        }
    }

    /**
     * 从系统配置中获取服务器列表
     * @return 服务器地址列表
     */
    private List<String> getServerList() {
        //后：根据系统配置中是否使用系统内置服务器列表-》否；在系统配置
        return Arrays.asList(
                "api.rqddjc.com:1030",
                "27.25.156.161:1030",
                "103.7.141.114:1030",
                "api.ddrqjc.com:1031",
                "103.7.141.111:1031",
                "nb.rqjiance.com:1032",
                "103.7.141.39:1032"
        );
    }


    private final ProjectService projectService;
    /**
     * 手动触发登录接口
     * @param projectId 项目ID
     * @param lineId 线路ID
     * @return 获取到的新Token
     */
    public String manualLogin(String projectId, String lineId) {
        // 1. 查询项目
        Project project = projectService.getProject(projectId,Integer.valueOf(lineId));
        if (project == null) {
            throw new BusinessException("未找到项目ID为 " + projectId + " 的项目");
        }
        if (lineId != null && !lineId.equals(project.getLineId())) {
            throw new BusinessException("项目ID与线路ID不匹配");
        }
        log.info("接收到手动登录请求：项目ID={}, 线路ID={}", projectId, lineId);
        return moduleUtil.executeLoginAndSave(project);
    }

    //--------------------特殊api----------------------------------

    // 使用 SimpleClientHttpRequestFactory (基于 JDK HttpURLConnection) 比 Netty 更抗造
    private final RestTemplate specialRestTemplate = new RestTemplate(new SimpleClientHttpRequestFactory() {{
        setConnectTimeout(60000); // 连接超时 10秒
        setReadTimeout(60000);    // 读取超时 60秒 (对应您之前设置的长时间等待)
    }});


    /**
     * 特殊API：获取手机号
     * URL格式: http://host:port/GETPHONE
     * 响应: 11位手机号 或 NO
     * 修改说明：使用 RestTemplate 替代 WebClient 以解决 PrematureCloseException
     */
    private Map<String, String> getPhoneNumberSpecial(Project project) {
        log.info("调用特殊API获取号码: {}", project.getProjectName());
        // 基础 URL
        String requestUrl = "http://154.86.26.17:51777/GETPHONE";

        try {
            // 1. 设置请求头：Connection: close 明确告知服务器不保持连接
            HttpHeaders headers = new HttpHeaders();
            headers.add("Connection", "Keep-Alive");
            headers.add("User-Agent", "Mozilla/5.0"); // 模拟浏览器
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 2. 发起请求 (阻塞式)
            ResponseEntity<String> response = specialRestTemplate.exchange(
                    requestUrl,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            String result = response.getBody();
            log.info("调用特殊API获取号码返回响应:{}", result);

            if (StringUtils.hasText(result)) {
                result = result.trim();
                // 排除 NO 和 长度不足的情况
                if ("NO".equalsIgnoreCase(result) || result.length() < 11) {
                    throw new BusinessException("特殊API无号返回: " + result);
                }
                Map<String, String> map = new HashMap<>();
                map.put("phone", result);
                // 这里为了兼容通用逻辑，建议把 id 也放进去，虽然特殊api没有id
                map.put("id", result);
                return map;
            }
        } catch (Exception e) {
            log.error("特殊API取号异常", e);
            throw new BusinessException("取号失败: " + e.getMessage());
        }
        throw new BusinessException("返回空");
    }

    /**
     * 特殊API：获取验证码
     * 逻辑：等待30s -> 请求一次 -> 成功返回码，失败返回null/抛异常
     * URL格式: http://host:port/GETCODE&phone&token
     * @param isManual 是否手动触发（手动触发不等待30s）
     */
    public String getVerificationCodeSpecial(Project project, Map<String, String> context, boolean isManual) {
        String phone = context.get("phone");
        if (!StringUtils.hasText(phone)) {
            throw new BusinessException(0, "调用获取验证码接口中，手机号参数为空");
        }

        String token = project.getSpecialApiToken() == null ? "815BA9C64F8B7C43" : project.getSpecialApiToken();
        String baseUrl = "http://154.86.26.17:51777";
        // 构造特殊 URL
        String fullUrl = String.format("%s/GETCODE&%s&%s", baseUrl, phone, token);

        // 等待逻辑
        if (!isManual) {
            int delaySeconds = project.getSpecialApiDelay() != null ? project.getSpecialApiDelay() : 30;
            log.info("特殊API机制：等待 {} 秒后请求验证码...", delaySeconds);
            try {
                TimeUnit.SECONDS.sleep(delaySeconds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("等待被中断");
            }
        }

        log.info("特殊API机制：发起请求 -> {}", fullUrl);
        try {
            // 1. 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.add("Connection", "close");
            headers.add("User-Agent", "Mozilla/5.0");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 2. 发起请求
            // 注意：RestTemplate 直接传 String URL 不会自动过度转义特殊字符，适合这种 & 连接的 URL
            ResponseEntity<String> response = specialRestTemplate.exchange(
                    fullUrl,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            String result = response.getBody();
            log.info("特殊API响应: {}", result);

            if (StringUtils.hasText(result)) {
                result = result.trim();
                if (!"NO".equalsIgnoreCase(result)) {
                    // 尝试提取验证码
                    Matcher matcher = Pattern.compile("\\d{4,8}").matcher(result);
                    if (matcher.find()) {
                        return matcher.group();
                    }
                    // 如果没有匹配到纯数字，返回原始内容
                    return result;
                }
            }
        } catch (Exception e) {
            log.error("特殊API获码请求异常: {}", e.getMessage());
            // 这里不抛出异常，返回 null 代表本次没取到，交给上层逻辑处理退款或重试
        }
        return null;
    }

    // 特殊API查询余额
    public String getApiBalanceSpecial(Project project) {
        String token = project.getSpecialApiToken() == null ? "815BA9C64F8B7C43" : project.getSpecialApiToken();
        String baseUrl = "http://154.86.26.17:51777";
        String url = String.format("%s/CX&%s", baseUrl, token);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Connection", "close");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = specialRestTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("余额查询失败", e);
            return "Error";
        }
    }

    //------------------------加解密特殊API----------------------------------


    // 静态初始化 BouncyCastle 驱动
    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // 专用的 ObjectMapper 配置
    private static final ObjectMapper aesMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    // -------------------- 新增：AES 特殊 API (示例 m_1688_a7) 封装 --------------------

    /**
     * 通用 AES 加密请求封装
     */
    private Map<String, Object> callAesSpecialApi(String url, Map<String, Object> params, String key) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.TEXT_PLAIN);
            headers.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0.0.0 Safari/537.36");

            String encryptedPayload = null;
            if (params != null && !params.isEmpty()) {
                String json = aesMapper.writeValueAsString(params);
                encryptedPayload = aesEncrypt(json, key);
            }

            HttpEntity<String> request = new HttpEntity<>(encryptedPayload, headers);
            log.info("发起 AES 特殊 API 请求: URL={}, Params={}.加密后的参数：{}", url, params,encryptedPayload);

            // 使用 service 中已有的 specialRestTemplate
            String encryptedResponse = specialRestTemplate.postForObject(url, request, String.class);

            if (!StringUtils.hasText(encryptedResponse)) {
                return null;
            }

            String decryptedResponse = aesDecrypt(encryptedResponse, key);
            log.info("AES 特殊 API 响应解密: {}", decryptedResponse);

            return aesMapper.readValue(decryptedResponse, Map.class);
        } catch (Exception e) {
            log.error("调用 AES 特殊 API 失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * AES 加密
     */
    private String aesEncrypt(String content, String key) throws Exception {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
        // 使用 BC 提供的 PKCS7Padding
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding", "BC");
        cipher.init(Cipher.ENCRYPT_MODE, skeySpec);
        byte[] data = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(data);
    }

    /**
     * AES 解密
     */
    private String aesDecrypt(String content, String key) throws Exception {
        byte[] raw = key.getBytes(StandardCharsets.UTF_8);
        SecretKeySpec skeySpec = new SecretKeySpec(raw, "AES");
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS7Padding", "BC");
        cipher.init(Cipher.DECRYPT_MODE, skeySpec);
        byte[] encrypted1 = Base64.getDecoder().decode(content);
        byte[] original = cipher.doFinal(encrypted1);
        return new String(original, StandardCharsets.UTF_8);
    }

    /**
     * 实现 1: AES 特殊接口获取手机号
     */
    public Map<String, String> getPhoneNumberAesSpecial(Project project) {
        String apiGateway = project.getAesSpecialApiGateway() == null ? "http://apim1a7x.bigbus666.top:2086/s/m" : project.getAesSpecialApiGateway();
        String outNumber = project.getAesSpecialApiOutNumber();
        String key = project.getAesSpecialApiKey();

        String url = String.format("%s/n/%s", apiGateway, outNumber);

        Map<String, Object> params = new HashMap<>();
        params.put("projectName", project.getAesSpecialApiProjectName());

        Map<String, Object> result = callAesSpecialApi(url, params, key);

        if (result != null && result.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) result.get("data");
            Map<String, String> context = new HashMap<>();
            context.put("phone", String.valueOf(data.get("mobile")));
            context.put("id", String.valueOf(data.get("extId")));
            return context;
        }

        throw new BusinessException("获取手机号失败，请稍后再试！");
    }

    /**
     * 实现: AES 特殊接口获取验证码 (单次动作)
     */
    public String getVerificationCodeAesSpecial(Project project, Map<String, String> context) {
        String gateway = project.getAesSpecialApiGateway() == null ? "http://apim1a7x.bigbus666.top:2086/s/m" : project.getAesSpecialApiGateway();
        String outNumber = project.getAesSpecialApiOutNumber();
        String key = project.getAesSpecialApiKey();
        String extId = context.get("id"); // 对应 getPhoneNumberAesSpecial 中存入的 Id

        if (!StringUtils.hasText(gateway) || !StringUtils.hasText(extId)) {
            log.error("AES 取码参数缺失: gateway={}, extId={}", gateway, extId);
            return null;
        }

        // 规范化 URL 拼接
        String baseUrl = gateway.endsWith("/") ? gateway.substring(0, gateway.length() - 1) : gateway;
        String url = String.format("%s/r/%s", baseUrl, outNumber);

        Map<String, Object> params = new HashMap<>();
        params.put("extId", extId);

        try {
            Map<String, Object> result = callAesSpecialApi(url, params, key);

            if (result != null && result.containsKey("data")) {
                Object dataObj = result.get("data");
                if (dataObj instanceof Map) {
                    Map data = (Map) dataObj;

                    // 兼容性提取：依次尝试取 smsContent, message, code
                    String smsContent = String.valueOf(data.getOrDefault("smsContent", ""));
                    String msgField = String.valueOf(data.getOrDefault("message", "")); // 新增对 message 字段的提取
                    String code = String.valueOf(data.getOrDefault("code", ""));

                    // 按照优先级返回第一个不为空的内容
                    if (StringUtils.hasText(smsContent) && !"null".equals(smsContent)) {
                        return smsContent;
                    }
                    if (StringUtils.hasText(msgField) && !"null".equals(msgField)) {
                        return msgField;
                    }
                    if (StringUtils.hasText(code) && !"null".equals(code)) {
                        return code;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("AES单次请求异常: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 实现 3: AES 特殊接口释放号码 (对应文档 2.4 释放号码)
     * URL: api_gateway + "/f/{customerOutNumber}"
     */
    public boolean  releasePhoneNumberAesSpecial(Project project, String extId ,boolean isSuccess) {
        // 1. 获取配置信息
        String apiGateway = project.getAesSpecialApiGateway() == null
                ? "http://apim1a7x.bigbus666.top:2086/s/m"
                : project.getAesSpecialApiGateway();

        // 对应文档中的 customerOutNumber
        String outNumber = project.getAesSpecialApiOutNumber();
        // AES 专用密钥
        String key = project.getAesSpecialApiKey();

        if (!StringUtils.hasText(outNumber) || !StringUtils.hasText(key)) {
            log.error("AES释放号码失败：缺少 customerOutNumber 或 Key 配置");
            return false;
        }

        // 2. 规范化 URL 拼接
        String baseUrl = apiGateway.endsWith("/") ? apiGateway.substring(0, apiGateway.length() - 1) : apiGateway;
        String url = String.format("%s/f/%s", baseUrl, outNumber);

        // 3. 准备加密前的请求参数 (严格对应文档)
        Map<String, Object> params = new HashMap<>();
        params.put("extId", extId);

        // 同样使用项目中的自定义配置
        String statusValue = isSuccess
                ? (StringUtils.hasText(project.getReleaseSuccessStatus()) ? project.getReleaseSuccessStatus() : "1")
                : (StringUtils.hasText(project.getReleaseFailStatus()) ? project.getReleaseFailStatus() : "0");

        String msgValue = isSuccess
                ? (StringUtils.hasText(project.getReleaseSuccessMsg()) ? project.getReleaseSuccessMsg() : "ok")
                : (StringUtils.hasText(project.getReleaseFailMsg()) ? project.getReleaseFailMsg() : "fail");

        params.put("status", statusValue);
        params.put("reason", msgValue);

        log.info("准备调用 AES 释放接口: url={}, extId={}", url, extId);

        try {
            Map<String, Object> result = callAesSpecialApi(url, params, key);
            if (result != null && result.containsKey("code")) {
                Object codeObj = result.get("code");
                // 文档规定 code 为 0 表示接口操作成功
                if ("0".equals(String.valueOf(codeObj)) || Integer.valueOf(0).equals(codeObj)) {
                    log.info("AES 特殊接口释放号码成功: extId={}", extId);
                    return true;
                } else {
                    log.warn("AES 特殊接口释放号码返回异常码: code={}, 响应={}", codeObj, result);
                }
            } else {
                log.warn("AES 特殊接口释放号码响应为空或格式非法");
            }
        } catch (Exception e) {
            log.error("执行 AES 释放号码接口时发生异常: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 实现 4: AES 特殊接口查询余额
     */
    public String getApiBalanceAesSpecial(Project project) {
        String apiGateway = project.getAesSpecialApiGateway() == null ? "http://apim1a7x.bigbus666.top:2086/s/m" : project.getAesSpecialApiGateway();
        String outNumber = project.getSelectNumberApiRequestValue();
        String key = project.getSpecialApiToken();

        String url = String.format("%s/q/%s", apiGateway, outNumber);
        Map<String, Object> result = callAesSpecialApi(url, new HashMap<>(), key);

        if (result != null && result.containsKey("data")) {
            return String.valueOf(result.get("data"));
        }
        return "0";
    }

    /**
     * AES 专用轮询逻辑
     */
    private String pollForAesVerificationCode(Project project, Map<String, String> context, Supplier<Boolean> stopCondition) {
        long startTime = System.currentTimeMillis();
        long timeout = 5 * 60 * 1000L; // 5分钟超时
        int attempts = 0;

        log.info("开始 AES 轮询，手机号: {}, 关联ID: {}", context.get("phone"), context.get("id"));

        while (System.currentTimeMillis() - startTime < timeout) {
            // 检查外部停止条件（比如用户在前端关闭了任务）
            if (stopCondition != null && stopCondition.get()) {
                log.info("检测到任务终止信号，停止 AES 轮询。");
                throw new BusinessException("任务已关闭或手动停止");
            }

            attempts++;
            try {
                // 调用单次 AES 获取接口
                String rawCodeOrSms = getVerificationCodeAesSpecial(project, context);
                if (StringUtils.hasText(rawCodeOrSms)) {
                    // 使用正则提取 4-8 位数字
                    Matcher matcher = Pattern.compile("\\d{4,8}").matcher(rawCodeOrSms);
                    if (matcher.find()) {
                        String code = matcher.group();
                        log.info("AES 轮询成功！第 {} 次尝试获取到验证码: {}, 耗时: {}ms",
                                attempts, code, System.currentTimeMillis() - startTime);
                        return code;
                    }
                }

                log.info("AES 第 {} 次尝试未获码，已耗时 {}ms，准备下次重试...",
                        attempts, System.currentTimeMillis() - startTime);

                // AES 接口建议不要请求太频繁，间隔 3-5 秒
                Thread.sleep(3000);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("获取验证码被中断");
            } catch (Exception e) {
                log.warn("AES 轮询单次请求异常: {}", e.getMessage());
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
        log.error("AES获取验证码超时");
        throw new BusinessException("获取验证码超时（5分钟未获取到）");
    }
}