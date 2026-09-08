package com.luky.nexusmind.service;

import com.luky.nexusmind.exception.CustomException;
import com.luky.nexusmind.model.*;
import com.luky.nexusmind.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

@Service
public class ModelUsageService {
    private static final ZoneId BILLING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
    private static final int MIN_LLM_OUTPUT_TOKENS = 512;
    private static final int MONEY_SCALE = 8;

    private final ModelPricingRuleRepository pricingRepository;
    private final AiModelConfigRepository configRepository;
    private final UserModelQuotaRepository quotaRepository;
    private final ModelUsageRecordRepository usageRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public ModelUsageService(ModelPricingRuleRepository pricingRepository,
                             AiModelConfigRepository configRepository,
                             UserModelQuotaRepository quotaRepository,
                             ModelUsageRecordRepository usageRepository,
                             UserRepository userRepository,
                             AuditService auditService) {
        this.pricingRepository = pricingRepository;
        this.configRepository = configRepository;
        this.quotaRepository = quotaRepository;
        this.usageRepository = usageRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Reservation reserve(ModelConfigService.ResolvedModelConfig config, String userKey,
                               String scenario, Object input, int requestedOutputTokens) {
        if (config.ownerType() == AiModelOwnerType.USER) return Reservation.unlimited(requestedOutputTokens);
        User user = requireUser(userKey);
        ModelPricingRule rule = pricingRepository.findByModelName(config.modelName())
                .filter(ModelPricingRule::isEnabled)
                .orElseThrow(() -> new CustomException("系统模型未启用计价规则，暂时无法调用", HttpStatus.BAD_REQUEST));
        Prices prices = pricesAt(rule, ZonedDateTime.now(BILLING_ZONE));
        long estimatedInput = estimateTokens(input);
        int maxOutput = Math.max(0, requestedOutputTokens);
        UserModelQuota quota = quotaForUpdate(user.getId());
        refreshPeriod(quota);
        releaseStaleReservations(quota);

        BigDecimal inputCost = tokenCost(estimatedInput, prices.input());
        int allowedOutput = maxOutput;
        if (config.modelType() == AiModelType.LLM) {
            BigDecimal minimum = inputCost.add(tokenCost(MIN_LLM_OUTPUT_TOKENS, prices.output()));
            if (quota.getRemainingAmount().compareTo(minimum) < 0) {
                throw new CustomException("模型额度不足，至少需保留 512 个输出 Token", HttpStatus.PAYMENT_REQUIRED);
            }
            allowedOutput = affordableOutputTokens(quota.getRemainingAmount(), inputCost, prices.output(), maxOutput);
        } else if (quota.getRemainingAmount().compareTo(inputCost) < 0) {
            throw new CustomException("模型额度不足", HttpStatus.PAYMENT_REQUIRED);
        }

        BigDecimal reserved = inputCost.add(tokenCost(allowedOutput, prices.output())).setScale(MONEY_SCALE, RoundingMode.UP);
        quota.setRemainingAmount(quota.getRemainingAmount().subtract(reserved));
        quotaRepository.save(quota);

        ModelUsageRecord record = new ModelUsageRecord();
        record.setUserId(user.getId());
        record.setUsername(user.getUsername());
        record.setModelName(config.modelName());
        record.setModelType(config.modelType());
        record.setScenario(scenario == null || scenario.isBlank() ? "model.call" : scenario);
        record.setStatus("PENDING");
        record.setInputTokens(estimatedInput);
        record.setInputPrice(prices.input());
        record.setCacheHitPrice(prices.cacheHit());
        record.setOutputPrice(prices.output());
        record.setReservedAmount(reserved);
        usageRepository.save(record);
        return new Reservation(record.getId(), allowedOutput, estimatedInput, false);
    }

    @Transactional
    public void settle(Reservation reservation, long inputTokens, long cacheHitTokens, long outputTokens) {
        if (reservation == null || reservation.unlimited()) return;
        ModelUsageRecord record = usageRepository.findById(reservation.recordId()).orElse(null);
        if (record == null || !"PENDING".equals(record.getStatus())) return;
        long input = Math.max(0, inputTokens);
        long cached = Math.min(input, Math.max(0, cacheHitTokens));
        long uncached = input - cached;
        BigDecimal actual = tokenCost(uncached, record.getInputPrice())
                .add(tokenCost(cached, record.getCacheHitPrice()))
                .add(tokenCost(Math.max(0, outputTokens), record.getOutputPrice()))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (actual.compareTo(record.getReservedAmount()) > 0) actual = record.getReservedAmount();
        UserModelQuota quota = quotaForUpdate(record.getUserId());
        boolean reservationFromPreviousPeriod = quota.isMonthlyReset() && !sameMonth(record, YearMonth.now(BILLING_ZONE));
        refreshPeriod(quota);
        if (!reservationFromPreviousPeriod)
            quota.setRemainingAmount(quota.getRemainingAmount().add(record.getReservedAmount().subtract(actual)));
        quotaRepository.save(quota);
        record.setInputTokens(input);
        record.setCacheHitTokens(cached);
        record.setOutputTokens(Math.max(0, outputTokens));
        record.setAmount(actual);
        record.setStatus("SETTLED");
        record.setSettledAt(LocalDateTime.now(BILLING_ZONE));
        usageRepository.save(record);
    }

    public void settleEstimated(Reservation reservation, long outputChars) {
        if (reservation == null) return;
        settle(reservation, reservation.estimatedInputTokens(), 0, Math.max(0, outputChars));
    }

    @Transactional
    public void release(Reservation reservation) {
        if (reservation == null || reservation.unlimited()) return;
        ModelUsageRecord record = usageRepository.findById(reservation.recordId()).orElse(null);
        if (record == null || !"PENDING".equals(record.getStatus())) return;
        UserModelQuota quota = quotaForUpdate(record.getUserId());
        boolean reservationFromPreviousPeriod = quota.isMonthlyReset() && !sameMonth(record, YearMonth.now(BILLING_ZONE));
        refreshPeriod(quota);
        if (!reservationFromPreviousPeriod) quota.setRemainingAmount(quota.getRemainingAmount().add(record.getReservedAmount()));
        quotaRepository.save(quota);
        usageRepository.delete(record);
    }

    @Transactional
    public void updateQuota(User actor, Long targetUserId, BigDecimal monthlyLimit, boolean monthlyReset,
                            String reason, String ip) {
        requireSuper(actor);
        if (monthlyLimit == null || monthlyLimit.signum() < 0 || monthlyLimit.scale() > 2)
            throw new CustomException("额度须为不小于 0 的两位小数", HttpStatus.BAD_REQUEST);
        requireReason(reason);
        userRepository.findById(targetUserId)
                .orElseThrow(() -> new CustomException("用户不存在", HttpStatus.NOT_FOUND));
        UserModelQuota quota = quotaForUpdate(targetUserId);
        refreshPeriod(quota);
        releaseStaleReservations(quota);
        BigDecimal oldLimit = quota.getMonthlyLimit();
        boolean oldReset = quota.isMonthlyReset();
        LocalDateTime from = monthStart(YearMonth.now(BILLING_ZONE));
        BigDecimal spent = settled(usageRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                targetUserId, from, from.plusMonths(1))).stream().map(ModelUsageRecord::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal pending = usageRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                        targetUserId, from, from.plusMonths(1)).stream()
                .filter(v -> "PENDING".equals(v.getStatus())).map(ModelUsageRecord::getReservedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal normalizedLimit = monthlyLimit.setScale(MONEY_SCALE);
        quota.setMonthlyLimit(normalizedLimit);
        quota.setMonthlyReset(monthlyReset);
        // 单独切换月重置不改变当前余额；只有额度数值变化时才按本月已消费重新计算。
        if (oldLimit.compareTo(normalizedLimit) != 0)
            quota.setRemainingAmount(normalizedLimit.subtract(spent).subtract(pending).max(BigDecimal.ZERO).setScale(MONEY_SCALE));
        quotaRepository.save(quota);
        auditService.record(actor, "MODEL_QUOTA_UPDATED", targetUserId, null,
                String.format("月额度 %s→%s；月重置 %s→%s；原因：%s", money(oldLimit), money(monthlyLimit),
                        oldReset ? "开启" : "关闭", monthlyReset ? "开启" : "关闭", reason.trim()), ip);
    }

    @Transactional
    public void updatePricing(User actor, Long ruleId, PricingUpdate update, String reason, String ip) {
        requireSuper(actor);
        requireReason(reason);
        validatePrices(update);
        ModelPricingRule rule = pricingRepository.findById(ruleId)
                .orElseThrow(() -> new CustomException("计价规则不存在", HttpStatus.NOT_FOUND));
        String before = priceSummary(rule);
        rule.setEnabled(update.enabled());
        rule.setInputPrice(update.inputPrice()); rule.setCacheHitPrice(update.cacheHitPrice()); rule.setOutputPrice(update.outputPrice());
        rule.setOffPeakInputPrice(update.offPeakInputPrice()); rule.setOffPeakCacheHitPrice(update.offPeakCacheHitPrice());
        rule.setOffPeakOutputPrice(update.offPeakOutputPrice());
        pricingRepository.save(rule);
        auditService.record(actor, "MODEL_PRICING_UPDATED", null, null,
                "模型 " + rule.getModelName() + "；计价 " + before + "→" + priceSummary(rule) + "；原因：" + reason.trim(), ip);
    }

    @Transactional
    public void createPricing(User actor, String modelName, AiModelType modelType, PricingUpdate update, String reason, String ip) {
        requireSuper(actor);
        requireReason(reason);
        validatePrices(update);
        if (modelName == null || modelName.isBlank() || modelType == null
                || !configRepository.existsByOwnerTypeAndModelNameAndModelType(AiModelOwnerType.SYSTEM, modelName.trim(), modelType))
            throw new CustomException("系统模型不存在", HttpStatus.NOT_FOUND);
        if (pricingRepository.findByModelName(modelName.trim()).isPresent())
            throw new CustomException("该模型已存在计价规则", HttpStatus.CONFLICT);
        ModelPricingRule rule = new ModelPricingRule();
        rule.setModelName(modelName.trim());
        rule.setModelType(modelType);
        rule.setEnabled(update.enabled());
        rule.setInputPrice(update.inputPrice()); rule.setCacheHitPrice(update.cacheHitPrice()); rule.setOutputPrice(update.outputPrice());
        rule.setOffPeakInputPrice(update.offPeakInputPrice()); rule.setOffPeakCacheHitPrice(update.offPeakCacheHitPrice());
        rule.setOffPeakOutputPrice(update.offPeakOutputPrice());
        pricingRepository.save(rule);
        auditService.record(actor, "MODEL_PRICING_CREATED", null, null,
                "模型 " + rule.getModelName() + "；计价 " + priceSummary(rule) + "；原因：" + reason.trim(), ip);
    }

    @Transactional
    public Overview overview(User viewer, YearMonth month, Long userFilter, String modelFilter) {
        boolean superAdmin = viewer.getRole() == User.Role.SUPER_ADMIN;
        Long effectiveUser = superAdmin ? userFilter : viewer.getId();
        LocalDateTime from = monthStart(month);
        // ponytail: 月度数据先内存聚合；单月记录达到十万级时改为数据库 GROUP BY 与明细分页。
        List<ModelUsageRecord> records = effectiveUser == null
                ? usageRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, from.plusMonths(1))
                : usageRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(effectiveUser, from, from.plusMonths(1));
        records = settled(records).stream().filter(v -> modelFilter == null || modelFilter.isBlank() || modelFilter.equals(v.getModelName())).toList();

        List<User> users = superAdmin ? userRepository.findAll() : List.of(viewer);
        if (effectiveUser != null) users = users.stream().filter(u -> u.getId().equals(effectiveUser)).toList();
        List<UserSummary> userSummaries = new ArrayList<>();
        for (User user : users) {
            UserModelQuota quota = quotaForUpdate(user.getId());
            refreshPeriod(quota);
            releaseStaleReservations(quota);
            BigDecimal spent = records.stream().filter(v -> v.getUserId().equals(user.getId()))
                    .map(ModelUsageRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            userSummaries.add(new UserSummary(user.getId(), user.getUsername(), user.getDisplayName(),
                    quota.getMonthlyLimit(), spent, quota.getRemainingAmount(), quota.isMonthlyReset()));
        }
        userSummaries.sort(Comparator.comparing(UserSummary::spent).reversed());
        BigDecimal totalQuota = userSummaries.stream().map(UserSummary::quota).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalSpent = records.stream().map(ModelUsageRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemaining = userSummaries.stream().map(UserSummary::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<LocalDate, BigDecimal> trend = new TreeMap<>();
        Map<String, BigDecimal> byModel = new TreeMap<>();
        records.forEach(v -> {
            trend.merge(v.getCreatedAt().toLocalDate(), v.getAmount(), BigDecimal::add);
            byModel.merge(v.getModelName(), v.getAmount(), BigDecimal::add);
        });
        List<UsageItem> items = records.stream().limit(200).map(this::usageItem).toList();
        List<PricingItem> prices = pricingRepository.findAll().stream().map(this::pricingItem).toList();
        return new Overview(superAdmin, month.toString(), totalQuota, totalSpent, totalRemaining,
                trend.entrySet().stream().map(e -> new ChartPoint(e.getKey().toString(), e.getValue())).toList(),
                byModel.entrySet().stream().map(e -> new ChartPoint(e.getKey(), e.getValue())).toList(),
                userSummaries, items, prices);
    }

    private UserModelQuota quotaForUpdate(Long userId) {
        return quotaRepository.findForUpdateByUserId(userId).orElseGet(() -> {
            UserModelQuota quota = new UserModelQuota(); quota.setUserId(userId);
            quota.setPeriodMonth(YearMonth.now(BILLING_ZONE).atDay(1));
            return quotaRepository.save(quota);
        });
    }

    private void refreshPeriod(UserModelQuota quota) {
        LocalDate current = YearMonth.now(BILLING_ZONE).atDay(1);
        if (current.equals(quota.getPeriodMonth())) return;
        if (quota.isMonthlyReset()) quota.setRemainingAmount(quota.getMonthlyLimit());
        quota.setPeriodMonth(current);
    }

    private void releaseStaleReservations(UserModelQuota quota) {
        // 无需调度器：超过所有模型调用超时上限的遗留预占，在下一次访问时归还。
        List<ModelUsageRecord> stale = usageRepository.findByUserIdAndStatusAndCreatedAtBefore(
                quota.getUserId(), "PENDING", LocalDateTime.now(BILLING_ZONE).minusHours(1));
        YearMonth current = YearMonth.now(BILLING_ZONE);
        for (ModelUsageRecord record : stale) {
            if (!quota.isMonthlyReset() || sameMonth(record, current))
                quota.setRemainingAmount(quota.getRemainingAmount().add(record.getReservedAmount()));
        }
        if (!stale.isEmpty()) usageRepository.deleteAll(stale);
    }

    private User requireUser(String key) {
        if (key == null || key.isBlank()) throw new CustomException("模型调用缺少用户信息", HttpStatus.BAD_REQUEST);
        Optional<User> byName = userRepository.findByUsername(key);
        if (byName.isPresent()) return byName.get();
        try { return userRepository.findById(Long.parseLong(key)).orElseThrow(); }
        catch (Exception ignored) { throw new CustomException("模型调用用户不存在", HttpStatus.BAD_REQUEST); }
    }

    static Prices pricesAt(ModelPricingRule rule, ZonedDateTime time) {
        boolean offPeak = "deepseek-v4-flash".equals(rule.getModelName()) && isDeepSeekOffPeak(time);
        return offPeak ? new Prices(value(rule.getOffPeakInputPrice(), rule.getInputPrice()),
                value(rule.getOffPeakCacheHitPrice(), rule.getCacheHitPrice()),
                value(rule.getOffPeakOutputPrice(), rule.getOutputPrice()))
                : new Prices(rule.getInputPrice(), rule.getCacheHitPrice(), rule.getOutputPrice());
    }

    static boolean isDeepSeekOffPeak(ZonedDateTime time) {
        DayOfWeek day = time.withZoneSameInstant(BILLING_ZONE).getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) return true;
        LocalTime value = time.withZoneSameInstant(BILLING_ZONE).toLocalTime();
        return !(between(value, 9, 12) || between(value, 14, 18));
    }

    private static boolean between(LocalTime value, int start, int end) {
        return !value.isBefore(LocalTime.of(start, 0)) && value.isBefore(LocalTime.of(end, 0));
    }

    private static BigDecimal value(BigDecimal preferred, BigDecimal fallback) { return preferred == null ? fallback : preferred; }
    private static BigDecimal tokenCost(long tokens, BigDecimal perMillion) {
        return BigDecimal.valueOf(Math.max(0, tokens)).multiply(perMillion).divide(MILLION, MONEY_SCALE, RoundingMode.UP);
    }
    static int affordableOutputTokens(BigDecimal remaining, BigDecimal inputCost, BigDecimal outputPrice, int requested) {
        if (outputPrice.signum() == 0) return requested;
        long affordable = remaining.subtract(inputCost).max(BigDecimal.ZERO).multiply(MILLION)
                .divide(outputPrice, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(Integer.MAX_VALUE)).longValue();
        return (int) Math.min(requested, affordable);
    }
    private static long estimateTokens(Object input) { return String.valueOf(input == null ? "" : input).getBytes(StandardCharsets.UTF_8).length; }
    private static LocalDateTime monthStart(YearMonth month) { return month.atDay(1).atStartOfDay(); }
    private static boolean sameMonth(ModelUsageRecord record, YearMonth month) {
        return record.getCreatedAt() != null && YearMonth.from(record.getCreatedAt()).equals(month);
    }
    private static List<ModelUsageRecord> settled(List<ModelUsageRecord> values) { return values.stream().filter(v -> "SETTLED".equals(v.getStatus())).toList(); }
    private static void requireSuper(User user) {
        if (user == null || user.getRole() != User.Role.SUPER_ADMIN) throw new CustomException("需要超级管理员权限", HttpStatus.FORBIDDEN);
    }
    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) throw new CustomException("请填写操作原因", HttpStatus.BAD_REQUEST);
        if (reason.trim().length() > 300) throw new CustomException("操作原因不能超过 300 个字符", HttpStatus.BAD_REQUEST);
    }
    private static void validatePrices(PricingUpdate value) {
        if (value == null || value.inputPrice() == null || value.cacheHitPrice() == null || value.outputPrice() == null
                || Stream.of(value.inputPrice(), value.cacheHitPrice(), value.outputPrice(),
                        value.offPeakInputPrice(), value.offPeakCacheHitPrice(), value.offPeakOutputPrice())
                .filter(Objects::nonNull)
                .anyMatch(v -> v.signum() < 0))
            throw new CustomException("价格不能为负数", HttpStatus.BAD_REQUEST);
    }
    private static String money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static String priceSummary(ModelPricingRule r) { return String.format("%s[入%s/缓存%s/出%s；闲时入%s/缓存%s/出%s]",
            r.isEnabled() ? "启用" : "停用", r.getInputPrice(), r.getCacheHitPrice(), r.getOutputPrice(),
            r.getOffPeakInputPrice(), r.getOffPeakCacheHitPrice(), r.getOffPeakOutputPrice()); }
    private UsageItem usageItem(ModelUsageRecord v) { return new UsageItem(v.getCreatedAt(), v.getUserId(), v.getUsername(), v.getModelName(), v.getModelType(), v.getScenario(), v.getInputTokens(), v.getCacheHitTokens(), v.getOutputTokens(), v.getInputPrice(), v.getCacheHitPrice(), v.getOutputPrice(), v.getAmount()); }
    private PricingItem pricingItem(ModelPricingRule v) { return new PricingItem(v.getId(), v.getModelName(), v.getModelType(), v.isEnabled(), v.getInputPrice(), v.getCacheHitPrice(), v.getOutputPrice(), v.getOffPeakInputPrice(), v.getOffPeakCacheHitPrice(), v.getOffPeakOutputPrice()); }

