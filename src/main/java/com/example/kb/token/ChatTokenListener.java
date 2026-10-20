package com.example.kb.token;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ChatTokenListener implements ChatModelListener {

    private static final Logger log = LoggerFactory.getLogger(ChatTokenListener.class);

    @Override
    public void onRequest(ChatModelRequestContext ctx) {
        String reqId = KbRequestContext.current();
        if (reqId != null) {
            ctx.attributes().put("kb_req_id", reqId);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext ctx) {
        TokenUsage usage = ctx.response().tokenUsage();
        if (usage == null) {
            return;
        }

        int prompt = usage.inputTokenCount() == null ? 0 : usage.inputTokenCount();
        int completion = usage.outputTokenCount() == null ? 0 : usage.outputTokenCount();

        Object rid = ctx.attributes().get("kb_req_id");
        if (rid != null) {
            TokenUsageAggregator.addChat(rid.toString(), prompt, completion);
        }

        log.debug("[CHAT-TOKEN] prompt={} completion={} total={}",
                prompt, completion, prompt + completion);
    }

    @Override
    public void onError(ChatModelErrorContext ctx) {
        log.warn("[CHAT-TOKEN] error={}", ctx.error().getMessage());
    }
}