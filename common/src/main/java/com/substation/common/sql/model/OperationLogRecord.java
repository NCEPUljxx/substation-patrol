package com.substation.common.sql.model;

/** 操作日志表行 */
public record OperationLogRecord(int id, String username, String action, String target,
                                  String details, String createdAt) {}
