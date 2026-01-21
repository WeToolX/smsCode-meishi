package com.wzz.smscode.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.wzz.smscode.annotation.*;
import com.wzz.smscode.common.BaseEntity;
import com.wzz.smscode.enums.ForeignKeyAction;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 号码记录表实体类
 * <p>
 * 记录用户每次获取的手机号，以及获取验证码的过程和结果，
 * 用于跟踪每一次取号/取码业务的全生命周期。
 * </p>
 * 对应数据库表：number_record
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("number_record")
@TableComment("号码记录表")
@ForeignKey(
        name = "fk_record_user_id",
        columns = {"user_id"},
        referenceEntity = User.class,
        referencedColumns = {"id"},
        onDelete = ForeignKeyAction.CASCADE, // 用户删除时，其号码记录也一并删除
        onUpdate = ForeignKeyAction.RESTRICT
)
@Index(name = "idx_user_id", columns = {"user_id"}, comment = "用户ID索引")
@Index(name = "idx_phone_number", columns = {"phone_number"}, comment = "手机号索引，便于反向追踪")
@Index(name = "idx_get_number_time", columns = {"get_number_time"}, comment = "取号时间索引，便于按时间查询")
public class NumberRecord extends BaseEntity {

    // 主键 id (对应 number_id) 从 BaseEntity 继承

    /**
     * 请求取号的用户ID
     */
    @ColumnComment("用户ID")
    @TableField("user_id")
    private Long userId;

    /**
     * 用户名
     */
    @ColumnComment("用户名")
    @TableField("user_name")
    @DefaultValue("'无'")
    private String userName;

    /**
     * 项目ID
     */
    @ColumnComment("项目ID")
    @TableField("project_id")
    private String projectId;

    /**
     * 项目线路ID
     */
    @ColumnComment("项目线路ID")
    @TableField("line_id")
    private Integer lineId;

    /**
     * 分配给用户的手机号码
     */
    @ColumnComment("手机号码")
    @TableField("phone_number")
    private String phoneNumber;

    /**
     * 平台手机码id
     */
    @ColumnComment("平台手机码id")
    @TableField("api_phone_id")
    private String apiPhoneId;

    /**
     * 获取到的验证码内容（如果已获取成功）
     */
    @ColumnComment("验证码内容")
    @TableField("code")
    private String code;

    /**
     * 状态：0=已分配待取码, 1=取码中, 2=取码成功, 3=取码超时, 4=号码无效
     */
    @ColumnComment("状态(0=待取码, 1=取码中, 2=成功, 3=超时, 4=无效)")
    @TableField("status")
    @DefaultValue("0")
    private Integer status;

    /**
     * 扣费状态(0=未扣费, 1=已扣费,2=已退款)
     */
    @ColumnComment("扣费状态(0=未扣费, 1=已扣费,2=已退款)")
    @TableField("charged")
    @DefaultValue("0")
    private Integer charged;

    /**
     * 此次取号扣费金额
     */
    @ColumnComment("扣费金额")
    @TableField("price")
    private BigDecimal price;

    /**
     * 用户取号前余额
     */
    @ColumnComment("变动前余额")
    @TableField("balance_before")
    private BigDecimal balanceBefore;

    /**
     * 错误信息
     */
    @TableField("error_info")
    @ColumnComment("错误信息")
    @ColumnType("TEXT")
    private String errorInfo;



    /**
     * 用户取号后余额
     */
    @ColumnComment("变动后余额")
    @TableField("balance_after")
    private BigDecimal balanceAfter;

    /**
     * 用户取号时间（号码分配给用户的时间）
     */
    @ColumnComment("取号时间")
    @TableField("get_number_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime getNumberTime;

    /**
     * 开始取码时间（后台线程开始请求验证码的时间）
     */
    @ColumnComment("开始取码时间")
    @TableField("start_code_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startCodeTime;

    /**
     * 取码完成时间（获取到验证码或确定超时的时间）
     */
    @ColumnComment("取码完成时间")
    @TableField("code_received_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime codeReceivedTime;

    /**
     * 项目成本价（平台获取号码的成本）
     */
    @ColumnComment("项目成本价")
    @TableField("cost_price")
    private BigDecimal costPrice;

    /**
     * 备注
     */
    @ColumnComment("备注")
    @TableField("remark")
    @ColumnType("TEXT")
    private String remark;

    /**
     * 项目名称
     */
    @ColumnComment("项目名称")
    @TableField("project_name")
    @DefaultValue("'无'")
    private String projectName;

    @TableField("is_released")
    @ColumnComment("该号码是否释放")
    @DefaultValue("0")
    private Integer isReleased; // 0-未释放，1-已释放
}