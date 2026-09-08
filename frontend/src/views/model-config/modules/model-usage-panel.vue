<script setup lang="tsx">
import type { DataTableColumns } from 'naive-ui';
import { NButton, NProgress, NSwitch, NTag } from 'naive-ui';
import dayjs from 'dayjs';
import { fetchModelUsageOverview, updateModelPricing, updateUserModelQuota } from '@/service/api';

const loading = ref(false);
const saving = ref(false);
const data = ref<Api.ModelUsage.Overview | null>(null);
const allUsers = ref<Api.ModelUsage.UserSummary[]>([]);
const month = ref(dayjs().format('YYYY-MM'));
const userId = ref<number | null>(null);
const modelName = ref<string | null>(null);
const quotaVisible = ref(false);
const pricingVisible = ref(false);
const editingUser = ref<Api.ModelUsage.UserSummary | null>(null);
const editingPrice = ref<Api.ModelUsage.PricingItem | null>(null);
const quotaForm = reactive<Api.ModelUsage.QuotaRequest>({ monthlyLimit: 1, monthlyReset: true, currentPassword: '', reason: '' });
const priceForm = reactive<Api.ModelUsage.PricingRequest>({
  enabled: true, inputPrice: 0, cacheHitPrice: 0, outputPrice: 0,
  offPeakInputPrice: null, offPeakCacheHitPrice: null, offPeakOutputPrice: null, currentPassword: '', reason: ''
});

const money = (value: number | null | undefined) => `¥${Number(value || 0).toFixed(2)}`;
const percent = computed(() => data.value && data.value.totalQuota > 0
  ? Math.min(100, (data.value.totalSpent / data.value.totalQuota) * 100) : 0);
const remainingColor = computed(() => {
  if ((data.value?.totalRemaining ?? 0) < 0) return '#d03050';
  const rate = data.value && data.value.totalQuota > 0 ? data.value.totalRemaining / data.value.totalQuota : 0;
  return rate > 0.3 ? '#18a058' : '#f0a020';
});
const userOptions = computed(() => allUsers.value.map(v => ({ label: v.displayName || v.username, value: v.userId })));
const modelOptions = computed(() => (data.value?.pricingRules || []).map(v => ({ label: v.modelName, value: v.modelName })));

const trendSeries = {
  type: 'line' as const,
  smooth: 0.35,
  symbol: 'circle',
  symbolSize: 8,
  lineStyle: { width: 3, color: '#3b82f6' },
  itemStyle: { color: '#3b82f6' },
  areaStyle: { color: 'rgba(59, 130, 246, 0.12)' }
};
const modelSeries = {
  type: 'bar' as const,
  barMaxWidth: 24,
  itemStyle: { color: '#3b82f6', borderRadius: [0, 6, 6, 0] },
  label: { show: true, position: 'right' as const, formatter: ({ value }: { value: unknown }) => money(Number(value)) }
};
const { domRef: trendRef, updateOptions: updateTrend } = useEcharts(() => ({
  tooltip: { trigger: 'axis', valueFormatter: value => money(Number(value)) },
  grid: { left: 16, right: 20, top: 24, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: [], boundaryGap: false, axisTick: { show: false }, axisLabel: { margin: 14 } },
  yAxis: { type: 'value', minInterval: 0.01, axisLabel: { formatter: money }, splitLine: { lineStyle: { type: 'dashed', opacity: 0.35 } } },
  series: [{ ...trendSeries, data: [] }]
}));
const { domRef: modelRef, updateOptions: updateModels } = useEcharts(() => ({
  tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, valueFormatter: value => money(Number(value)) },
  grid: { left: 16, right: 72, top: 24, bottom: 8, containLabel: true },
  xAxis: { type: 'value', minInterval: 0.01, axisLabel: { formatter: money }, axisLine: { show: false }, axisTick: { show: false }, splitLine: { lineStyle: { type: 'dashed', opacity: 0.35 } } },
  yAxis: { type: 'category', data: [], axisTick: { show: false }, axisLine: { show: false }, axisLabel: { margin: 14 } },
  series: [{ ...modelSeries, data: [] }]
}));

