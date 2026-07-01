<script setup lang="ts">
// eslint-disable-next-line @typescript-eslint/no-unused-vars
import { nextTick } from 'vue';
import { VueMarkdownIt } from 'vue-markdown-shiki';
defineOptions({ name: 'ChatMessage' });

const props = defineProps<{ msg: Api.Chat.Message }>();

const authStore = useAuthStore();

function handleCopy(content: string) {
  navigator.clipboard.writeText(content);
  window.$message?.success('已复制');
}

const chatStore = useChatStore();

// 存储文件名和对应的事件处理
const sourceFiles = ref<Array<{fileName: string, id: string}>>([]);

// 处理来源文件链接的函数
function processSourceLinks(text: string): string {
  // 匹配 (来源#数字: 文件名) 的正则表达式
  const sourcePattern = /\(来源#(\d+):\s*([^)]+)\)/g;

  return text.replace(sourcePattern, (_match, sourceNum, fileName) => {
    // 为文件名创建可点击的链接
    const linkClass = 'source-file-link';
    const encodedFileName = encodeURIComponent(fileName.trim());
    const fileId = `source-file-${sourceFiles.value.length}`;

    // 存储文件信息
    sourceFiles.value.push({
      fileName: encodedFileName,
      id: fileId
    });

    return `(来源#${sourceNum}: <span class="${linkClass}" data-file-id="${fileId}">${fileName}</span>)`;
  });
}

const content = computed(() => {
  chatStore.scrollToBottom?.();
  const rawContent = props.msg.content ?? '';

  // 只对助手消息处理来源链接
  if (props.msg.role === 'assistant') {
    return processSourceLinks(rawContent);
  }

  return rawContent;
});

// 处理内容点击事件（事件委托）
function handleContentClick(event: MouseEvent) {
  const target = event.target as HTMLElement;

  // 检查点击的是否是文件链接
  if (target.classList.contains('source-file-link')) {
    const fileId = target.getAttribute('data-file-id');
    if (fileId) {
      const file = sourceFiles.value.find(f => f.id === fileId);
      if (file) {
        handleSourceFileClick(file.fileName);
      }
    }
  }
}

// 处理来源文件点击事件
async function handleSourceFileClick(fileName: string) {
  const decodedFileName = decodeURIComponent(fileName);
  console.log('点击了来源文件:', decodedFileName);

  try {
    window.$message?.loading(`正在获取文件下载链接: ${decodedFileName}`, {
      duration: 0,
      closable: false
    });

    // 调用文件下载接口
    const { error, data } = await request<Api.Document.DownloadResponse>({
      url: 'documents/download',
      params: {
        fileName: decodedFileName,
        token: authStore.token
      },
      baseURL: '/proxy-api'
    });

    window.$message?.destroyAll();

    if (error) {
      window.$message?.error(`文件下载失败: ${error.response?.data?.message || '未知错误'}`);
      return;
    }

    if (data?.downloadUrl) {
      // 在新窗口打开下载链接
      window.open(data.downloadUrl, '_blank');
      window.$message?.success(`文件下载链接已打开: ${decodedFileName}`);
    } else {
      window.$message?.error('未能获取到下载链接');
    }
  } catch (err) {
    window.$message?.destroyAll();
    console.error('文件下载失败:', err);
    window.$message?.error(`文件下载失败: ${decodedFileName}`);
  }
}
</script>

<template>
  <div class="chat-message mb-8" :class="msg.role === 'user' ? 'chat-message--user' : 'chat-message--assistant'">
    <div class="chat-message__inner">
      <template v-if="msg.role === 'user'">
        <div class="chat-message__user-row">
          <div class="chat-message__user-bubble">
            <NText tag="div" class="chat-message__text whitespace-pre-wrap text-4">{{ content }}</NText>
          </div>
        </div>
      </template>

      <template v-else>
        <div class="chat-message__assistant-content">
          <NText v-if="msg.status === 'pending'">
            <icon-eos-icons:three-dots-loading class="text-8" />
          </NText>
          <NText v-else-if="msg.status === 'error'" class="italic">服务器繁忙，请稍后再试</NText>
          <NText v-else tag="div" class="chat-message__markdown text-4" @click="handleContentClick">
            <VueMarkdownIt :content="content" />
          </NText>
        </div>
      </template>

      <div class="chat-message__actions" :class="msg.role === 'user' ? 'justify-end' : 'justify-start'">
        <NButton quaternary size="small" @click="handleCopy(msg.content)">
          <template #icon>
            <icon-mynaui:copy />
          </template>
        </NButton>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
:deep(.source-file-link) {
  color: #1890ff;
  cursor: pointer;
  text-decoration: underline;
  transition: color 0.2s;

  &:hover {
    color: #40a9ff;
    text-decoration: none;
  }

  &:active {
    color: #096dd9;
  }
}

.chat-message__inner {
  max-width: 760px;
  margin: 0 auto;
}

.chat-message__user-row {
  display: flex;
  justify-content: flex-end;
}

.chat-message__user-bubble {
  max-width: min(70%, 620px);
  border-radius: 20px;
  background: #f3f4f6;
  padding: 10px 16px;
  line-height: 1.5;
}

:global(.dark) .chat-message__user-bubble {
  background: #26262b;
}

.chat-message__assistant-content {
  max-width: 760px;
  color: #1f2937;
}

:global(.dark) .chat-message__assistant-content {
  color: #f1f5f9;
}

.chat-message__actions {
  display: flex;
  margin-top: 6px;
}

.chat-message__text,
.chat-message__markdown {
  color: inherit;
  line-height: 1.5;
}

.chat-message__markdown {
  :deep(.vp-doc) {
    width: auto;
    min-width: 0;
    color: inherit;
    font-size: inherit;
    line-height: 1.75;
  }

  :deep(.vp-doc > :first-child) {
    margin-top: 0;
  }

  :deep(.vp-doc > :last-child) {
    margin-bottom: 0;
  }

  :deep(.vp-doc p) {
    margin: 0 0 1em;
    font-size: inherit;
    line-height: 1.75;
  }

  :deep(.vp-doc ul),
  :deep(.vp-doc ol) {
    margin: 0.75em 0 1em;
    padding-left: 1.5em;
  }

  :deep(.vp-doc li) {
    margin: 0.35em 0;
    line-height: 1.75;
  }

  :deep(.vp-doc h1),
  :deep(.vp-doc h2),
  :deep(.vp-doc h3),
  :deep(.vp-doc h4),
  :deep(.vp-doc h5),
  :deep(.vp-doc h6) {
    margin: 0.8em 0 0.4em;
    border: 0;
    padding: 0;
    font-size: 1em;
    line-height: 1.5;
  }

  :deep(.vp-doc a) {
    color: #1677ff;
    font-weight: inherit;
    text-underline-offset: 3px;
  }

  :deep(.vp-doc strong) {
    font-weight: 600;
  }
}
</style>
