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
  maxTokens: 2000,
  dimension: 2048,
  batchSize: 10,
  maxConcurrency: 10
});

const formModel = ref<Api.ModelConfig.Request>(emptyForm());

const isAdmin = computed(() => Boolean(overview.value?.admin));
const configs = computed(() => overview.value?.configs || []);
const llmConfigs = computed(() => configs.value.filter(item => item.modelType === 'LLM'));
const embeddingConfigs = computed(() => configs.value.filter(item => item.modelType === 'EMBEDDING'));
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
const preferenceReady = computed(() => selectedLlmConfigId.value != null && selectedEmbeddingConfigId.value != null);

const ownerTypeOptions = computed(() => {
  const options = [{ label: '我的模型', value: 'USER' }];
  if (isAdmin.value) options.unshift({ label: '系统模型', value: 'SYSTEM' });
  return options;
});

const rules: FormRules = {
  name: { required: true, message: '请输入配置名称', trigger: 'blur' },
  baseUrl: { required: true, message: '请输入 Base URL', trigger: 'blur' },
  modelName: { required: true, message: '请输入模型名称', trigger: 'blur' }
};

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
    ellipsis: { tooltip: true }
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
        ? `temp ${row.temperature ?? '-'} / top_p ${row.topP ?? '-'} / max ${row.maxTokens ?? '-'}`
        : `维度 ${row.dimension ?? 2048} / batch ${row.batchSize ?? '-'} / 并发 ${row.maxConcurrency ?? '-'}`
  },
  {
    key: 'operate',
    title: '操作',
    width: 160,
    fixed: 'right',
    render: row => (
      <NSpace size={8}>
        <NButton size="small" type="primary" ghost onClick={() => openEdit(row)}>
          编辑
        </NButton>
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

function canDelete(item: Api.ModelConfig.Item) {
  return item.ownerType === 'USER' || isAdmin.value;
}

async function loadData() {
  loading.value = true;
  const { data, error } = await fetchModelConfigOverview();
  if (!error) {
    overview.value = data;
    selectedLlmConfigId.value = data.selectedLlmConfigId;
    selectedEmbeddingConfigId.value = data.selectedEmbeddingConfigId;
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
    embeddingConfigId: selectedEmbeddingConfigId.value
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
    dimension: 2048,
    temperature: modelType === 'LLM' ? 0.3 : null,
    topP: modelType === 'LLM' ? 0.9 : null,
    maxTokens: modelType === 'LLM' ? 2000 : null
  };
  modalVisible.value = true;
}

function openEdit(row: Api.ModelConfig.Item) {
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
    maxTokens: row.maxTokens,
    dimension: row.dimension ?? 2048,
    batchSize: row.batchSize,
    maxConcurrency: row.maxConcurrency
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
    maxConcurrency: value.modelType === 'EMBEDDING' ? value.maxConcurrency : null,
    temperature: value.modelType === 'LLM' ? value.temperature : null,
    topP: value.modelType === 'LLM' ? value.topP : null,
    maxTokens: value.modelType === 'LLM' ? value.maxTokens : null
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
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="当前使用" :bordered="false" size="small" class="card-wrapper">
      <div class="grid grid-cols-1 gap-16px lg:grid-cols-[1fr_1fr_auto] lg:items-end">
        <div>
          <div class="mb-6px text-14px font-medium lh-22px">LLM</div>
          <NSelect v-model:value="selectedLlmConfigId" :options="selectableLlmOptions" placeholder="请选择 LLM" />
        </div>
        <div>
          <div class="mb-6px text-14px font-medium lh-22px">向量化模型</div>
          <NSelect
            v-model:value="selectedEmbeddingConfigId"
            :options="selectableEmbeddingOptions"
            placeholder="请选择向量化模型"
          />
        </div>
        <div class="flex">
          <NButton type="primary" :loading="saving" @click="savePreference">保存</NButton>
        </div>
      </div>
    </NCard>

    <NCard title="LLM 配置" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <NTooltip>
          <template #trigger>
            <NButton type="primary" size="small" circle aria-label="新增 LLM" @click="openCreate('LLM')">
              <template #icon>
                <SvgIcon icon="material-symbols:add-rounded" class="text-18px" />
              </template>
            </NButton>
          </template>
          新增 LLM
        </NTooltip>
      </template>
      <NDataTable
        :columns="columns"
        :data="llmConfigs"
        size="small"
        :loading="loading"
        :row-key="row => row.id"
        :scroll-x="900"
      />
    </NCard>

    <NCard title="向量化模型配置" :bordered="false" size="small" class="card-wrapper">
      <template #header-extra>
        <NTooltip>
          <template #trigger>
            <NButton type="primary" size="small" circle aria-label="新增向量化模型" @click="openCreate('EMBEDDING')">
              <template #icon>
                <SvgIcon icon="material-symbols:add-rounded" class="text-18px" />
              </template>
            </NButton>
          </template>
          新增向量化模型
        </NTooltip>
      </template>
      <NDataTable
        :columns="columns"
        :data="embeddingConfigs"
        size="small"
        :loading="loading"
        :row-key="row => row.id"
        :scroll-x="900"
      />
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
                { label: '向量化模型', value: 'EMBEDDING' }
              ]"
            />
          </NFormItem>
          <NFormItem label="Base URL" path="baseUrl">
            <NInput v-model:value="formModel.baseUrl" placeholder="https://api.example.com/v1" />
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
            <NInput v-model:value="formModel.modelName" placeholder="deepseek-chat / text-embedding-v4" />
          </NFormItem>
          <NFormItem label="启用">
            <NSwitch v-model:value="formModel.enabled" />
          </NFormItem>
          <NFormItem v-if="formModel.ownerType === 'SYSTEM'" label="系统默认">
            <NSwitch v-model:value="formModel.defaultModel" />
          </NFormItem>
          <template v-if="formModel.modelType === 'LLM'">
            <NFormItem label="Temperature">
              <NInputNumber v-model:value="formModel.temperature" :min="0" :max="2" :step="0.1" class="w-full" />
            </NFormItem>
            <NFormItem label="Top P">
              <NInputNumber v-model:value="formModel.topP" :min="0" :max="1" :step="0.05" class="w-full" />
            </NFormItem>
            <NFormItem label="Max Tokens">
              <NInputNumber v-model:value="formModel.maxTokens" :min="1" :step="100" class="w-full" />
            </NFormItem>
          </template>
          <template v-else>
            <NFormItem label="向量维度">
              <NInputNumber v-model:value="formModel.dimension" :disabled="true" class="w-full" />
            </NFormItem>
            <NFormItem label="Batch Size">
              <NInputNumber v-model:value="formModel.batchSize" :min="1" :step="1" class="w-full" />
            </NFormItem>
            <NFormItem label="最大并发">
              <NInputNumber v-model:value="formModel.maxConcurrency" :min="1" :step="1" class="w-full" />
            </NFormItem>
          </template>
        </div>
      </NForm>
      <template #footer>
        <div class="flex justify-end gap-12px">
          <NButton @click="modalVisible = false">取消</NButton>
          <NButton type="primary" :loading="saving" @click="handleSubmit">保存</NButton>
        </div>
      </template>
    </NModal>
  </div>
</template>
