package com.substation.common.auth.model;

/** 当前用户信息（不含密码） */
public record UserInfo(String username, String role, String displayName) {}
