package com.wzz.smscode.common;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 通用返回结果封装类
 *
 * @param <T> 数据内容的泛型
 */
@Data
@NoArgsConstructor
public class CommonResultDTO<T> implements Serializable {

    /**
     * 状态码
     * 0: 成功
     * -1: 无可用号码
     * -2: 无验证码
     * -3: 用户验证失败
     * -4: 项目线路配额不足
     * -5: 系统错误
     */
    private Integer status;

    /**
     * 描述信息
     */
    private String msg;

    /**
     * 数据内容
     */
    private T data;

    public CommonResultDTO(Integer status, String msg, T data) {
        this.status = status;
        this.msg = msg;
        this.data = data;
    }

    // --- 静态工厂方法，方便使用 ---

    /**
     * 成功，不返回数据
     */
    public static <T> CommonResultDTO<T> success() {
        return new CommonResultDTO<>(0, "成功", null);
    }

    /**
     * 成功，并返回数据
     */
    public static <T> CommonResultDTO<T> success(T data) {
        return new CommonResultDTO<>(0, "成功", data);
    }

    /**
     * 成功，并返回自定义消息和数据
     */
    public static <T> CommonResultDTO<T> success(String msg, T data) {
        return new CommonResultDTO<>(0, msg, data);
    }

    /**
     * 失败，返回指定的状态码和消息
     */
    public static <T> CommonResultDTO<T> error(Integer status, String msg) {
        return new CommonResultDTO<>(status, msg, null);
    }

    /**
     * 失败，使用通用的系统错误码
     */
    public static <T> CommonResultDTO<T> systemError() {
        return new CommonResultDTO<>(-5, "系统错误", null);
    }
}
