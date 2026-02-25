package com.wzz.smscode.dto;

import lombok.Data;

/**
 * 单条线路的统计数据 DTO
 */
@Data
public class LineStatisticsDTO {

    private String projectId;
    private String lineId;
    private String projectName;

    /**
     * 取号数量
     */
    private long totalRequests;

    /**
     * 取码数量 (成功)
     */
    private long successCount;

    /**
     * 回码率 (%)
     */
    private double successRate;

}
