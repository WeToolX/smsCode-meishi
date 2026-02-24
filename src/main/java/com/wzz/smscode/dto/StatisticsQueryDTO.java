package com.wzz.smscode.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class StatisticsQueryDTO implements Serializable {

    /**
     * 当前页码
     */
    private long current = 1;

    /**
     * 每页显示条数
     */
    private long size = 10;

    /**
     * 筛选条件：项目名 (将支持模糊查询)
     */
    private String projectName;

    /**
     * 筛选条件：项目ID
     */
    private String projectId;

    /**
     * 筛选条件：线路ID
     */
    private String lineId;

    /**
     * 开始时间
     */
    private String startTime;
    /**
     * 结束时间
     */
    private String endTime;
}
