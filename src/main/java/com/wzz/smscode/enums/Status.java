package com.wzz.smscode.enums;

/**
 * 表示操作结果的状态码枚举。（仅仅是用户端使用该状态码）
 * 约定 0 表示成功，负数表示各种错误。
 */
public enum Status {
    /**
     * 操作成功。
     */
    SUCCESS(0, "成功"),
    /**
     * 取号失败，无号码可用。
     */
    NO_AVAILABLE_NUMBER(-1, "无可用号码（取号失败，无号码可用）。"),
    /**
     * 取码失败，尚未获取到验证码或超时。
     */
    NO_VERIFICATION_CODE(-2, "无验证码（取码失败，尚未获取到验证码或超时）"),

    /**
     * 用户ID或密码错误。
     */
    USER_VALIDATION_FAILED(-3, "用户验证失败（用户ID或密码错误）"),
    /**
     * 项目线路配额不足。
     */
    INSUFFICIENT_BALANCE(-4, "项目线路配额不足"),
    /**
     * 其他异常情况。
     */
    SYSTEM_ERROR(-5, "系统错误（其他异常情况）");

    private final int code;
    private final String message;

    /**
     * 构造函数，用于初始化枚举常量。
     *
     * @param code    状态码
     * @param message 对应的提示信息
     */
    Status(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 获取状态码。
     *
     * @return 整数形式的状态码
     */
    public int getCode() {
        return code;
    }

    /**
     * 获取状态的提示信息。
     *
     * @return 字符串形式的提示信息
     */
    public String getMessage() {
        return message;
    }

    /**
     * 根据整型状态码查找对应的枚举常量。
     *
     * @param code 要查找的状态码
     * @return 对应的 Status 枚举常量，如果找不到则返回 null
     */
    public static Status fromCode(int code) {
        for (Status status : values()) {
            if (status.getCode() == code) {
                return status;
            }
        }
        return null; // 或者可以根据业务需求抛出异常
    }
}
