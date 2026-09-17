package com.luky.nexusmind.im.channel;

import com.luky.nexusmind.im.model.ImChannelType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 渠道类型 -> Adapter 注册表，由 Spring 收集所有 ImChannelAdapter 实现。 */
@Component
public class ImChannelRegistry {

    private final Map<ImChannelType, ImChannelAdapter> adapters = new EnumMap<>(ImChannelType.class);

    public ImChannelRegistry(List<ImChannelAdapter> adapterList) {
        for (ImChannelAdapter adapter : adapterList) {
            adapters.put(adapter.channelType(), adapter);
        }
    }

    public ImChannelAdapter require(ImChannelType type) {
        ImChannelAdapter adapter = adapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("渠道尚未实现适配器: " + type);
        }
        return adapter;
    }

    public boolean supports(ImChannelType type) {
        return adapters.containsKey(type);
    }
}
