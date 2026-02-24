package com.wzz.smscode.dto.number;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 号码记录传输对象
 */
@Data
public class NumberDTO {

    /**
     * 手机号
     */
    private String phoneNumber;
    /**
     * 验证码
     */
    private String code;
    /**
     * 状态（例如：0=已取号, 1=已取码, 2=已释放, 3=超时）
     */
    private Integer status;

    /**
     * 备注
     */
    private String remark;

    /**
     * 错误信息
     */
    private String errorInfo;
    /**
     * 项目id
     */
    private String projectId;
    /**
     * 线路id
     */
    private String lineId;
    /**
     * 用户名
     */
    private String userName;
    /**
     * 项目名称
     */
    private String projectName;
    /**
     * 获取手机号的时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime getNumberTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime codeReceivedTime;
}
