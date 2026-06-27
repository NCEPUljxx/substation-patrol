package com.substation.common.auth.model;

import com.alibaba.fastjson2.JSON;

/** 登录响应 */
public record LoginResponse(boolean success, String token, String username, String role,
                             String displayName, String error) {

    public static LoginResponse ok(String token, String username, String role, String displayName) {
        return new LoginResponse(true, token, username, role, displayName, null);
    }

    public static LoginResponse fail(String error) {
        return new LoginResponse(false, null, null, null, null, error);
    }

    public String toJson() {
        return JSON.toJSONString(this);
    }
}
