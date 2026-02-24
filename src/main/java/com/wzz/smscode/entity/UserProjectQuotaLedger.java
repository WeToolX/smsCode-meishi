package com.wzz.smscode.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.wzz.smscode.annotation.ColumnComment;
import com.wzz.smscode.annotation.DefaultValue;
import com.wzz.smscode.annotation.ForeignKey;
import com.wzz.smscode.annotation.Index;
import com.wzz.smscode.annotation.TableComment;
import com.wzz.smscode.common.BaseEntity;
import com.wzz.smscode.enums.ForeignKeyAction;
import com.wzz.smscode.enums.IndexType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户项目线路配额流水表
 * <p>
 * 记录每一次配额增减，支持幂等业务号。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_project_quota_ledger")
@TableComment("用户项目线路配额流水表")
@ForeignKey(
        name = "fk_user_project_quota_ledger_user_id",
        columns = {"user_id"},
        referenceEntity = User.class,
        referencedColumns = {"id"},
        onDelete = ForeignKeyAction.CASCADE,
        onUpdate = ForeignKeyAction.RESTRICT
)
@Index(
        name = "uk_quota_ledger_biz_no",
        columns = {"biz_no"},
        type = IndexType.UNIQUE,
        comment = "业务号唯一索引"
)
@Index(
        name = "idx_quota_ledger_user_project_line_time",
        columns = {"user_id", "project_id", "line_id", "timestamp"},
        comment = "用户项目线路时间索引"
)
public class UserProjectQuotaLedger extends BaseEntity {

    /**
     * 幂等业务号
     */
    @ColumnComment("业务号")
    @TableField("biz_no")
    private String bizNo;

    /**
     * 目标用户ID
     */
    @ColumnComment("目标用户ID")
    @TableField("user_id")
    private Long userId;

    /**
     * 操作人ID（管理员固定0）
     */
    @ColumnComment("操作人ID")
    @TableField("operator_id")
    @DefaultValue("0")
    private Long operatorId;

    /**
     * 项目ID
     */
    @ColumnComment("项目ID")
    @TableField("project_id")
    private String projectId;

    /**
     * 线路ID（字符串）
     */
    @ColumnComment("线路ID")
    @TableField("line_id")
    private String lineId;

    /**
     * 本次变动数量（正数）
     */
    @ColumnComment("变动数量")
    @TableField("change_count")
    private Long changeCount;

    /**
     * 变动类型：1-入账, 0-出账
     */
    @ColumnComment("变动类型(1入账,0出账)")
    @TableField("ledger_type")
    private Integer ledgerType;

    /**
     * 变动前数量
     */
    @ColumnComment("变动前数量")
    @TableField("count_before")
    private Long countBefore;

    /**
     * 变动后数量
     */
    @ColumnComment("变动后数量")
    @TableField("count_after")
    private Long countAfter;

    /**
     * 备注
     */
    @ColumnComment("备注")
    @TableField("remark")
    private String remark;

    /**
     * 操作时间
     */
    @ColumnComment("操作时间")
    @TableField("timestamp")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime timestamp;
}
