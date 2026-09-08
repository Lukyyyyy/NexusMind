<script setup lang="tsx">
import type { DataTableColumns, FormInst, FormRules } from 'naive-ui';
import { NButton, NPopconfirm, NSpace, NTag, NTooltip } from 'naive-ui';
import {
  createModelConfig,
  deleteModelConfig,
  fetchModelConfigOverview,
  updateModelConfig,
  updateModelPreference
} from '@/service/api';
import SvgIcon from '@/components/custom/svg-icon.vue';

const loading = ref(false);
const saving = ref(false);
const modalVisible = ref(false);
const editingId = ref<number | null>(null);
const formRef = ref<FormInst | null>(null);
const overview = ref<Api.ModelConfig.Overview | null>(null);
const selectedLlmConfigId = ref<number | null>(null);
const selectedEmbeddingConfigId = ref<number | null>(null);
const selectedGraphExtractionConfigId = ref<number | null>(null);
const selectedRerankConfigId = ref<number | null>(null);
const activeTab = ref<Api.ModelConfig.ModelType>('LLM');
const rerankWindowMin = ref(10);
const rerankWindowMax = ref(30);

const emptyForm = (): Api.ModelConfig.Request => ({
  ownerType: 'USER',
  modelType: 'LLM',
  name: '',
  provider: 'OpenAI Compatible',
  baseUrl: '',
  apiKey: '',
  modelName: '',
  enabled: true,
  defaultModel: false,
  temperature: 0.3,
  topP: 0.9,
  maxTokens: 16,
  maxToolCalls: 6,
  dimension: 2048,
  batchSize: 10,
  maxConcurrency: 10,
  instruct: null,
  topN: null,
  fps: null
});

const formModel = ref<Api.ModelConfig.Request>(emptyForm());

const isAdmin = computed(() => Boolean(overview.value?.admin));
const configs = computed(() => overview.value?.configs || []);
const llmConfigs = computed(() => configs.value.filter(item => item.modelType === 'LLM'));
const embeddingConfigs = computed(() => configs.value.filter(item => item.modelType === 'EMBEDDING'));
const rerankConfigs = computed(() => configs.value.filter(item => item.modelType === 'RERANK'));
const selectableLlmOptions = computed(() =>
  llmConfigs.value
    .filter(item => item.enabled)
    .map(item => ({ label: optionLabel(item), value: item.id }))
);
const selectableEmbeddingOptions = computed(() =>
  embeddingConfigs.value
    .filter(item => item.enabled)
    .map(item => ({ label: optionLabel(item), value: item.id }))
);
const selectableRerankOptions = computed(() =>
  rerankConfigs.value
    .filter(item => item.enabled)
    .map(item => ({ label: optionLabel(item), value: item.id }))
);
const tabMeta: Record<Api.ModelConfig.ModelType, { tab: string; add: string }> = {
  LLM: { tab: 'LLM', add: '新增 LLM' },
  EMBEDDING: { tab: '向量化模型', add: '新增向量化模型' },
  RERANK: { tab: 'Rerank 模型', add: '新增 Rerank 模型' }
};
const preferenceReady = computed(() => selectedLlmConfigId.value != null && selectedEmbeddingConfigId.value != null);

const ownerTypeOptions = computed(() => {
  const options = [{ label: '我的模型', value: 'USER' }];
  if (isAdmin.value) options.unshift({ label: '系统模型', value: 'SYSTEM' });
  return options;
});

const rules: FormRules = {
  name: { required: true, message: '请输入配置名称', trigger: 'blur' },
  baseUrl: { required: true, message: '请输入 Base URL', trigger: 'blur' },
  modelName: { required: true, message: '请输入模型名称', trigger: 'blur' },
  topN: [
    {
      validator: (_rule, value) => {
        if (formModel.value.modelType !== 'RERANK' || value == null) return true;
        if (value < rerankWindowMin.value) {
          return new Error(`重排窗口不能小于最终返回条数 topK（${rerankWindowMin.value}），否则无法覆盖全部返回结果`);
        }
        if (value > rerankWindowMax.value) {
          return new Error(`重排窗口不能超过全局融合窗口（${rerankWindowMax.value}）`);
        }
        return true;
      },
      trigger: ['blur', 'change']
    }
  ]
};

