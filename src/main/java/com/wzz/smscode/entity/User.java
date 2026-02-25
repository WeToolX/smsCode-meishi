package com.wzz.smscode.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.wzz.smscode.annotation.*;
import com.wzz.smscode.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 用户表实体类
 * <p>
 * 存储系统中所有用户的信息，包括普通用户、代理和管理员。
 * 认证方式采用 用户ID + 密码。
 * </p>
 * 对应数据库表：user
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user")
@TableComment("用户表")
//@ForeignKey(
//        name = "fk_user_parent_id",               // 外键约束名称
//        columns = {"parent_id"},                 // 当前表的列
//        referenceEntity = User.class,              // 自我引用，指向 User 实体
//        referencedColumns = {"id"},           // 引用当前表的 user_id 列
//        onDelete = ForeignKeyAction.SET_DEFAULT,      // 当上级代理被删除时，下级用户的parent_id设为NULL
//        onUpdate = ForeignKeyAction.RESTRICT       // 不允许更新上级用户的ID
//)
@Index(name = "idx_parent_id", columns = {"parent_id"}, comment = "上级用户ID索引，加速查询下级")
public class User extends BaseEntity {


    /**
     *用户名
     */
    @TableField("user_name")
    @ColumnComment("用户名")
    private String userName;

    /**
     * 密码（存储为加密哈希值，用于登录验证）
     */
    @ColumnComment("密码（加密哈希值）")
    @TableField("password")
    @JsonIgnore
    private String password;

    /**
     * 用户账户余额
     */
    @ColumnComment("账户余额")
    @TableField("balance")
    @DefaultValue("0.00")
    private BigDecimal balance;

    /**
     * 项目价格配置的 JSON 字符串。(无效参数)
     * 例如：{"id0001-1": 5.0, "id0002-1": 3.5}
     */
    @ColumnComment("项目价格配置JSON(无效参数)")
    @TableField("project_prices")
    private String projectPrices; // 数据库中建议使用 TEXT 或 JSON 类型
//    private List<ProjectPriceDTO> projectPrices;


    /**
     * 用户状态（0=正常，1=冻结/禁用等）
     */
    @ColumnComment("用户状态（0=正常，1=冻结/禁用）")
    @TableField("status")
    @DefaultValue("0")
    private Integer status;

    /**
     * 最近24小时取号次数
     */
    @ColumnComment("最近24小时取号次数")
    @TableField("daily_get_count")
    @DefaultValue("0")
    private Integer dailyGetCount;

    /**
     * 最近24小时成功取码（获取到验证码）次数
     */
    @ColumnComment("最近24小时成功取码次数")
    @TableField("daily_code_count")
    @DefaultValue("0")
    private Integer dailyCodeCount;

    /**
     * 最近24小时回码率
     */
    @ColumnComment("最近24小时回码率")
    @TableField("daily_code_rate")
    @DefaultValue("0.0")
    private Double dailyCodeRate;

    /**
     * 总取号次数
     */
    @ColumnComment("总取号次数")
    @TableField("total_get_count")
    @DefaultValue("0")
    private Integer totalGetCount;

    /**
     * 总取码次数
     */
    @ColumnComment("总取码次数")
    @TableField("total_code_count")
    @DefaultValue("0")
    private Integer totalCodeCount;

    /**
     * 总回码率
     */
    @ColumnComment("总回码率")
    @TableField("total_code_rate")
    @DefaultValue("0.0")
    private Double totalCodeRate;

    /**
     * 上级用户ID。顶级管理员该值可为空或0。
     */
    @ColumnComment("上级用户ID")
    @TableField("parent_id")
    @DefaultValue("0")
    private Long parentId;

    /**
     * 是否具有代理权限。0表示普通用户，1表示代理用户。
     */
    @ColumnComment("是否代理（0=否, 1=是）")
    @TableField("is_agent")
    @DefaultValue("0")
    private Integer isAgent;

    /**
     * 用户最后登录时间
     */
    @ColumnComment("最近登录时间")
    @TableField("last_login_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastLoginTime;

    /**
     * 上级用户名（非数据库字段，仅用于展示）
     */
    @TableField(exist = false)
    private String parentName;

    /**
     * 项目线路黑名单
     * 存储格式示例: "projectId1-lineId1,projectId2-lineId2"
     * 如果存在于此列表中，则用户无法使用该项目线路
     */
    @ColumnComment("项目线路黑名单(pid-lid,pid-lid)")
    @TableField("project_blacklist")
    @ColumnType("TEXT")
    private String projectBlacklist;

}