    public record Reservation(Long recordId, int allowedMaxTokens, long estimatedInputTokens, boolean unlimited) {
        static Reservation unlimited(int maxTokens) { return new Reservation(null, maxTokens, 0, true); }
    }
    record Prices(BigDecimal input, BigDecimal cacheHit, BigDecimal output) {}
    public record PricingUpdate(boolean enabled, BigDecimal inputPrice, BigDecimal cacheHitPrice, BigDecimal outputPrice,
                                BigDecimal offPeakInputPrice, BigDecimal offPeakCacheHitPrice, BigDecimal offPeakOutputPrice) {}
    public record ChartPoint(String label, BigDecimal amount) {}
    public record UserSummary(Long userId, String username, String displayName, BigDecimal quota, BigDecimal spent,
                              BigDecimal remaining, boolean monthlyReset) {}
    public record UsageItem(LocalDateTime time, Long userId, String username, String modelName, AiModelType modelType,
                            String scenario, long inputTokens, long cacheHitTokens, long outputTokens,
                            BigDecimal inputPrice, BigDecimal cacheHitPrice, BigDecimal outputPrice, BigDecimal amount) {}
    public record PricingItem(Long id, String modelName, AiModelType modelType, boolean enabled, BigDecimal inputPrice,
                              BigDecimal cacheHitPrice, BigDecimal outputPrice, BigDecimal offPeakInputPrice,
                              BigDecimal offPeakCacheHitPrice, BigDecimal offPeakOutputPrice) {}
    public record Overview(boolean superAdmin, String month, BigDecimal totalQuota, BigDecimal totalSpent,
                           BigDecimal totalRemaining, List<ChartPoint> trend, List<ChartPoint> byModel,
                           List<UserSummary> users, List<UsageItem> records, List<PricingItem> pricingRules) {}
}