const rerankWindowInvalid = computed(
  () =>
    formModel.value.modelType === 'RERANK' &&
    formModel.value.topN != null &&
    (formModel.value.topN < rerankWindowMin.value || formModel.value.topN > rerankWindowMax.value)
);

const columns: DataTableColumns<Api.ModelConfig.Item> = [
  {
    key: 'name',
    title: '配置',
    minWidth: 190,
    render: row => (
      <div class="min-w-0">
        <div class="font-medium text-#1f2937">{row.name}</div>
        <div class="mt-4px text-12px text-#8a8f99">{row.modelName}</div>
      </div>
    )
  },
  {
    key: 'ownerType',
    title: '来源',
    width: 110,
    render: row => <NTag type={row.ownerType === 'SYSTEM' ? 'success' : 'info'}>{ownerLabel(row)}</NTag>
  },
  {
    key: 'baseUrl',
    title: 'Base URL',
    minWidth: 220,
    render: row => (
      <div class="min-w-0 flex items-center gap-8px">
        <NTooltip>
          {{
            trigger: () => <span class="min-w-0 flex-1 truncate">{row.baseUrl}</span>,
            default: () => row.baseUrl
          }}
        </NTooltip>
        <NButton quaternary circle size="tiny" aria-label="复制 Base URL" onClick={() => handleCopyBaseUrl(row.baseUrl)}>
          <SvgIcon icon="material-symbols:content-copy-outline-rounded" class="text-16px" />
        </NButton>
      </div>
    )
  },
  {
    key: 'status',
    title: '状态',
    width: 130,
    render: row => (
      <NSpace size={6}>
        <NTag type={row.enabled ? 'success' : 'warning'}>{row.enabled ? '启用' : '停用'}</NTag>
        {row.defaultModel ? <NTag type="primary">默认</NTag> : null}
      </NSpace>
    )
  },
  {
    key: 'params',
    title: '参数',
    minWidth: 190,
    render: row =>
      row.modelType === 'LLM'
        ? `temp ${row.temperature ?? '-'} / top_p ${row.topP ?? '-'} / max ${row.maxTokens == null ? '-' : row.maxTokens / 1024 + 'k'} / 工具 ${row.maxToolCalls ?? 6}`
        : row.modelType === 'EMBEDDING'
          ? `维度 ${row.dimension ?? 2048} / batch ${row.batchSize ?? '-'} / 并发 ${row.maxConcurrency ?? '-'}`
          : `窗口 ${row.topN ?? '全局 30'}${row.fps != null ? ` / fps ${row.fps}` : ''}${
              row.instruct ? ' / 自定义指令' : ''}`
  },
  {
    key: 'operate',
    title: '操作',
    width: 160,
    fixed: 'right',
    render: row => (
      <NSpace size={8}>
        {canManage(row) ? (
          <NButton size="small" type="primary" ghost onClick={() => openEdit(row)}>
            编辑
          </NButton>
        ) : null}
        {canDelete(row) ? (
          <NPopconfirm onPositiveClick={() => handleDelete(row)}>
            {{
              trigger: () => (
                <NButton size="small" type="error" ghost>
                  删除
                </NButton>
              ),
              default: () => '确认删除这个模型配置吗？'
            }}
          </NPopconfirm>
        ) : null}
      </NSpace>
    )
  }
];

function optionLabel(item: Api.ModelConfig.Item) {
  return `${item.name} (${item.modelName})`;
}

function ownerLabel(item: Api.ModelConfig.Item) {
  return item.ownerType === 'SYSTEM' ? '系统' : '我的';
}

function canManage(item: Api.ModelConfig.Item) {
  return item.ownerType === 'USER' || isAdmin.value;
}

