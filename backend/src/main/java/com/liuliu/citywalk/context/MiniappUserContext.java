package com.liuliu.citywalk.context;

public final class MiniappUserContext {

    private MiniappUserContext() {
    }

    public static void setCurrentUserId(Long userId) {
        BaseContext.setCurrentUserId(userId);
    }

    public static Long getCurrentUserId() {
        Long currentUserId = BaseContext.getCurrentUserId();
        return currentUserId != null ? currentUserId : 1L;
    }

    public static void clear() {
        BaseContext.clear();
    }
}
