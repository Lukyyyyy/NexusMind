<template>
  <div class="file-preview-container">
    <div class="preview-content">
      <template v-if="loading">
        <div class="flex items-center justify-center h-full">
          <NSpin size="large" />
        </div>
      </template>
      <template v-else-if="error">
        <div class="flex flex-col items-center justify-center h-full text-gray-500">
          <icon-mdi-alert-circle class="text-48 mb-4" />
          <p>{{ error }}</p>
        </div>
      </template>
      <template v-else-if="isPdfFile && previewUrl">
        <embed class="pdf-preview-frame" :src="pdfViewerUrl" type="application/pdf" />
      </template>
      <template v-else>
        <div class="content-wrapper">
          <pre class="preview-text">{{ content }}</pre>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { NSpin } from 'naive-ui';
import { request } from '@/service/request';
import { getAuthorization } from '@/service/request/shared';
import { getFileExt } from '@/utils/common';
import { getServiceBaseURL } from '@/utils/service';

interface Props {
  fileName: string;
  visible: boolean;
}

const props = defineProps<Props>();

const loading = ref(false);
const content = ref('');
const error = ref('');
const previewUrl = ref('');

const fileExt = computed(() => getFileExt(props.fileName)?.toLowerCase() || '');
const isPdfFile = computed(() => fileExt.value === 'pdf');
const pdfViewerUrl = computed(() => {
  if (!previewUrl.value) return '';
  return `${previewUrl.value}#toolbar=1&navpanes=0&scrollbar=1&view=Fit&page=1`;
});
const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);

watch(
  () => [props.fileName, props.visible] as const,
  async ([newFileName, visible]) => {
    if (newFileName && visible) {
      await loadPreviewContent();
    } else if (!visible) {
      content.value = '';
      error.value = '';
      revokePreviewUrl();
      loading.value = false;
    }
  },
  { immediate: true }
);

onBeforeUnmount(() => {
  revokePreviewUrl();
});

function revokePreviewUrl() {
  if (previewUrl.value.startsWith('blob:')) {
    URL.revokeObjectURL(previewUrl.value);
  }

  previewUrl.value = '';
}

async function loadPdfPreviewUrl() {
  const query = new URLSearchParams({ fileName: props.fileName });
  const Authorization = getAuthorization();

  const response = await fetch(`${baseURL}/documents/preview/pdf?${query.toString()}`, {
    headers: Authorization ? { Authorization } : undefined
  });

  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      const data = await response.json();
      message = data?.message || message;
    } catch {}

    throw new Error(message);
  }

  const blob = await response.blob();
  if (blob.type && blob.type !== 'application/pdf') {
    throw new Error('服务端返回的不是PDF文件');
  }

  revokePreviewUrl();
  previewUrl.value = URL.createObjectURL(new Blob([blob], { type: 'application/pdf' }));
}

// 加载预览内容
async function loadPreviewContent() {
  if (!props.fileName) return;
  
  loading.value = true;
  error.value = '';
  content.value = '';
  revokePreviewUrl();
  
  try {
    if (isPdfFile.value) {
      await loadPdfPreviewUrl();
      return;
    }

    const { error: requestError, data } = await request<{
      fileName: string;
      content: string;
      fileSize: number;
    }>({
      url: '/documents/preview',
      params: {
        fileName: props.fileName
      }
    });
    
    if (requestError) {
      error.value = '预览失败：' + (requestError.message || '未知错误');
    } else if (data) {
      content.value = data.content;
    }
  } catch (err: any) {
    error.value = '预览失败：' + (err.message || '网络错误');
  } finally {
    loading.value = false;
  }
}

</script>

<style scoped lang="scss">
.file-preview-container {
  @apply h-full min-h-0 flex flex-col bg-white;

  .preview-content {
    @apply min-h-0 flex-1 overflow-hidden bg-gray-100;

    .pdf-preview-frame {
      @apply block h-full w-full border-0 bg-white;
    }
    
    .content-wrapper {
      @apply h-full overflow-auto bg-white p-4;
    }
    
    .preview-text {
      @apply text-sm font-mono whitespace-pre-wrap break-words;
      font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
      line-height: 1.5;
      margin: 0;
    }
  }
}
</style>
