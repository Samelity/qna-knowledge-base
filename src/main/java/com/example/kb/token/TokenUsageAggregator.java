package com.example.kb.token;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class TokenUsageAggregator {

    private static final ConcurrentHashMap<String, Bucket> BUCKETS = new ConcurrentHashMap<>();

    public static String newRequestId() {
        String id = UUID.randomUUID().toString();
        BUCKETS.put(id, new Bucket());
        return id;
    }

    public static void addChat(String reqId, int prompt, int completion) {
        Bucket b = BUCKETS.get(reqId);
        if (b != null) {
            b.prompt.addAndGet(prompt);
            b.completion.addAndGet(completion);
        }
    }

    public static Bucket finish(String reqId) {
        Bucket b = BUCKETS.remove(reqId);
        return b == null ? new Bucket() : b;
    }

    public static class Bucket {
        final AtomicLong prompt = new AtomicLong();
        final AtomicLong completion = new AtomicLong();

        public long promptTokens() {
            return prompt.get();
        }

        public long completionTokens() {
            return completion.get();
        }

        public long totalTokens() {
            return prompt.get() + completion.get();
        }
    }
}