const userColumns: DataTableColumns<Api.ModelUsage.UserSummary> = [
  { title: '用户', key: 'username', render: row => <div><div class="font-medium">{row.displayName || row.username}</div><div class="text-12px text-gray">{row.username}</div></div> },
  { title: '月额度', key: 'quota', render: row => money(row.quota) },
  { title: '已消费', key: 'spent', render: row => money(row.spent) },
  { title: '剩余', key: 'remaining', render: row => money(row.remaining) },
  { title: '使用率', key: 'rate', width: 150, render: row => <NProgress percentage={row.quota > 0 ? Math.min(100, row.spent / row.quota * 100) : 0} showIndicator={false} /> },
  { title: '月重置', key: 'monthlyReset', render: row => <NTag type={row.monthlyReset ? 'success' : 'default'}>{row.monthlyReset ? '开启' : '关闭'}</NTag> },
  { title: '操作', key: 'actions', width: 90, render: row => <NButton size="small" type="primary" ghost onClick={() => openQuota(row)}>调整</NButton> }
];
const usageColumns: DataTableColumns<Api.ModelUsage.UsageItem> = [
  { title: '时间', key: 'time', width: 170, render: row => dayjs(row.time).format('YYYY-MM-DD HH:mm:ss') },
  { title: '用户', key: 'username', width: 130 }, { title: '模型', key: 'modelName', minWidth: 170 },
  { title: '场景', key: 'scenario', width: 150 },
  { title: '输入 / 缓存 / 输出 Token', key: 'tokens', width: 210, render: row => `${row.inputTokens} / ${row.cacheHitTokens} / ${row.outputTokens}` },
  { title: '单价（入 / 缓存 / 出）', key: 'prices', width: 210, render: row => `${row.inputPrice.toFixed(2)} / ${row.cacheHitPrice.toFixed(2)} / ${row.outputPrice.toFixed(2)}` },
  { title: '金额', key: 'amount', width: 120, render: row => `¥${Number(row.amount || 0).toFixed(6)}` }
];
const pricingColumns: DataTableColumns<Api.ModelUsage.PricingItem> = [
  { title: '模型', key: 'modelName', minWidth: 180 }, { title: '类型', key: 'modelType', width: 110 },
  { title: '状态', key: 'enabled', width: 150, render: row => <NTag type={row.enabled ? 'success' : 'warning'}>{row.enabled ? '计价已启用' : '未启用计价规则'}</NTag> },
  { title: '高峰单价（入 / 缓存 / 出）', key: 'prices', minWidth: 230, render: row => `${row.inputPrice.toFixed(2)} / ${row.cacheHitPrice.toFixed(2)} / ${row.outputPrice.toFixed(2)}` },
  { title: '闲时单价（入 / 缓存 / 出）', key: 'offPrices', minWidth: 230, render: row => row.offPeakInputPrice == null ? '—' : `${row.offPeakInputPrice.toFixed(2)} / ${Number(row.offPeakCacheHitPrice).toFixed(2)} / ${Number(row.offPeakOutputPrice).toFixed(2)}` },
  { title: '操作', key: 'actions', width: 90, render: row => <NButton size="small" type="primary" ghost onClick={() => openPricing(row)}>编辑</NButton> }
];

async function load() {
  loading.value = true;
  const { data: value, error } = await fetchModelUsageOverview({ month: month.value, userId: userId.value || undefined, modelName: modelName.value || undefined });
  if (!error) {
    data.value = value;
    if (value.superAdmin && !userId.value) allUsers.value = value.users;
    await nextTick();
    updateTrend(() => ({ xAxis: { type: 'category', data: value.trend.map(v => v.label) }, series: [{ ...trendSeries, data: value.trend.map(v => v.amount) }] }));
    updateModels(() => ({ yAxis: { type: 'category', data: value.byModel.map(v => v.label) }, series: [{ ...modelSeries, data: value.byModel.map(v => v.amount) }] }));
  }
  loading.value = false;
}
function openQuota(row: Api.ModelUsage.UserSummary) {
  editingUser.value = row; Object.assign(quotaForm, { monthlyLimit: Number(row.quota.toFixed(2)), monthlyReset: row.monthlyReset, currentPassword: '', reason: '' }); quotaVisible.value = true;
}
function openPricing(row: Api.ModelUsage.PricingItem) {
  editingPrice.value = row; Object.assign(priceForm, { ...row, currentPassword: '', reason: '' }); pricingVisible.value = true;
}
async function saveQuota() {
  if (!editingUser.value || !quotaForm.currentPassword || !quotaForm.reason.trim()) return window.$message?.warning('请填写当前密码和操作原因');
  saving.value = true; const { error } = await updateUserModelQuota(editingUser.value.userId, quotaForm);
  if (!error) { quotaVisible.value = false; window.$message?.success('用户额度已更新'); await load(); } saving.value = false;
}
async function savePricing() {
  if (!editingPrice.value || !priceForm.currentPassword || !priceForm.reason.trim()) return window.$message?.warning('请填写当前密码和操作原因');
  saving.value = true; const { error } = await updateModelPricing(editingPrice.value.id, priceForm);
  if (!error) { pricingVisible.value = false; window.$message?.success('计价规则已更新'); await load(); } saving.value = false;
}
watch([month, userId, modelName], load);
onMounted(load);
</script>

