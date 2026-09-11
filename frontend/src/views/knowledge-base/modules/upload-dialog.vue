<script setup lang="ts">
import {
  defaultTextChunkSize,
  parseEngineOptions,
  textChunkSizeMax,
  textChunkSizeMin,
  textChunkSizeOptions,
  uploadAccept
} from '@/constants/common';
import { fetchGraphPromptTemplates } from '@/service/api';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const templateLoading = ref(false);
const templates = ref<Api.GraphPromptTemplate.Item[]>([]);
const visible = defineModel<boolean>('visible', { default: false });

const authStore = useAuthStore();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());
const isPrivateSpace = computed(
  () => typeof model.value.orgTag === 'string' && model.value.orgTag.startsWith('PRIVATE_')
);
const templateOptions = computed(() =>
  templates.value.filter(item => item.enabled).map(item => ({
    label: `${item.name}${item.defaultTemplate ? '（默认）' : ''}`,
    value: item.id
  }))
);
const selectedTemplate = computed(() =>
  templates.value.find(item => item.id === model.value.graphPromptTemplateId) || null
);

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    orgTag: null,
    orgTagName: '',
    isPublic: false,
    parseEngine: 'AUTO',
    chunkSize: defaultTextChunkSize,
    graphEnabled: true,
    graphPromptTemplateId: null,
    graphBatchChars: 3072,
    fileList: []
  };
}

const rules = ref<FormRules>({
  orgTag: defaultRequiredRule,
  isPublic: defaultRequiredRule,
  parseEngine: defaultRequiredRule,
  chunkSize: defaultRequiredRule,
  fileList: defaultRequiredRule
});

function close() {
  visible.value = false;
}

const store = useKnowledgeBaseStore();
async function handleSubmit() {
  if (loading.value) return;
  loading.value = true;
  try {
    await validate();
    if (model.value.graphEnabled && model.value.graphPromptTemplateId == null) {
      window.$message?.warning('请选择图谱抽取模板');
      return;
    }
    if (model.value.graphEnabled && (!Number.isInteger(model.value.graphBatchChars) || model.value.graphBatchChars < model.value.chunkSize)) {
      window.$message?.warning('图谱批次大小不得低于切片大小');
      return;
    }
    await store.enqueueUpload(model.value);
    close();
  } finally {
    loading.value = false;
  }
}

watch(visible, async () => {
  if (visible.value) {
    model.value = createDefaultModel();
    restoreValidation();
    templateLoading.value = true;
    const { data, error } = await fetchGraphPromptTemplates();
    if (!error) {
      templates.value = data || [];
      model.value.graphPromptTemplateId = templates.value.find(item => item.defaultTemplate)?.id
        || templates.value.find(item => item.enabled)?.id
        || null;
    }
    templateLoading.value = false;
  }
});

watch(
  () => model.value.graphEnabled,
  enabled => {
    if (enabled && model.value.graphPromptTemplateId == null) {
      model.value.graphPromptTemplateId = templates.value.find(item => item.defaultTemplate)?.id
        || templates.value.find(item => item.enabled)?.id
        || null;
    }
  }
);

watch(
  () => model.value.orgTag,
  orgTag => {
    if (typeof orgTag === 'string' && orgTag.startsWith('PRIVATE_')) model.value.isPublic = false;
    model.value.graphEnabled = true;
  }
);

function onUpdate(option: unknown) {
  if (option) model.value.orgTagName = (option as Api.OrgTag.Item).name;
}
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="dialog"
    title="文件上传"
    :show-icon="false"
    :mask-closable="false"
    class="w-500px!"
    @positive-click="handleSubmit"
  >
    <NForm ref="formRef" :model="model" :rules="rules" label-placement="left" :label-width="100" mt-10>
      <NFormItem v-if="authStore.isAdmin" label="组织标签" path="orgTag">
        <OrgTagCascader v-model:value="model.orgTag" @change="onUpdate" />
      </NFormItem>
      <NFormItem v-else label="组织标签" path="orgTag">
        <TheSelect
          v-model:value="model.orgTag"
          url="/users/org-tags"
          key-field="orgTagDetails"
          label-field="name"
          value-field="tagId"
          @change="onUpdate"
        />
      </NFormItem>

      <NFormItem label="是否公开" path="isPublic">
        <NText v-if="isPrivateSpace" depth="3">私有（仅自己可访问）</NText>
        <NRadioGroup v-else v-model:value="model.isPublic" name="visibility">
          <NSpace :size="16">
            <NRadio :value="true">公开</NRadio>
            <NRadio :value="false">仅组织内</NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>

      <NFormItem label="解析方式" path="parseEngine">
        <NRadioGroup v-model:value="model.parseEngine" name="parseEngine">
          <NSpace vertical :size="10">
            <NRadio v-for="option in parseEngineOptions" :key="option.value" :value="option.value">
              {{ option.label }}
            </NRadio>
          </NSpace>
        </NRadioGroup>
      </NFormItem>
      <NFormItem>
        <template #label>
          <span>知识图谱<span class="invisible">*</span></span>
        </template>
        <NSwitch v-model:value="model.graphEnabled" />
        <NText depth="3" class="ml-10px">抽取关系，确认后写入对应组织图谱</NText>
      </NFormItem>
      <NFormItem v-if="model.graphEnabled" label="抽取模板">
        <div class="min-w-0 flex-1">
          <NSelect
            v-model:value="model.graphPromptTemplateId"
            :options="templateOptions"
            :loading="templateLoading"
            placeholder="请选择适合当前文档的模板"
          />
          <NText v-if="selectedTemplate?.description" depth="3" class="mt-5px block text-12px">
            {{ selectedTemplate.description }}
          </NText>
        </div>
      </NFormItem>
      <NFormItem label="图谱批次大小" path="graphBatchChars">
        <NInputNumber v-model:value="model.graphBatchChars" :disabled="!model.graphEnabled"
          :min="model.chunkSize" :max="100000" :precision="0" :step="1024">
          <template #suffix>字符</template>
        </NInputNumber>
      </NFormItem>
      <NFormItem label="切片大小" path="chunkSize">
        <NSpace vertical :size="10">
          <NRadioGroup v-model:value="model.chunkSize" name="chunkSizePreset">
            <NSpace :size="8">
              <NRadioButton v-for="option in textChunkSizeOptions" :key="option.value" :value="option.value">
                {{ option.label }} {{ option.value }}
              </NRadioButton>
            </NSpace>
          </NRadioGroup>
          <NInputNumber
            v-model:value="model.chunkSize"
            :min="textChunkSizeMin"
            :max="textChunkSizeMax"
            :step="128"
            class="w-180px"
          />
        </NSpace>
      </NFormItem>
      <NFormItem label="文件内容" path="fileList">
        <NUpload
          v-model:file-list="model.fileList"
          :accept="uploadAccept"
          :max="1"
          :multiple="false"
          :default-upload="false"
        >
          <NButton>上传文件</NButton>
        </NUpload>
      </NFormItem>
    </NForm>
    <template #action>
      <NSpace :size="16">
        <NButton @click="close">取消</NButton>
        <NButton type="primary" :loading="loading" @click="handleSubmit">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
