<script setup lang="tsx">
import type { DataTableColumns } from 'naive-ui';
import { NButton, NEllipsis, NTag } from 'naive-ui';
import dayjs from 'dayjs';
import { fetchLangfuseOverview, fetchLangfuseTraceDetail, fetchLangfuseTraces } from '@/service/api';

type RangePreset = '1h' | '24h' | '7d' | 'custom';

const appStore = useAppStore();
const preset = ref<RangePreset>('24h');
const customRange = ref<[number, number] | null>(null);
const level = ref<string | null>(null);
const traceName = ref<string | null>(null);
const loading = ref(false);
const detailLoading = ref(false);
const overview = ref<Api.Observability.Overview | null>(null);
const traceList = ref<Api.Observability.TraceList | null>(null);
const traces = ref<Api.Observability.TraceItem[]>([]);
const selectedTrace = ref<Api.Observability.TraceDetail | null>(null);
const detailVisible = ref(false);

const levelOptions = [
  { label: '全部状态', value: '' },
  { label: '默认', value: 'DEFAULT' },
  { label: '错误', value: 'ERROR' },
  { label: '警告', value: 'WARNING' },
  { label: '调试', value: 'DEBUG' }
];

const columns: DataTableColumns<Api.Observability.TraceItem> = [
  {
    key: 'traceName',
    title: 'Trace',
    minWidth: 260,
    render: row => (
      <div class="min-w-0">
        <NEllipsis tooltip>
          <span class="cursor-pointer font-medium text-#1f2937 hover:text-primary" onClick={() => openTraceDetail(row)}>
            {row.traceName || row.traceId}
          </span>
        </NEllipsis>
        <div class="mt-4px text-12px text-#8a8f99">{row.traceId}</div>
      </div>
    )
  },
  {
    key: 'level',
    title: '状态',
    width: 100,
    render: row => <NTag type={row.level === 'ERROR' ? 'error' : 'success'}>{row.level || 'DEFAULT'}</NTag>
  },
  {
    key: 'modelNames',
    title: '模型',
    minWidth: 180,
    render: row => row.modelNames?.length ? row.modelNames.join(', ') : '-'
  },
  {
    key: 'observationCount',
    title: '步骤',
    width: 90
  },
  {
    key: 'durationMs',
    title: '耗时',
    width: 120,
    render: row => formatDuration(row.durationMs)
  },
  {
    key: 'totalTokens',
    title: 'Tokens',
    width: 120,
    render: row => formatNumber(row.totalTokens)
  },
  {
    key: 'totalCost',
    title: '成本',
    width: 110,
    render: row => formatCost(row.totalCost)
  },
  {
    key: 'startTime',
    title: '开始时间',
    width: 180,
    render: row => formatDate(row.startTime)
  },
  {
    key: 'operate',
    title: '操作',
    width: 100,
    fixed: 'right',
    render: row => (
      <NButton type="primary" ghost size="small" onClick={() => openTraceDetail(row)}>
        详情
      </NButton>
    )
  }
];

const observationColumns: DataTableColumns<Api.Observability.Observation> = [
  {
    key: 'name',
    title: '步骤',
    minWidth: 240,
    render: row => (
      <div class="min-w-0" style={{ paddingLeft: `${resolveDepth(row) * 18}px` }}>
        <NEllipsis tooltip>
          <span class="font-medium">{row.name || row.id}</span>
        </NEllipsis>
        <div class="mt-4px text-12px text-#8a8f99">{row.type || '-'}</div>
      </div>
    )
  },
  {
    key: 'level',
    title: '状态',
    width: 100,
    render: row => <NTag type={row.level === 'ERROR' ? 'error' : 'success'}>{row.level || 'DEFAULT'}</NTag>
  },
  {
    key: 'modelName',
    title: '模型',
    width: 170,
    render: row => row.modelName || '-'
  },
  {
    key: 'durationMs',
    title: '耗时',
    width: 110,
    render: row => formatDuration(row.durationMs)
  },
  {
    key: 'totalTokens',
    title: 'Tokens',
    width: 100,
    render: row => formatNumber(row.totalTokens)
  },
  {
    key: 'totalCost',
    title: '成本',
    width: 100,
    render: row => formatCost(row.totalCost)
  },
  {
    key: 'startTime',
    title: '开始时间',
    width: 180,
    render: row => formatDate(row.startTime)
  }
];

const timeRange = computed(() => {
  const now = dayjs();
  if (preset.value === 'custom' && customRange.value) {
    return {
      from: dayjs(customRange.value[0]).toISOString(),
      to: dayjs(customRange.value[1]).toISOString()
    };
  }

  const amount = preset.value === '1h' ? 1 : preset.value === '7d' ? 7 : 24;
  const unit = preset.value === '7d' ? 'day' : 'hour';

  return {
    from: now.subtract(amount, unit).toISOString(),
    to: now.toISOString()
  };
});