<template>
  <div class="flex-col-stretch gap-16px">
    <NCard title="额度与消费" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra><div class="flex items-center gap-10px"><NTag v-if="data && !data.superAdmin" :type="data.users[0]?.monthlyReset ? 'success' : 'default'">月重置{{ data.users[0]?.monthlyReset ? '已开启' : '已关闭' }}</NTag><NDatePicker v-model:formatted-value="month" type="month" value-format="yyyy-MM" :clearable="false" /></div></template>
      <div v-if="data?.superAdmin" class="mb-16px flex flex-wrap gap-12px">
        <NSelect v-model:value="userId" :options="userOptions" clearable filterable placeholder="全部用户" class="w-220px" />
        <NSelect v-model:value="modelName" :options="modelOptions" clearable placeholder="全部模型" class="w-220px" />
      </div>
      <div class="grid grid-cols-1 gap-12px sm:grid-cols-2 xl:grid-cols-4">
        <NCard size="small"><NStatistic label="总额度" :value="money(data?.totalQuota)" /></NCard>
        <NCard size="small"><NStatistic label="已消费" :value="money(data?.totalSpent)" /></NCard>
        <NCard size="small"><NStatistic label="剩余额度" :value="money(data?.totalRemaining)" :theme-overrides="{ valueTextColor: remainingColor }" /></NCard>
        <NCard size="small">
          <div class="mb-10px text-13px text-gray">额度使用率</div>
          <div class="flex items-center gap-12px">
            <NProgress class="min-w-0 flex-1" type="line" :percentage="Number(percent.toFixed(2))" :show-indicator="false" />
            <span class="w-56px shrink-0 text-right tabular-nums">{{ percent.toFixed(2) }}%</span>
          </div>
        </NCard>
      </div>
    </NCard>

    <div class="grid grid-cols-1 gap-16px xl:grid-cols-2">
      <NCard title="每日消费趋势" :bordered="false" size="small" class="card-wrapper"><div ref="trendRef" class="h-280px" /></NCard>
      <NCard title="模型消费分布" :bordered="false" size="small" class="card-wrapper"><div ref="modelRef" class="h-280px" /></NCard>
    </div>
    <NCard v-if="data?.superAdmin" title="用户额度" :bordered="false" size="small" class="card-wrapper">
      <NDataTable :columns="userColumns" :data="data?.users || []" :loading="loading" :scroll-x="900" />
    </NCard>
    <NCard title="消费明细（最近 200 条）" :bordered="false" size="small" class="card-wrapper">
      <NDataTable :columns="usageColumns" :data="data?.records || []" :loading="loading" :scroll-x="1250" :pagination="{ pageSize: 10 }" />
    </NCard>
    <NCard v-if="data?.superAdmin" title="计价规则（人民币元 / 百万 Token）" :bordered="false" size="small" class="card-wrapper">
      <NDataTable :columns="pricingColumns" :data="data?.pricingRules || []" :loading="loading" :scroll-x="1050" />
    </NCard>

    <NModal v-model:show="quotaVisible" preset="card" title="调整用户额度" class="max-w-520px">
      <NForm label-placement="top"><NFormItem label="月额度（元）"><NInputNumber v-model:value="quotaForm.monthlyLimit" :min="0" :precision="2" class="w-full" /></NFormItem>
        <NFormItem label="每月自动重置"><NSwitch v-model:value="quotaForm.monthlyReset" /></NFormItem>
        <NFormItem label="操作原因"><NInput v-model:value="quotaForm.reason" maxlength="300" /></NFormItem>
        <NFormItem label="当前密码"><NInput v-model:value="quotaForm.currentPassword" type="password" show-password-on="click" /></NFormItem></NForm>
      <template #footer><div class="flex justify-end gap-12px"><NButton @click="quotaVisible = false">取消</NButton><NButton type="primary" :loading="saving" @click="saveQuota">确认调整</NButton></div></template>
    </NModal>
    <NModal v-model:show="pricingVisible" preset="card" title="编辑计价规则" class="max-w-620px">
      <NForm label-placement="top"><NFormItem label="启用计价"><NSwitch v-model:value="priceForm.enabled" /></NFormItem>
        <div class="grid grid-cols-3 gap-12px"><NFormItem label="输入单价"><NInputNumber v-model:value="priceForm.inputPrice" :min="0" :precision="2" class="w-full" /></NFormItem><NFormItem label="缓存命中"><NInputNumber v-model:value="priceForm.cacheHitPrice" :min="0" :precision="2" class="w-full" /></NFormItem><NFormItem label="输出单价"><NInputNumber v-model:value="priceForm.outputPrice" :min="0" :precision="2" class="w-full" /></NFormItem></div>
        <div v-if="editingPrice?.modelName === 'deepseek-v4-flash'" class="grid grid-cols-3 gap-12px"><NFormItem label="闲时输入"><NInputNumber v-model:value="priceForm.offPeakInputPrice" :min="0" :precision="2" class="w-full" /></NFormItem><NFormItem label="闲时缓存"><NInputNumber v-model:value="priceForm.offPeakCacheHitPrice" :min="0" :precision="2" class="w-full" /></NFormItem><NFormItem label="闲时输出"><NInputNumber v-model:value="priceForm.offPeakOutputPrice" :min="0" :precision="2" class="w-full" /></NFormItem></div>
        <NFormItem label="操作原因"><NInput v-model:value="priceForm.reason" maxlength="300" /></NFormItem><NFormItem label="当前密码"><NInput v-model:value="priceForm.currentPassword" type="password" show-password-on="click" /></NFormItem></NForm>
      <template #footer><div class="flex justify-end gap-12px"><NButton @click="pricingVisible = false">取消</NButton><NButton type="primary" :loading="saving" @click="savePricing">保存</NButton></div></template>
    </NModal>
  </div>
</template>
