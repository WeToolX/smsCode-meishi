package com.wzz.smscode.dto.quota;

import lombok.Data;

/**
 * 用户项目线路配额明细
 */
@Data
public class UserQuotaItemDTO {

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

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
     * 剩余配额
     */
    private Long availableCount;
}
