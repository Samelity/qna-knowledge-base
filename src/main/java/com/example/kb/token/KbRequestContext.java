package com.example.kb.token;

public final class KbRequestContext {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    public static void set(String id) {
        HOLDER.set(id);
    }

    public static String current() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}