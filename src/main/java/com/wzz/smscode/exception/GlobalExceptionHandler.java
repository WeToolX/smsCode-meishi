package com.wzz.smscode.exception;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenException;
import com.wzz.smscode.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.MyBatisSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.error("不支持的请求方法: {}, 错误信息={}", e.getMessage(), e);
        return Result.error("不支持的请求方法");
    }

    /**
     * 【必须添加】处理请求参数类型转换失败 (如: "?parentId=p" 无法转为 Long)
     * 这是解决当前报错的直接方案
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName(); // 获取参数名: parentId
        Class<?> requiredType = ex.getRequiredType(); // 获取目标类型: Long
        Object actualValue = ex.getValue(); // 获取实际值: "p"

        String message = String.format("参数 [%s] 的值 '%s' 不是有效的 %s 类型",
                paramName,
                actualValue,
                requiredType != null ? requiredType.getSimpleName() : "未知");

        log.warn("请求参数类型错误: {}", message, ex); // 保留堆栈信息便于排查

        return Result.error(400, message); // 返回友好的中文提示
    }

    /**
     * 【推荐】统一处理参数校验异常 (JSR-303)
     * 整合了对 @RequestBody, @RequestParam, @ModelAttribute 等参数校验失败的处理
     */
    @ExceptionHandler(BindException.class) // 只捕获父类即可
    @ResponseStatus(HttpStatus.BAD_REQUEST) // 使用此注解可让Spring自动设置HTTP状态码
    public Result<?> handleBindException(BindException e) {
        String msg = "参数校验失败";
        FieldError fieldError = e.getBindingResult().getFieldError();
        if (fieldError != null) {
             msg = fieldError.getField() + " " + fieldError.getDefaultMessage();
        }
        log.warn("参数校验或绑定异常: {}", msg, e);
        return Result.error(400, msg);
    }
    /**
     * 【新增】处理单个必需参数缺失的异常 (@RequestParam)
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<?> handleMissingServletRequestParameterException(MissingServletRequestParameterException e) {
        // 格式化为: "必需的请求参数 '参数名' 不存在"
        String msg = "必需的请求参数 '" + e.getParameterName() + "' 不存在";
        log.warn(msg);
        return Result.error(400, msg);
    }

    // JSON解析失败
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<?> handleHttpMessageNotReadableException(HttpMessageNotReadableException e) {
        log.warn("请求体JSON解析失败: {}, 错误信息={}", e.getMessage(), e);
        return Result.error(400, "请求参数格式错误");
    }

    @ExceptionHandler(NumberFormatException.class)
    public Result<?> handNumberFormatException(NumberFormatException e){
        log.warn("数据类型转换错误，请尝试刷新网页或修改错误的数据类型: {}, 错误信息={}", e.getMessage(), e);
        return Result.error(400,"数据类型转换错误，请尝试刷新网页或修改错误的数据类型");
    }

    @ExceptionHandler(NullPointerException.class)
    public Result<?> handNullPointerException(NullPointerException e){
        log.warn("参数出现空值: {}, 错误信息={}", e.getMessage(), e);
        return Result.error(400,"参数出现空值");
    }

//     SaToken异常
    @ExceptionHandler(SaTokenException.class)
    public Result<?> handleSaTokenException(SaTokenException e) {
        int code = e.getCode();
        String msg;
        switch (code) {
            case 11001: msg = "未能读取到有效Token"; code = 101; break;
            case 11002: msg = "登录时的账号id值为空"; code = 102; break;
            case 11003: msg = "更改Token指向的账号Id时，账号Id值为空"; code = 103; break;
            case 11011: msg = "未能读取到有效Token"; code = 104; break;
            case 11012: msg = "Token无效"; code = 105; break;
            case 11013: msg = "Token已过期"; code = 106; break;
            case 11014: msg = "Token已被顶下线"; code = 107; break;
            case 11015: msg = "Token已被踢下线"; code = 108; break;
            case 11016: msg = "Token已被冻结"; code = 109; break;
            case 11041: msg = "无权限，请联系管理员"; code = 403; break;
            case 11042: msg = "无此角色权限：" + e.getMessage(); code = 404; break;
            default:
                log.error("未知SaToken异常: code={}, msg={}， 错误信息={}", code, e.getMessage(), e);
                return Result.error(500, "认证服务器错误");
        }
        log.warn("SaToken鉴权异常: code={}, msg={}, 错误信息={}", code, msg, e);
        return Result.error(code, msg);
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<?> handleNotLoginException(NotLoginException nle) {
        // 判断场景, p.s. 这种区分片面, 实际根据前端约定进行灵活处理
        String message;
        if (nle.getType().equals(NotLoginException.NOT_TOKEN)) {
            message = "请求头未提供Token";
        } else if (nle.getType().equals(NotLoginException.INVALID_TOKEN)) {
            message = "Token无效";
        } else if (nle.getType().equals(NotLoginException.TOKEN_TIMEOUT)) {
            message = "Token已过期";
        } else {
            message = "当前会话未登录";
        }
        return Result.error(401, message);
    }
//
//    // 可以添加自定义业务异常
    @ExceptionHandler(BusinessException.class)
    public Result<?> handleBusinessException(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage(), e);
        return Result.error(e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND) // 设置HTTP状态码为404
    public Result<?> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("资源未找到：{}", e.getMessage());
        return Result.error(404, "您访问的资源不存在");
    }
    // 全局异常兜底
    @ExceptionHandler(Exception.class)
    public Result<?> handleAllException(Exception e) {
        log.error("服务器未知异常:{ }", e);
        return Result.error(500, "系统繁忙，未知错误，请稍后再试");
    }

    /**
     * 【重点修改】处理调用外部服务(WebClient)时发生的异常
     * 并将详细的错误信息返回给前端
     */
    @ExceptionHandler(WebClientResponseException.class)
    public Result<?> handleWebClientResponseException(WebClientResponseException e) {
        // 1. 提取详细的错误信息
        int statusCode = e.getStatusCode().value();
        String responseBody = e.getResponseBodyAsString();
        String requestUri = e.getRequest() != null ? e.getRequest().getURI().toString() : "N/A";

        // 2. 记录完整的错误日志，用于后端排查
        log.error("调用外部服务失败: Status='{}', URI='{}', Body='{}'",
                statusCode, requestUri, responseBody, e);

        // 3. 构建返回给前端的结构化错误详情
        Map<String, Object> errorDetails = new HashMap<>();
        errorDetails.put("status", statusCode);
        errorDetails.put("uri", requestUri);
        errorDetails.put("body", responseBody);

        // 4. 根据不同的状态码，返回不同的外层提示信息
        if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
            // 对于本次的 404 错误
            return Result.error(HttpStatus.BAD_GATEWAY.value(), "依赖的外部资源未找到", errorDetails);
        } else if (e.getStatusCode().is4xxClientError()) {
            // 对于其他 4xx 客户端错误 (如 400, 401, 403)
            return Result.error(HttpStatus.BAD_GATEWAY.value(), "外部服务调用异常，请检查请求", errorDetails);
        } else if (e.getStatusCode().is5xxServerError()) {
            // 如果是服务端错误 (如 500, 503)，说明下游服务本身出了问题
            return Result.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "外部依赖服务暂时不可用", errorDetails);
        }

        // 对于其他未分类的HTTP错误
        return Result.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "调用外部服务时发生未知错误", errorDetails);
    }



    /**
     * 数据库框架异常MyBatisSystemException
     */
    @ExceptionHandler(MyBatisSystemException.class)
    public Result<?> handleMyBatisSystemException(MyBatisSystemException e){
        log.warn("数据库框架处理异常: {}，异常详情：{}", e.getMessage(), e);
        return Result.error("系统异常：异常码-10045");
    }
}
