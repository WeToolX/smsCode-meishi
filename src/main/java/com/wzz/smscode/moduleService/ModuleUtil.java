package com.wzz.smscode.moduleService;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.ParseContext;
import com.wzz.smscode.dto.ApiConfig.ApiConfig;
import com.wzz.smscode.dto.ApiConfig.ExtractRule;
import com.wzz.smscode.dto.RequestDTO.KeyValue;
import com.wzz.smscode.entity.Project;
import com.wzz.smscode.exception.BusinessException;
import com.wzz.smscode.service.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleUtil {
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(.+?)}}");


    private static final Configuration JSON_PATH_CONFIG = Configuration.defaultConfiguration()
            .addOptions(Option.SUPPRESS_EXCEPTIONS);
    private static final ParseContext JSON_PARSER = JsonPath.using(JSON_PATH_CONFIG);

    // 【修改点】：去掉 static 和 = null，Spring 会通过构造函数自动注入 WebClient
    private final WebClient webClient;

    private final ProjectService projectService; // 需要更新Token到数据库


    @Value("${admin.debug:false}") // 给个默认值防止报错
    private Boolean debug;

    /**
     * 【新增方法】执行登录并保存Token的核心逻辑
     * @param project 项目实体
     * @return 获取到的新Token
     */
    public String executeLoginAndSave(Project project) {
        if (project.getLoginConfig() == null || !StringUtils.hasText(project.getLoginConfig().getUrl())) {
            throw new BusinessException("项目未配置登录接口，无法执行登录");
        }

        Map<String, String> context = new HashMap<>();
        // 有些登录接口可能需要之前的Token作为参数（虽然少见），或者是其他固定参数
        if (StringUtils.hasText(project.getAuthTokenValue())) {
            context.put("token", project.getAuthTokenValue());
        }

        try {
            log.info("开始执行项目 [{} - {}] 的强制登录...", project.getProjectName(), project.getLineId());

            // 执行登录 API
            executeApi(project.getLoginConfig(), context);

            // 获取新 Token (约定提取变量名为 "token")
            String newToken = context.get("token");
            if (StringUtils.hasText(newToken)) {
                // 更新对象属性
                project.setAuthTokenValue(newToken);
                project.setTokenExpirationTime(LocalDateTime.now());

                // 持久化到数据库
                projectService.updateById(project);
                log.info("登录成功，数据库 Token 及时间已更新。Token: {}", newToken);
                return newToken;
            } else {
                log.warn("执行登录成功，但在 Context 中未获取到 'token' 变量，请检查提取规则配置！");
                throw new BusinessException("登录接口执行成功但未提取到Token变量");
            }
        } catch (Exception e) {
            log.error("项目 [{} - {}] 执行登录失败, 错误：{}", project.getProjectName(), project.getLineId(), e);
            throw new BusinessException("接口登录失败：" + e.getMessage());
        }
    }

    /**
     * 获取有效token
     */
    public Map<String, String> getApiToken(Project project, String... dynamicParams) {
        Map<String, String> context = new HashMap<>();
        // 如果数据库里已经存了Token，先放进去
        if (StringUtils.hasText(project.getAuthTokenValue())) {
            context.put("token", project.getAuthTokenValue());
        }
        // 放入传入的动态参数 (如果有)
        if (dynamicParams != null && dynamicParams.length > 0) {
            for (int i = 0; i < dynamicParams.length; i++) {
                context.put("param" + (i + 1), dynamicParams[i]);
            }
        }
        // 2. 执行登录逻辑
        if (project.getLoginConfig() != null && StringUtils.hasText(project.getLoginConfig().getUrl())) {
            boolean needLogin = false;
            LocalDateTime lastUpdateTime = project.getTokenExpirationTime();
            String currentToken = project.getAuthTokenValue();
            // 判定条件 1: Token 为空，必须登录
            if (!StringUtils.hasText(currentToken)) {
                log.info("当前无有效Token，准备执行登录...");
                needLogin = true;
            }
            // 判定条件 2: 时间字段为空，必须登录
            else if (lastUpdateTime == null) {
                log.info("Token时间未记录，准备执行登录...");
                needLogin = true;
            }
            // 判定条件 3: 距离上次更新超过 24 小时，需要重新登录
            else if (lastUpdateTime.plusHours(24).isBefore(LocalDateTime.now())) {
                log.info("Token已过期 (上次更新于: {})，准备刷新Token...", lastUpdateTime);
                needLogin = true;
            }
            // 不需要登录: Token 存在且在 24 小时内
            else {
                log.info("Token有效 (24小时内)，跳过登录操作。");
                context.put("token", currentToken);
            }
            // 执行登录操作
            if (needLogin) {
                try {
                    String newToken = executeLoginAndSave(project);
                    context.put("token", newToken);
                } catch (Exception e) {
                    log.error("项目 [{} - {}] 执行登录失败, 错误：{}", project.getProjectName(), project.getLineId(), e);
                    throw new BusinessException("接口登录失败，无法执行后续操作");
                }
            }
        }
        return context;
    }


    /**
     * 通用 API 执行方法
     * @param config 前端传递的接口配置
     * @param context 上下文变量 (入参为当前变量，执行后会将提取的新变量 put 进去)
     */
    public void executeApi(ApiConfig config, Map<String, String> context) {
        try{
            // 1. 处理前置操作 (PreHooks)
            if (config.getPreHooks() != null) {
                for (KeyValue hook : config.getPreHooks()) {
                    String val = replaceVariables(hook.getValue(), context);
                    context.put(hook.getKey(), val);
                }
            }

            // 2. 构建 URL
            String rawUrl = config.getUrl();
            if (!StringUtils.hasText(rawUrl)) {
                throw new BusinessException("接口 URL 不能为空");
            }
            String finalUrl = replaceVariables(rawUrl, context);

            // 3. 构建 Query 参数
            UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(finalUrl);
            if (config.getParams() != null) {
                for (KeyValue param : config.getParams()) {
                    if (StringUtils.hasText(param.getKey())) {
                        uriBuilder.queryParam(param.getKey(), replaceVariables(param.getValue(), context));
                    }
                }
            }
            URI uri = uriBuilder.build().encode().toUri();

            // 4. 构建请求体 & 准备日志数据
            Object body = null;
            Object logBody = null; // 用于打印日志的 Body 内容
            MediaType contentType = MediaType.APPLICATION_JSON;

            if ("JSON".equalsIgnoreCase(config.getBodyType())) {
                String jsonStr = config.getJsonBody();
                if (StringUtils.hasText(jsonStr)) {
                    String processedJson = replaceVariables(jsonStr, context);
                    body = processedJson;
                    logBody = processedJson; // JSON 直接记录字符串
                    contentType = MediaType.APPLICATION_JSON;
                }
            } else if ("FORM_DATA".equalsIgnoreCase(config.getBodyType()) || "X_WWW_FORM".equalsIgnoreCase(config.getBodyType())) {
                MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
                if (config.getFormBody() != null) {
                    for (KeyValue item : config.getFormBody()) {
                        formData.add(item.getKey(), replaceVariables(item.getValue(), context));
                        logBody = formData;
                    }
                }
                body = BodyInserters.fromFormData(formData);
                contentType = MediaType.APPLICATION_FORM_URLENCODED;
            }

            // 5. 发起请求
            WebClient.RequestBodySpec requestSpec = webClient
                    .method(HttpMethod.valueOf(config.getMethod().toUpperCase()))
                    .uri(uri)
                    .contentType(contentType);

            // 添加 Headers 并记录日志
            Map<String, String> logHeaders = new HashMap<>(); // 用于日志记录
            // 添加 Headers
            if (config.getHeaders() != null) {
                for (KeyValue header : config.getHeaders()) {
                    if (StringUtils.hasText(header.getKey())) {
                        String headerVal = replaceVariables(header.getValue(), context);
                        requestSpec.header(header.getKey(), headerVal);
                        logHeaders.put(header.getKey(), headerVal);
                    }
                }
            }

            if (body != null) {
                requestSpec.body(body instanceof BodyInserters.FormInserter ? (BodyInserters.FormInserter) body : BodyInserters.fromValue(body));
            }
            Map<String, Object> requestLog = new LinkedHashMap<>();
            requestLog.put("URL", uri.toString());
            requestLog.put("Method", config.getMethod());
            requestLog.put("Headers", logHeaders);
            requestLog.put("Body", logBody);

            log.info("准备请求 API >>> {}", requestLog);

            //获取响应
            String responseBody = requestSpec.retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();

            log.info("API响应 [{}]: {}", finalUrl, responseBody);

            //执行变量提取 (Extract Rules)
            if (config.getExtractRules() != null && StringUtils.hasText(responseBody)) {
                for (ExtractRule rule : config.getExtractRules()) {
                    try {
                        Object val = JSON_PARSER.parse(responseBody).read(rule.getJsonPath());
                        if (val != null) {
                            context.put(rule.getTargetVariable(), String.valueOf(val));
                            log.info("变量提取成功: {} = {}", rule.getTargetVariable(), val);
                        } else {
                            // 在抑制异常模式下，找不到会走这里
                            String errorMsg = extractErrorMessage(responseBody);
                            log.info("变量提取失败 (路径不存在) [Key: {}]: {}", rule.getTargetVariable(), errorMsg);
                            throw new BusinessException(errorMsg);
                        }
                    } catch (Exception e) {
                        // 只有发生解析错误（JSON格式非法）等才会走这里
                        String errorMsg = extractErrorMessage(responseBody);
                        log.info("变量提取失败 (解析异常) [Key: {}]: {},{}", rule.getTargetVariable(), errorMsg,e.getMessage());
                        throw new BusinessException(errorMsg);
                    }
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用接口时发生系统错误: URL={}, Error={}",config.getUrl(), e.getMessage());
            throw new BusinessException("接口调用异常: " + e.getMessage());
        }
    }

    private String extractErrorMessage(String responseBody) {
        try {
            // 常见错误字段名：msg, message, respMsg
            com.jayway.jsonpath.DocumentContext ctx = JsonPath.parse(responseBody);
            String[] possibleFields = {"$.msg", "$.message", "$.respMsg", "$.result"};
            for (String field : possibleFields) {
                String val = ctx.read(field, String.class);
                if (StringUtils.hasText(val)) return val;
            }
        } catch (Exception ignored) {}
        return "接口返回数据格式异常";
    }

    /**
     * 变量替换工具
     */
    private static String replaceVariables(String input, Map<String, String> context) {
        if (!StringUtils.hasText(input)) {
            return input;
        }
        Matcher matcher = VARIABLE_PATTERN.matcher(input);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            String value = context.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}