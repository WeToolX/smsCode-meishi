package com.wzz.smscode.dto.agent;

import lombok.Data;

/**
 * 代理本人项目线路配额DTO
 */
@Data
public class AgentMyQuotaDTO {

    /**
     * 项目ID
     */
    private String projectId;

    /**
     * 项目名称
     */
    private String projectName;

    /**
     * 线路ID
     */
    private String lineId;

    /**
     * 线路名称
     */
    private String lineName;

    /**
     * 剩余可用配额
     */
    private Long availableCount;
}
