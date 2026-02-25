package com.wzz.smscode.dto.quota;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户项目线路配额流水明细
 */
@Data
public class UserQuotaLedgerItemDTO {

    /**
     * 流水ID
     */
    private Long id;

    /**
     * 业务号
     */
    private String bizNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 用户名
     */
    private String userName;

    /**
     * 操作人ID
     */
    private Long operatorId;

    /**
     * 操作人名称
     */
    private String operatorName;

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
     * 变动数量
     */
    private Long changeCount;

    /**
     * 流水类型（1入账，0出账）
     */
    private Integer ledgerType;

    /**
     * 变动前数量
     */
    private Long countBefore;

    /**
     * 变动后数量
     */
    private Long countAfter;

    /**
     * 备注
     */
    private String remark;

    /**
     * 时间戳
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
}