const disabledMessage = computed(() => overview.value?.enabled === false ? overview.value.message : traceList.value?.message);
const trendMax = computed(() => Math.max(...(overview.value?.trend || []).map(item => item.count), 1));

async function loadData(resetCursor = true) {
  loading.value = true;
  const params = {
    ...timeRange.value,
    level: level.value || null,
    traceName: traceName.value || null,
    cursor: resetCursor ? null : traceList.value?.nextCursor,
    limit: 100
  };

  const [overviewRes, tracesRes] = await Promise.all([
    fetchLangfuseOverview(timeRange.value),
    fetchLangfuseTraces(params)
  ]);

  if (!overviewRes.error) {
    overview.value = overviewRes.data;
  }

  if (!tracesRes.error) {
    traceList.value = tracesRes.data;
    traces.value = resetCursor ? tracesRes.data.items : [...traces.value, ...tracesRes.data.items];
  }

  loading.value = false;
}

async function loadMore() {
  if (!traceList.value?.nextCursor) return;
  await loadData(false);
}

async function openTraceDetail(row: Api.Observability.TraceItem) {
  detailVisible.value = true;
  detailLoading.value = true;
  selectedTrace.value = null;

  const { data, error } = await fetchLangfuseTraceDetail(row.traceId, timeRange.value);
  if (!error) {
    selectedTrace.value = data;
  }

  detailLoading.value = false;
}

function resolveDepth(row: Api.Observability.Observation) {
  if (!selectedTrace.value || !row.parentObservationId) return 0;
  const byId = new Map(selectedTrace.value.observations.map(item => [item.id, item]));
  let depth = 0;
  let parentId: string | null = row.parentObservationId;
  while (parentId && byId.has(parentId) && depth < 6) {
    depth += 1;
    parentId = byId.get(parentId)?.parentObservationId || null;
  }
  return depth;
}

function formatDate(value?: string | null) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function formatDuration(value?: number | null) {
  if (!value) return '-';
  if (value < 1000) return `${value}ms`;
  return `${(value / 1000).toFixed(2)}s`;
}

function formatNumber(value?: number | null) {
  return value == null ? '-' : value.toLocaleString();
}

function formatCost(value?: number | null) {
  if (!value) return '$0';
  return `$${value.toFixed(6)}`;
}

function formatMetadata(value: Record<string, unknown>) {
  const text = JSON.stringify(value || {}, null, 2);
  return text === '{}' ? '无 metadata' : text;
}

watch([preset, level], () => loadData());

