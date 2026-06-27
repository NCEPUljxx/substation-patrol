package com.substation.common.auth.model;

/** 会话信息，存储在 Redis auth:session:{token} key 中 */
public record SessionInfo(String username, String role, long loginAt, long lastAccess) {}
