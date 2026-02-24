package com.wzz.smscode.dto;


import lombok.Data;
import java.util.Date;

@Data
public class SubordinateNumberRecordQueryDTO {
    private long current = 1;
    private long size = 10;
    private String userName;      // 按下级用户名筛选
    private String projectName;   // 按项目名称（模糊）筛选
    private String projectId;     // 按项目ID筛选
    private String lineId;       // 按线路ID筛选
    private String phoneNumber;   // 按手机号（模糊）筛选
    private Integer status;       // 按记录状态筛选
    private Integer charged;      // 按扣费状态筛选
    private String startTime;     // 按取号时间范围筛选 (类型已修改)
    private String endTime;       // (类型已修改)
}