function canDelete(item: Api.ModelConfig.Item) {
  return canManage(item);
}

function handleCopyBaseUrl(baseUrl: string) {
  navigator.clipboard.writeText(baseUrl);
  window.$message?.success('Base URL 已复制');
}

async function loadData() {
  loading.value = true;
  const { data, error } = await fetchModelConfigOverview();
  if (!error) {
    overview.value = data;
    selectedLlmConfigId.value = data.selectedLlmConfigId;
    selectedEmbeddingConfigId.value = data.selectedEmbeddingConfigId;
    selectedGraphExtractionConfigId.value = data.selectedGraphExtractionConfigId;
    selectedRerankConfigId.value = data.selectedRerankConfigId;
    rerankWindowMin.value = data.rerankWindowMin ?? 10;
    rerankWindowMax.value = data.rerankWindowMax ?? 30;
  }
  loading.value = false;
}

async function savePreference() {
  if (!preferenceReady.value) {
    window.$message?.warning('请选择 LLM 和向量化模型后再保存');
    return;
  }
  saving.value = true;
  const { error } = await updateModelPreference({
    llmConfigId: selectedLlmConfigId.value,
    embeddingConfigId: selectedEmbeddingConfigId.value,
    graphExtractionConfigId: selectedGraphExtractionConfigId.value,
    rerankConfigId: selectedRerankConfigId.value
  });
  if (!error) {
    window.$message?.success('当前模型已更新');
    await loadData();
  }
  saving.value = false;
}

function openCreate(modelType: Api.ModelConfig.ModelType) {
  editingId.value = null;
  formModel.value = {
    ...emptyForm(),
    ownerType: isAdmin.value ? 'SYSTEM' : 'USER',
    modelType,
    dimension: modelType === 'EMBEDDING' ? 2048 : null,
    temperature: modelType === 'LLM' ? 0.3 : null,
    topP: modelType === 'LLM' ? 0.9 : null,
    maxTokens: modelType === 'LLM' ? 16 : null
  };
  modalVisible.value = true;
}

function openEdit(row: Api.ModelConfig.Item) {
  if (!canManage(row)) {
    window.$message?.warning('系统模型只能由管理员维护');
    return;
  }
  editingId.value = row.id;
  formModel.value = {
    ownerType: row.ownerType,
    modelType: row.modelType,
    name: row.name,
    provider: row.provider,
    baseUrl: row.baseUrl,
    apiKey: '',
    modelName: row.modelName,
    enabled: row.enabled,
    defaultModel: row.defaultModel,
    temperature: row.temperature,
    topP: row.topP,
    maxTokens: row.maxTokens == null ? 16 : row.maxTokens / 1024,
    maxToolCalls: row.maxToolCalls ?? 6,
    dimension: row.dimension ?? 2048,
    batchSize: row.batchSize,
    maxConcurrency: row.maxConcurrency ?? 10,
    instruct: row.instruct,
    topN: row.topN,
    fps: row.fps
  };
  modalVisible.value = true;
}

async function handleSubmit() {
  await formRef.value?.validate();
  saving.value = true;
  const payload = normalizePayload(formModel.value);
  const request = editingId.value ? updateModelConfig(editingId.value, payload) : createModelConfig(payload);
  const { error } = await request;
  if (!error) {
    window.$message?.success(editingId.value ? '模型配置已更新' : '模型配置已创建');
    modalVisible.value = false;
    await loadData();
  }
  saving.value = false;
}

async function handleDelete(row: Api.ModelConfig.Item) {
  loading.value = true;
  const { error } = await deleteModelConfig(row.id);
  if (!error) {
    window.$message?.success('模型配置已删除');
    await loadData();
  }
  loading.value = false;
}

