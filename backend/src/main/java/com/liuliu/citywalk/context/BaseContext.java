package com.liuliu.citywalk.context;

public final class BaseContext {

    private static final ThreadLocal<Long> CURRENT_USER_ID = new ThreadLocal<>();

    private BaseContext() {
    }

    public static void setCurrentUserId(Long userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static Long getCurrentUserId() {
        return CURRENT_USER_ID.get();
    }

    public static Long requireCurrentUserId() {
        Long currentUserId = CURRENT_USER_ID.get();
        if (currentUserId == null || currentUserId <= 0) {
            throw new IllegalStateException("login_required");
        }
        return currentUserId;
    }

    public static void clear() {
        CURRENT_USER_ID.remove();
    }
}
