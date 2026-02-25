package com.wzz.smscode.dto.CreatDTO;

import lombok.Data;
import java.math.BigDecimal;
import java.util.List; // 引入 List

/**
 * 用户创建/编辑 DTO
 */
@Data
public class UserCreateDTO {

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户ID（编辑时用，新建时可不传）
     */
    private Long userId;

    /**
     * 密码
     */
    private String password;

    /**
     * 初始充值金额
     */
    private BigDecimal initialBalance;

    /**
     * 禁用的项目线路列表 (前端传 List，后端转 String 存库)
     * 格式示例: ["1001-1", "1002-5"]
     */
    private List<String> blacklistedProjects;

    /**
     * 是否赋予代理权限
     */
    private Boolean isAgent;

    /**
     * 上级用户ID（代理ID或管理员ID）
     */
    private Long parentId;
}