onMounted(() => {
  loadData();
});
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <Teleport defer to="#header-extra">
      <div class="observability-filter-bar">
        <NRadioGroup v-model:value="preset" size="small">
          <NRadioButton value="1h">近 1h</NRadioButton>
          <NRadioButton value="24h">近 24h</NRadioButton>
          <NRadioButton value="7d">近 7d</NRadioButton>
          <NRadioButton value="custom">自定义</NRadioButton>
        </NRadioGroup>
        <NDatePicker
          v-if="preset === 'custom'"
          v-model:value="customRange"
          type="datetimerange"
          clearable
          class="custom-range-filter"
          @update:value="loadData()"
        />
        <NSelect v-model:value="level" :options="levelOptions" clearable size="small" class="level-filter" />
        <NInput
          v-model:value="traceName"
          clearable
          size="small"
          placeholder="Trace 名称"
          class="trace-name-filter"
          @keyup.enter="loadData()"
        />
        <NButton size="small" type="primary" :loading="loading" @click="loadData()">刷新</NButton>
      </div>
    </Teleport>

    <NAlert v-if="disabledMessage" type="warning" :show-icon="false">
      {{ disabledMessage }}
    </NAlert>

    <NGrid cols="1 s:2 m:4" :x-gap="16" :y-gap="16" responsive="screen">
      <NGi>
        <NCard :bordered="false" size="small" class="metric-card">
          <div class="metric-label">Trace 数</div>
          <div class="metric-value">{{ overview?.totalTraces ?? 0 }}</div>
        </NCard>
      </NGi>
      <NGi>
        <NCard :bordered="false" size="small" class="metric-card">
          <div class="metric-label">Observation 数</div>
          <div class="metric-value">{{ overview?.totalObservations ?? 0 }}</div>
        </NCard>
      </NGi>
      <NGi>
        <NCard :bordered="false" size="small" class="metric-card">
          <div class="metric-label">错误数</div>
          <div class="metric-value text-error">{{ overview?.errorCount ?? 0 }}</div>
        </NCard>
      </NGi>
      <NGi>
        <NCard :bordered="false" size="small" class="metric-card">
          <div class="metric-label">平均耗时</div>
          <div class="metric-value">{{ formatDuration(overview?.avgLatencyMs) }}</div>
        </NCard>
      </NGi>
    </NGrid>

    <NCard title="最近 Trace" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper trace-card">
      <template #header-extra>
        <NSpace align="center" :wrap="false">
          <span class="text-12px text-#8a8f99">点击 Trace 名称或“详情”查看完整调用链路</span>
          <NButton v-if="traceList?.nextCursor" size="small" :loading="loading" @click="loadMore">加载更多</NButton>
        </NSpace>
      </template>
      <NDataTable
        class="sm:h-full trace-table"
        :columns="columns"
        :data="traces"
        :loading="loading"
        :row-key="row => row.traceId"
        :scroll-x="1360"
        :flex-height="!appStore.isMobile"
        size="small"
      />
    </NCard>

    <NGrid cols="1 l:3" :x-gap="16" :y-gap="16" responsive="screen" class="shrink-0">
      <NGi span="1 l:2">
        <NCard title="调用趋势" :bordered="false" size="small" class="h-220px">
          <div v-if="overview?.trend?.length" class="trend-chart">
            <div v-for="item in overview.trend" :key="item.time" class="trend-item">
              <div class="trend-bar-wrap">
                <div class="trend-bar" :style="{ height: `${Math.max(8, (item.count / trendMax) * 150)}px` }" />
                <div v-if="item.errorCount" class="trend-error" />
              </div>
              <div class="trend-label">{{ dayjs(item.time).format('HH:mm') }}</div>
            </div>
          </div>
          <NEmpty v-else description="暂无趋势数据" />
        </NCard>
      </NGi>
      <NGi>
        <NCard title="模型分布" :bordered="false" size="small" class="h-220px">
          <div v-if="overview?.byModel?.length" class="flex-col gap-10px">
            <div v-for="item in overview.byModel" :key="item.model" class="model-row">
              <div class="min-w-0">
                <NEllipsis>{{ item.model }}</NEllipsis>
                <div class="text-12px text-#8a8f99">{{ item.count }} 次 · {{ formatNumber(item.totalTokens) }} tokens</div>
              </div>
              <NTag size="small">{{ formatCost(item.totalCost) }}</NTag>
            </div>
          </div>
          <NEmpty v-else description="暂无模型数据" />
        </NCard>
      </NGi>
    </NGrid>

    <NDrawer v-model:show="detailVisible" width="min(960px, 92vw)" placement="right">
      <NDrawerContent :title="selectedTrace?.traceName || selectedTrace?.traceId || 'Trace 详情'" closable>
        <NSpin :show="detailLoading">
          <div v-if="selectedTrace" class="flex-col gap-16px">
            <NDescriptions bordered size="small" :column="2">
              <NDescriptionsItem label="Trace ID">{{ selectedTrace.traceId }}</NDescriptionsItem>
              <NDescriptionsItem label="Session">{{ selectedTrace.sessionId || '-' }}</NDescriptionsItem>
              <NDescriptionsItem label="步骤数">{{ selectedTrace.observations.length }}</NDescriptionsItem>
              <NDescriptionsItem label="内容">{{ '输入/输出内容默认隐藏，后端未返回' }}</NDescriptionsItem>
            </NDescriptions>
            <NDataTable
              :columns="observationColumns"
              :data="selectedTrace.observations"
              :row-key="row => row.id"
              :scroll-x="1100"
              size="small"
            />
            <NCollapse>
              <NCollapseItem
                v-for="item in selectedTrace.observations"
                :key="item.id"
                :title="`${item.name || item.id} metadata`"
                :name="item.id"
              >
                <NCode :code="formatMetadata(item.metadata)" language="json" word-wrap />
              </NCollapseItem>
            </NCollapse>
          </div>
          <NEmpty v-else description="暂无详情数据" />
        </NSpin>
      </NDrawerContent>
    </NDrawer>
  </div>
</template>

<style scoped>
.observability-filter-bar {
  height: 100%;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 40px;
  white-space: nowrap;
}

.custom-range-filter {
  width: 320px;
}

.level-filter {
  width: 128px;
}

.trace-name-filter {
  width: 160px;
}

.metric-card {
  min-height: 84px;
}

.metric-label {
  color: #8a8f99;
  font-size: 13px;
}

.metric-value {
  margin-top: 8px;
  color: #1f2937;
  font-size: 24px;
  font-weight: 700;
  line-height: 1;
}

.trace-card {
  min-height: 300px;
}

.trend-chart {
  height: 160px;
  display: flex;
  align-items: flex-end;
  gap: 8px;
  overflow-x: auto;
  padding: 4px 0;
}

.trend-item {
  width: 34px;
  flex: 0 0 34px;
  text-align: center;
}

.trend-bar-wrap {
  height: 150px;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  position: relative;
}

.trend-bar {
  width: 16px;
  border-radius: 4px 4px 0 0;
  background: var(--primary-color);
  opacity: 0.85;
}

.trend-error {
  position: absolute;
  bottom: 0;
  width: 16px;
  height: 4px;
  border-radius: 0 0 4px 4px;
  background: #d03050;
}

.trend-label {
  margin-top: 6px;
  color: #8a8f99;
  font-size: 11px;
}

.model-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
</style>
