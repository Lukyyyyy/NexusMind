package com.luky.nexusmind.service;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public final class WebSocketChatReplySink implements ChatReplySink {
    private final WebSocketSession session;

    public WebSocketChatReplySink(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public String id() {
        return session.getId();
    }

    @Override
    public void send(String payload) throws Exception {
        synchronized (session) {
            if (session.isOpen()) session.sendMessage(new TextMessage(payload));
        }
    }
}
