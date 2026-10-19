package com.taskapp.common.error;

/** Test access to package-private helpers of {@link GlobalExceptionHandler}. */
public final class GlobalExceptionHandlerAccess {

    private GlobalExceptionHandlerAccess() {
    }

    public static boolean isListForeignKey(Throwable e) {
        return GlobalExceptionHandler.isListForeignKey(e);
    }
}
