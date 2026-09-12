package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.AiModelOwnerType;
import com.luky.nexusmind.model.AiModelType;
import com.luky.nexusmind.model.User;
import com.luky.nexusmind.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
class ModelConfigServiceTest {
    @Test
    void acceptsBaseUrlsAndFullEndpointUrls() {
        assertAll(
                () -> assertEquals("https://open.bigmodel.cn/api/paas/v4",
                        ModelConfigService.normalizeBaseUrl(
                                "https://open.bigmodel.cn/api/paas/v4/chat/completions/", AiModelType.LLM)),
                () -> assertEquals("https://open.bigmodel.cn/api/paas/v4",
                        ModelConfigService.normalizeBaseUrl(
                                "https://open.bigmodel.cn/api/paas/v4/embeddings", AiModelType.EMBEDDING)),
                () -> assertEquals("https://open.bigmodel.cn/api/paas/v4",
                        ModelConfigService.normalizeBaseUrl(
                                "https://open.bigmodel.cn/api/paas/v4", AiModelType.LLM)),
                () -> assertEquals("https://dashscope.aliyuncs.com",
                        ModelConfigService.normalizeBaseUrl(
                                "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank/",
                                AiModelType.RERANK)),
                () -> assertEquals("https://dashscope.aliyuncs.com",
                        ModelConfigService.normalizeBaseUrl(
                                "https://dashscope.aliyuncs.com", AiModelType.RERANK)));
    }

    @Test
    void rejectsEmbeddingLimitsOutsideSupportedRanges() {
        User user = new User();
        user.setRole(User.Role.USER);
        UserRepository users = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> method.getName().equals("findByUsername") ? Optional.of(user) : null);
        ModelConfigService service = new ModelConfigService(
                null, null, null, users,
                null, null,
                "", "", "", "", "", "", 10, false, 10, 2048, 30);

        assertAll(
                () -> assertBadRequest(service, request(0, 10)),
                () -> assertBadRequest(service, request(11, 10)),
                () -> assertBadRequest(service, request(10, 0)),
                () -> assertBadRequest(service, request(10, 31)));
    }

    @Test
    void userModelCannotTargetInternalNetworkOrNonHttpSchemes() throws Exception {
        User user = new User();
        user.setRole(User.Role.USER);
        UserRepository users = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(), new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> method.getName().equals("findByUsername") ? Optional.of(user) : null);
        ModelConfigService service = new ModelConfigService(null, null, null, users, null, null,
                "", "", "", "", "", "", 10, false, 10, 2048, 30);
        for (String url : new String[]{"http://127.0.0.1:8080", "http://169.254.169.254/latest",
                "http://[::1]/", "http://[fd00::1]/", "file:///etc/passwd", "http://user@api.example.com"}) {
            var request = new ModelConfigService.ModelConfigRequest(AiModelOwnerType.USER, AiModelType.EMBEDDING,
                    "embedding", null, url, "key", "model", true, false,
                    null, null, null, null, 2048, 1, 1, null, null, null);
            assertBadRequest(service, request);
        }
        assertFalse(ModelConfigService.isPublicAddress(InetAddress.getByName("10.0.0.1")));
        assertFalse(ModelConfigService.isPublicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(ModelConfigService.isPublicAddress(InetAddress.getByName("fd00::1")));
        assertTrue(ModelConfigService.isPublicAddress(InetAddress.getByName("8.8.8.8")));
    }

    private static void assertBadRequest(ModelConfigService service, ModelConfigService.ModelConfigRequest request) {
        CustomException exception = assertThrows(CustomException.class, () -> service.createConfig("user", request));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatus());
    }

    private static ModelConfigService.ModelConfigRequest request(int batchSize, int maxConcurrency) {
        return new ModelConfigService.ModelConfigRequest(
                AiModelOwnerType.USER, AiModelType.EMBEDDING, "embedding", null,
                "https://example.com", "key", "model", true, false,
                null, null, null, null, 2048, batchSize, maxConcurrency, null, null, null);
    }

    private static ModelConfigService.ModelConfigRequest rerankRequest(Integer topN, Double fps) {
        return new ModelConfigService.ModelConfigRequest(
                AiModelOwnerType.USER, AiModelType.RERANK, "rerank", null,
                "https://dashscope.aliyuncs.com", "key", "qwen3-vl-rerank", true, false,
                null, null, null, null, null, null, null, null, topN, fps);
    }

    @Test
    void rejectsRerankParamsOutsideSupportedRanges() {
        User user = new User();
        user.setRole(User.Role.USER);
        UserRepository users = (UserRepository) Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> method.getName().equals("findByUsername") ? Optional.of(user) : null);
        ModelConfigService service = new ModelConfigService(
                null, null, null, users,
                null, null,
                "", "", "", "", "", "", 10, false, 10, 2048, 30);

        assertAll(
                () -> assertBadRequest(service, rerankRequest(9, null)),
                () -> assertBadRequest(service, rerankRequest(31, null)),
                () -> assertBadRequest(service, rerankRequest(101, null)),
                () -> assertBadRequest(service, rerankRequest(null, 1.5d)),
                () -> assertBadRequest(service, rerankRequest(null, -0.1d)));
    }
}
