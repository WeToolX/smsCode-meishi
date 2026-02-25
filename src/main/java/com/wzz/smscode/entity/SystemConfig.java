package com.wzz.smscode.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wzz.smscode.annotation.ColumnComment;
import com.wzz.smscode.annotation.DefaultValue;
import com.wzz.smscode.annotation.TableComment;
import com.wzz.smscode.enums.RequestType;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 系统配置表实体类
 * <p>
 * 存储全局配置和控制开关，将所有配置作为单行记录保存。
 * 注意：此类未继承BaseEntity，因为它采用固定主键(config_id=1)，而非自增ID。
 * </p>
 * 对应数据库表：system_config
 */
@Data
@TableName("system_config")
@TableComment("系统配置表")
public class SystemConfig implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 主键，固定为 1（全局唯一配置项）
     */
    @ColumnComment("主键ID (固定为1)")
    @TableId(value = "config_id", type = IdType.INPUT) // INPUT类型表示该ID由用户手动设置，非自增
    private Integer configId;

    /**
     * 筛选号码的外部API接口 URL
     */
    @ColumnComment("号码筛选API的URL")
    @TableField("filter_api_url")
    private String filterApiUrl;

    /**
     * 筛选号码的api的接口请求参数类型
     * - PARAM: 参数将以 a=1&b=2 的形式附加到URL或表单中<br/>
     * - JSON: 参数将以 JSON 格式放在请求体中发送
     */
    @ColumnComment("筛选号码的api的接口请求参数类型")
    @TableField("select_number_api_reauest_type")
    @DefaultValue("'PARAM'")
    private RequestType selectNumberApiReauestType;

    /**
     * 筛选号码的api的接口请求方法
     */
    @ColumnComment("筛选号码的api的接口请求方法")
    @TableField("select_number_api_route_method")
    @DefaultValue("'GET'")
    private String selectNumberApiRouteMethod;

    /**
     * 筛选号码的api的接口请求字段 输入请求的参数，可使用 %s 作为手机号占位符
     */
    @ColumnComment("筛选号码的api的接口请求字段值")
    @TableField("select_number_api_reauest_value")
    private String selectNumberApiReauestValue;

    /**
     * 筛选号码的api的接口数据返回字段 哪个字段代表筛选结果。例如：data.status
     */
    @ColumnComment("筛选号码的api的接口数据返回字段")
    @TableField("response_select_number_api_field")
    private String responseSelectNumberApiField;

    /**
     * 筛选API所需的鉴权密钥或卡密
     */
    @ColumnComment("号码筛选API的密钥")
    @TableField("filter_api_key")
    private String filterApiKey;

    /**
     * 是否开启封禁模式标志。1 表示开启，0 表示关闭。
     */
    @ColumnComment("是否开启封禁模式(0=关闭, 1=开启)")
    @TableField("enable_ban_mode")
    @DefaultValue("0")
    private Integer enableBanMode;

    /**
     * 是否开启号码筛选功能 (0=关闭, 1=开启)
     */
    @ColumnComment("是否开启号码筛选功能")
    @TableField("enable_number_filtering")
    @DefaultValue("0")
    private Boolean enableNumberFiltering;

    /**
     * 24小时最低回码率限制值
     */
    @ColumnComment("24小时最低回码率")
    @TableField("min_24h_code_rate")
    @DefaultValue("0.0")
    private Double min24hCodeRate;

    /**
     * 系统通知
     */
    @ColumnComment("系统通知")
    @TableField("system_notice")
    @DefaultValue("'系统通知'")
    private String systemNotice;

    /**
     * 用户能够删除几天前的数据(账单和号码记录通用)
     */
    @ColumnComment("用户能够删除几天前的数据")
    @TableField("user_delete_data_day")
    @DefaultValue("3")
    private String userDeleteDataDay;
}
