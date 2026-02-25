package com.wzz.smscode.dto.update;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class UserUpdateDtoByUser {
    /*
    id
     */
    private Long id;
    /**
     * 用户名
     */
    private String username;
    /**
     * 密码
     */
    private String  password;
    /**
     * 否具有代理权限。0表示普通用户，1表示代理用户。
     */
    @JsonProperty("isAgent")
    private boolean isAgent;
    /**、
     * 用户状态（0=正常，1=冻结/禁用等）
     */
    private Integer status;

    // 变更黑名单
    private List<String> blacklistedProjects;
}
