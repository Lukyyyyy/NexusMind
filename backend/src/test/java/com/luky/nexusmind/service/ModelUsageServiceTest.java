package com.luky.nexusmind.service;

import com.luky.nexusmind.model.AiModelType;
import com.luky.nexusmind.model.ModelPricingRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ModelUsageServiceTest {
    @Test
    void deepSeekUsesOffPeakOnWeekendsAndOutsideWeekdayWindows() {
        assertTrue(ModelUsageService.isDeepSeekOffPeak(at("2026-09-05T10:00:00")));
        assertTrue(ModelUsageService.isDeepSeekOffPeak(at("2026-09-07T12:00:00")));
        assertFalse(ModelUsageService.isDeepSeekOffPeak(at("2026-09-07T09:00:00")));
        assertFalse(ModelUsageService.isDeepSeekOffPeak(at("2026-09-07T14:00:00")));
        assertTrue(ModelUsageService.isDeepSeekOffPeak(at("2026-09-07T18:00:00")));

        ModelPricingRule rule = new ModelPricingRule();
        rule.setModelName("deepseek-v4-flash"); rule.setModelType(AiModelType.LLM);
        rule.setInputPrice(new BigDecimal("3")); rule.setCacheHitPrice(new BigDecimal("0.1")); rule.setOutputPrice(new BigDecimal("9"));
        rule.setOffPeakInputPrice(new BigDecimal("1.5")); rule.setOffPeakCacheHitPrice(new BigDecimal("0.05")); rule.setOffPeakOutputPrice(new BigDecimal("4.5"));
        assertEquals(new BigDecimal("1.5"), ModelUsageService.pricesAt(rule, at("2026-09-06T14:00:00")).input());
        assertEquals(new BigDecimal("3"), ModelUsageService.pricesAt(rule, at("2026-09-07T10:00:00")).input());
    }

    @Test
    void reservationShrinksOutputBudgetToAffordableTokens() {
        assertEquals(1110, ModelUsageService.affordableOutputTokens(
                new BigDecimal("0.01"), new BigDecimal("0.000006"), new BigDecimal("9"), 2000));
        assertEquals(2000, ModelUsageService.affordableOutputTokens(
                new BigDecimal("1"), BigDecimal.ZERO, BigDecimal.ZERO, 2000));
    }

    private static ZonedDateTime at(String value) { return ZonedDateTime.parse(value + "+08:00[Asia/Shanghai]"); }
}
