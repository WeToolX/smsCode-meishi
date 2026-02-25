package com.wzz.smscode.dto;

import lombok.Data;
import java.util.List;

/**
 * 单个项目的整体统计数据 DTO，包含其下所有线路的详细统计
 */
@Data
public class ProjectStatisticsDTO {

    private String projectId;
    private String projectName;

    /**
     * 线路数量
     */
    private long lineCount;

    /**
     * 总取号数量
     */
    private long totalRequests;

    /**
     * 总取码数量
     */
    private long successCount;

    /**
     * 总回码率 (%)
     */
    private double successRate;

    /**
     * 该项目下每条线路的详细统计列表
     */
    private List<LineStatisticsDTO> lineDetails;
}
