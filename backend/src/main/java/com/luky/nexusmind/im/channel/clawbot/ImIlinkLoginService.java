package com.luky.nexusmind.im.channel.clawbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * iLink Bot 扫码登录服务。
 *
 * 流程：get_bot_qrcode 生成二维码 -> 用户微信扫码确认 -> get_qrcode_status 轮询至 confirmed
 * -> 返回 bot_token / ilink_bot_id。管理员把 bot_token 保存到渠道配置后即可直连 iLink 收发消息。
 *
 * 用法（管理员端点 POST /api/v1/im/clawbot/login/start 与 /login/poll，或直接 curl）：
 * 1. start：返回 qrcode 与 qrcodeUrl，管理员用微信扫码；
 * 2. poll：携带 qrcode 轮询状态，confirmed 时返回 botToken。
 */
@Service
public class ImIlinkLoginService {

    public record LoginStart(String qrcode, String qrcodeImageUrl, int expireSeconds) {
    }

    public record LoginPoll(String status, String botToken, String botId, String baseUrl) {
    }

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public ImIlinkLoginService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LoginStart startLogin(String baseUrl) throws Exception {
        JsonNode resp = getJson(baseUrl, "ilink/bot/get_bot_qrcode?bot_type=3");
        if (resp.path("ret").asInt(-1) != 0) {
            throw new IllegalStateException("iLink 获取二维码失败 ret=" + resp.path("ret").asInt());
        }
        String qrcodeUrl = resp.path("qrcode_img_content").asText("");
        if (qrcodeUrl.isBlank()) {
            qrcodeUrl = resp.path("qrcode_url").asText("");
        }
        if (qrcodeUrl.isBlank()) {
            qrcodeUrl = resp.path("img_url").asText("");
        }
        return new LoginStart(
                resp.path("qrcode").asText(""),
                qrcodeUrl,
                resp.path("expire_time").asInt(120));
    }

    public LoginPoll pollStatus(String baseUrl, String qrcode) throws Exception {
        JsonNode resp = getJson(baseUrl, "ilink/bot/get_qrcode_status?qrcode=" + urlEncode(qrcode));
        String status = resp.path("status").asText("wait");
        if ("confirmed".equals(status)) {
            return new LoginPoll(status,
                    resp.path("bot_token").asText(""),
                    resp.path("ilink_bot_id").asText(""),
                    resp.path("baseurl").asText(ClawbotWeChatAdapter.DEFAULT_BASE_URL));
        }
        return new LoginPoll(status, "", "", "");
    }

    private JsonNode getJson(String baseUrl, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("iLink " + path + " HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /** 供管理端点把 token 写回渠道配置凭据（复用 ImCryptoService 加密）。 */
    public Map<String, Object> buildCredentials(String botToken, String botId) {
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("botToken", botToken);
        if (botId != null && !botId.isBlank()) credentials.put("botId", botId);
        return credentials;
    }

}
