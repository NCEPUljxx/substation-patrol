package com.substation.common.auth.model;

/** 会话校验结果 */
public record SessionValidation(Status status, SessionInfo session) {

    public enum Status {
        VALID,
        NOT_FOUND,
        KICKED
    }

    public static SessionValidation valid(SessionInfo session) {
        return new SessionValidation(Status.VALID, session);
    }

    public static SessionValidation notFound() {
        return new SessionValidation(Status.NOT_FOUND, null);
    }

    public static SessionValidation kicked() {
        return new SessionValidation(Status.KICKED, null);
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    public boolean isKicked() {
        return status == Status.KICKED;
    }
}
