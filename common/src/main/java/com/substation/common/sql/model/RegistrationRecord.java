package com.substation.common.sql.model;

/** 注册申请表行 */
public record RegistrationRecord(int id, String username, String role, String displayName,
                                  String status, String reviewedBy, String reviewTime,
                                  String createdAt) {}
