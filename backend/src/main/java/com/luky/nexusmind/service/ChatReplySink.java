package com.luky.nexusmind.service;

/** Transport-neutral destination for generated chat events. */
public interface ChatReplySink {
    String id();
    void send(String payload) throws Exception;
}
