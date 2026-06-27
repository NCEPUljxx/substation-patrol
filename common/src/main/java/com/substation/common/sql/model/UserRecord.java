package com.substation.common.sql.model;

/** 用户表行，不含密码（安全原则：查询时不返回密码） */
public record UserRecord(String username, String role, String displayName,
                          String status, String createdAt) {}
