package com.substation.common.auth;

import com.alibaba.fastjson2.JSON;

import java.util.Map;

/** 认证 API 统一 JSON 响应 */
final class AuthResponses {

    private AuthResponses() {
    }

    static String unauthorized() {
        return JSON.toJSONString(Map.of("success", false, "error", "请先登录"));
    }

    static String kicked() {
        return JSON.toJSONString(Map.of(
                "success", false,
                "error", SessionManager.KICKED_MESSAGE,
                "kicked", true));
    }
}
