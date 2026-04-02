package com.liuliu.citywalk.context;

public final class MiniappUserContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private MiniappUserContext() {
    }

    public static void setCurrentUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Long getCurrentUserId() {
        Long currentUserId = CURRENT_USER_ID.get();
        return currentUserId != null ? currentUserId : 1L;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
