package com.wzz.smscode.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
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

/**
 * 用户项目线路配额表
 * <p>
 * 按 user_id + project_id + line_id 维度维护可用配额数量。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_project_quota")
@TableComment("用户项目线路配额表")
@ForeignKey(
        name = "fk_user_project_quota_user_id",
        columns = {"user_id"},
        referenceEntity = User.class,
        referencedColumns = {"id"},
        onDelete = ForeignKeyAction.CASCADE,
        onUpdate = ForeignKeyAction.RESTRICT
)
@Index(
        name = "uk_user_project_line",
        columns = {"user_id", "project_id", "line_id"},
        type = IndexType.UNIQUE,
        comment = "用户+项目+线路唯一"
)
@Index(
        name = "idx_project_line",
        columns = {"project_id", "line_id"},
        comment = "项目线路查询索引"
)
public class UserProjectQuota extends BaseEntity {

    /**
     * 用户ID
     */
    @ColumnComment("用户ID")
    @TableField("user_id")
    private Long userId;

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
     * 当前可用配额
     */
    @ColumnComment("可用配额")
    @TableField("available_count")
    @DefaultValue("0")
    private Long availableCount;
}