function normalizePayload(value: Api.ModelConfig.Request): Api.ModelConfig.Request {
  return {
    ...value,
    defaultModel: value.ownerType === 'SYSTEM' && value.defaultModel,
    dimension: value.modelType === 'EMBEDDING' ? 2048 : null,
    batchSize: value.modelType === 'EMBEDDING' ? value.batchSize : null,
    maxConcurrency: value.modelType === 'EMBEDDING' || value.modelType === 'LLM' ? value.maxConcurrency : null,
    temperature: value.modelType === 'LLM' ? value.temperature : null,
    topP: value.modelType === 'LLM' ? value.topP : null,
    maxTokens: value.modelType === 'LLM' && value.maxTokens != null ? value.maxTokens * 1024 : null,
    maxToolCalls: value.modelType === 'LLM' ? value.maxToolCalls : null,
    instruct: value.modelType === 'RERANK' ? value.instruct : null,
    topN: value.modelType === 'RERANK' ? value.topN : null,
    fps: value.modelType === 'RERANK' ? value.fps : null
  };
}

watch(
  () => formModel.value.ownerType,
  ownerType => {
    if (ownerType === 'USER') formModel.value.defaultModel = false;
  }
);

onMounted(loadData);
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-y-auto">
    <NCard title="当前使用" :bordered="false" size="small" class="card-wrapper">
      <div class="grid grid-cols-1 gap-16px md:grid-cols-2 xl:grid-cols-[repeat(4,minmax(0,1fr))_auto] xl:items-end">
        <div>
          <div class="mb-6px text-14px font-medium lh-22px">LLM</div>
          <NSelect v-model:value="selectedLlmConfigId" :options="selectableLlmOptions" placeholder="请选择 LLM" />
        </div>
        <div>
          <div class="mb-6px text-14px font-medium lh-22px">图谱抽取模型</div>
          <NSelect
            v-model:value="selectedGraphExtractionConfigId"
            :options="selectableLlmOptions"
            clearable
            placeholder="跟随问答模型"
          />
        </div>
        <div>
          <div class="mb-6px text-14px font-medium lh-22px">向量化模型</div>
          <NSelect
            v-model:value="selectedEmbeddingConfigId"
            :options="selectableEmbeddingOptions"
            placeholder="请选择向量化模型"
          />
        </div>
        <div>
          <div class="mb-6px text-14px font-medium lh-22px">Rerank 模型</div>
          <NSelect
            v-model:value="selectedRerankConfigId"
            :options="selectableRerankOptions"
            clearable
            placeholder="跟随系统默认"
          />
        </div>
        <div class="flex">
          <NButton type="primary" :loading="saving" @click="savePreference">保存</NButton>
        </div>
      </div>
    </NCard>

    <NCard title="模型配置" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <NButton type="primary" size="small" @click="openCreate(activeTab)">
          {{ tabMeta[activeTab].add }}
        </NButton>
      </template>
      <NTabs v-model:value="activeTab" type="line" animated>
        <NTabPane name="LLM" :tab="tabMeta.LLM.tab">
          <NDataTable
            :columns="columns"
            :data="llmConfigs"
            size="small"
            :loading="loading"
            :row-key="row => row.id"
            :scroll-x="900"
            table-layout="fixed"
          />
        </NTabPane>
        <NTabPane name="EMBEDDING" :tab="tabMeta.EMBEDDING.tab">
          <NDataTable
            :columns="columns"
            :data="embeddingConfigs"
            size="small"
            :loading="loading"
            :row-key="row => row.id"
            :scroll-x="900"
            table-layout="fixed"
          />
        </NTabPane>
        <NTabPane name="RERANK" :tab="tabMeta.RERANK.tab">
          <NDataTable
            :columns="columns"
            :data="rerankConfigs"
            size="small"
            :loading="loading"
            :row-key="row => row.id"
            :scroll-x="900"
            table-layout="fixed"
          />
        </NTabPane>
      </NTabs>
    </NCard>

    <NModal v-model:show="modalVisible" preset="card" :title="editingId ? '编辑模型配置' : '新增模型配置'" class="max-w-720px">
      <NForm ref="formRef" :model="formModel" :rules="rules" label-placement="top">
        <div class="grid grid-cols-1 gap-x-16px md:grid-cols-2">
          <NFormItem label="配置名称" path="name">
            <NInput v-model:value="formModel.name" placeholder="例如：DeepSeek" />
          </NFormItem>
          <NFormItem label="来源">
            <NSelect v-model:value="formModel.ownerType" :options="ownerTypeOptions" :disabled="Boolean(editingId)" />
          </NFormItem>
          <NFormItem label="供应商（可选）">
            <NInput v-model:value="formModel.provider" placeholder="仅用于标记来源，可不填" />
          </NFormItem>
          <NFormItem label="模型类型">
            <NSelect
              v-model:value="formModel.modelType"
              :disabled="Boolean(editingId)"
              :options="[
                { label: 'LLM', value: 'LLM' },
                { label: '向量化模型', value: 'EMBEDDING' },
                { label: 'Rerank 模型', value: 'RERANK' }
              ]"
            />
          </NFormItem>
          <NFormItem label="Base URL" path="baseUrl">
            <NInput
              v-model:value="formModel.baseUrl"
              :placeholder="formModel.modelType === 'RERANK'
                ? 'https://dashscope.aliyuncs.com（自动拼接 rerank 接口路径）'
                : 'https://api.example.com/v1'"
            />
          </NFormItem>
          <NFormItem label="API Key">
            <NInput
              v-model:value="formModel.apiKey"
              type="password"
              show-password-on="click"
              :placeholder="editingId ? '留空表示不修改' : '本地或无鉴权服务可为空'"
            />
          </NFormItem>
          <NFormItem label="模型名称" path="modelName">
            <NInput v-model:value="formModel.modelName" placeholder="deepseek-chat / text-embedding-v4 / qwen3-vl-rerank" />
          </NFormItem>
          <NFormItem label="启用">
            <NSwitch v-model:value="formModel.enabled" />
          </NFormItem>
          <NFormItem v-if="formModel.ownerType === 'SYSTEM'" label="系统默认">
            <NSwitch v-model:value="formModel.defaultModel" />
          </NFormItem>
          <template v-if="formModel.modelType === 'LLM'">
            <NFormItem>
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  Temperature
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="Temperature：控制回答的随机性。值越低越稳定，值越高越多样。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-280px">控制回答的随机性。值越低越稳定一致，值越高越多样、有创造性。</span>
                  </NTooltip>
                </span>
              </template>
              <NInputNumber v-model:value="formModel.temperature" :min="0" :max="2" :step="0.1" class="w-full" />
            </NFormItem>
            <NFormItem>
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  Top P
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="Top P：限制模型从累计概率达到该值的候选词中采样。值越低越聚焦。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-280px">限制候选词的采样范围。值越低回答越聚焦，通常不建议与 Temperature 同时大幅调整。</span>
                  </NTooltip>
                </span>
              </template>
              <NInputNumber v-model:value="formModel.topP" :min="0" :max="1" :step="0.05" class="w-full" />
            </NFormItem>
            <NFormItem>
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  Max Tokens（k）
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="Max Tokens：限制推理及最终正文的总输出预算，1k = 1024 token，不等同于字符数。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-280px">限制推理及最终正文的总输出预算，1k = 1024 token，不等同于字符数。值越大，回答可更长但耗时和费用可能增加。</span>
                  </NTooltip>
                </span>
              </template>
              <NInputNumber v-model:value="formModel.maxTokens" :min="1" :max="2097151" :precision="0" :step="1" class="w-full" />
            </NFormItem>
            <NFormItem label="图谱共享并发数">
              <NInputNumber v-model:value="formModel.maxConcurrency" :min="1" :max="30" :precision="0" class="w-full" />
            </NFormItem>
            <NFormItem>
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  最大工具调用次数
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="每次回答允许的工具调用总数，修改后仅影响新开始的回答。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-280px">每次回答允许的工具调用总数。修改后立即影响新开始的回答，不影响正在进行的回答。</span>
                  </NTooltip>
                </span>
              </template>
              <NInputNumber v-model:value="formModel.maxToolCalls" :min="1" :max="20" :precision="0" class="w-full" />
            </NFormItem>
          </template>
          <template v-else-if="formModel.modelType === 'EMBEDDING'">
            <NFormItem label="向量维度">
              <NInputNumber v-model:value="formModel.dimension" :disabled="true" class="w-full" />
            </NFormItem>
            <NFormItem label="Batch Size">
              <NInputNumber v-model:value="formModel.batchSize" :min="1" :max="10" :step="1" class="w-full" />
            </NFormItem>
            <NFormItem label="最大并发">
              <NInputNumber v-model:value="formModel.maxConcurrency" :min="1" :max="30" :step="1" class="w-full" />
            </NFormItem>
          </template>
          <template v-else-if="formModel.modelType === 'RERANK'">
            <NFormItem class="md:col-span-2">
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  排序指令（Instruct，可选）
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="排序指令：自定义重排任务说明，建议英文。留空使用服务端默认指令。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-320px">
                      自定义重排任务说明，建议英文。留空使用服务端默认指令（给定检索查询，召回能回答该查询的段落）。
                      仅当模型支持 instruct 参数时才下发，换模型不会因该字段报错。
                    </span>
                  </NTooltip>
                </span>
              </template>
              <NInput
                v-model:value="formModel.instruct"
                type="textarea"
                :rows="2"
                :maxlength="2000"
                placeholder="例如：Given a customer service question, retrieve the most relevant policy clauses."
              />
            </NFormItem>
            <NFormItem path="topN">
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  重排窗口 top_n（可选）
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="重排窗口：送入重排模型的候选条数，范围 topK 到全局融合窗口之间，留空使用全局默认。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-320px">
                      该模型的候选窗口：送入重排模型的候选条数，最终返回条数仍由系统按场景截断（如聊天取前
                      {{ rerankWindowMin }}）。下限为 topK（{{ rerankWindowMin }}）——小于它重排无法覆盖全部返回结果；
                      上限为全局融合窗口（{{ rerankWindowMax }}）。留空使用全局默认 {{ rerankWindowMax }}。
                    </span>
                  </NTooltip>
                </span>
              </template>
              <NInputNumber
                v-model:value="formModel.topN"
                :min="rerankWindowMin"
                :max="rerankWindowMax"
                :step="1"
                class="w-full"
                :placeholder="`留空 = 全局窗口 ${rerankWindowMax}`"
                clearable
              />
            </NFormItem>
            <NFormItem>
              <template #label>
                <span class="inline-flex items-center gap-4px">
                  fps（可选）
                  <NTooltip>
                    <template #trigger>
                      <button
                        type="button"
                        class="inline-flex cursor-help border-0 bg-transparent p-0 text-#8a8f99"
                        aria-label="fps：视频抽帧比例 0~1，仅重排视频文档时生效。"
                      >
                        <SvgIcon icon="material-symbols:help-outline-rounded" class="text-16px" />
                      </button>
                    </template>
                    <span class="block max-w-320px">
                      视频抽帧比例（0~1），越小抽取帧数越少。仅重排视频文档时生效，纯文本检索不会下发该参数。
                    </span>
                  </NTooltip>
                </span>
              </template>
              <NInputNumber
                v-model:value="formModel.fps"
                :min="0"
                :max="1"
                :step="0.1"
                class="w-full"
                placeholder="默认 1.0"
                clearable
              />
            </NFormItem>
          </template>
        </div>
      </NForm>
      <template #footer>
        <div class="flex items-center justify-end gap-12px">
          <span v-if="rerankWindowInvalid" class="text-12px text-error">
            重排窗口需在 {{ rerankWindowMin }} ~ {{ rerankWindowMax }} 之间（下限为最终返回条数 topK）
          </span>
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :disabled="rerankWindowInvalid" :loading="saving" @click="handleSubmit">
            保存
          </NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>
