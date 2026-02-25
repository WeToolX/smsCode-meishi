package com.wzz.smscode.common;

/**
 * 系统常量定义类
 */
public final class Constants {

    private Constants() {}

    // --- 通用返回状态码 ---
    public static final int SUCCESS = 0;//成功
    public static final int ERROR_NO_NUMBER = -1;//暂无号码
    public static final int ERROR_NO_CODE = -2;//暂无验证码
    public static final int ERROR_AUTH_FAILED = -3;//用户认证失败
    public static final int ERROR_INSUFFICIENT_BALANCE = -4;//项目线路配额不足
    public static final int ERROR_SYSTEM_ERROR = -5;//系统错误
}
