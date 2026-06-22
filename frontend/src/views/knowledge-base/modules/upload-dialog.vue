<script setup lang="ts">
import {
  defaultTextChunkSize,
  parseEngineOptions,
  textChunkSizeMax,
  textChunkSizeMin,
  textChunkSizeOptions,
  uploadAccept
} from '@/constants/common';

defineOptions({
  name: 'UploadDialog'
});

const loading = ref(false);
const visible = defineModel<boolean>('visible', { default: false });

const authStore = useAuthStore();

const { formRef, validate, restoreValidation } = useNaiveForm();
const { defaultRequiredRule } = useFormRules();

const model = ref<Api.KnowledgeBase.Form>(createDefaultModel());

function createDefaultModel(): Api.KnowledgeBase.Form {
  return {
    orgTag: null,
    orgTagName: '',
    isPublic: false,
    parseEngine: 'AUTO',
    chunkSize: defaultTextChunkSize,
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
  await validate();
  loading.value = true;
  await store.enqueueUpload(model.value);
  loading.value = false;
  close();
}

watch(visible, () => {
  if (visible.value) {
    model.value = createDefaultModel();
    restoreValidation();
  }
});

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
        <NRadioGroup v-model:value="model.isPublic" name="radiogroup">
          <NSpace :size="16">
            <NRadio :value="true">公开</NRadio>
            <NRadio :value="false">私有</NRadio>
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
        <NButton type="primary" @click="handleSubmit">保存</NButton>
      </NSpace>
    </template>
  </NModal>
</template>

<style scoped></style>
