<script setup lang="ts">
import {
  fetchDocumentGraph,
  publishDocumentGraph,
  rebuildDocumentGraph,
  setDocumentGraphEnabled,
  updateGraphCandidate
} from '@/service/api';

defineOptions({ name: 'GraphReviewDialog' });

const props = defineProps<{ fileMd5: string; fileName: string }>();
const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);
const saving = ref(false);
const graph = ref<Api.KnowledgeGraph.DocumentGraph | null>(null);

const statusText: Record<Api.KnowledgeGraph.Status, string> = {
  DISABLED: '未启用',
  QUEUED: '等待抽取',
  EXTRACTING: '正在抽取',
  PENDING_REVIEW: '待确认',
  PUBLISHED: '已发布',
  FAILED: '抽取失败'
};

const pendingCandidates = computed(() => graph.value?.candidates.filter(item => item.status === 'PENDING') || []);
const selectedCount = computed(() => pendingCandidates.value.filter(item => item.selected).length);

async function load() {
  if (!props.fileMd5) return;
  loading.value = true;
  const { data, error } = await fetchDocumentGraph(props.fileMd5);
  if (!error) graph.value = data;
  loading.value = false;
}

async function saveCandidate(candidate: Api.KnowledgeGraph.Candidate) {
  const { error } = await updateGraphCandidate(props.fileMd5, candidate.id, {
    selected: candidate.selected,
    subjectName: candidate.subjectName,
    subjectType: candidate.subjectType,
    predicate: candidate.predicate,
    objectName: candidate.objectName,
    objectType: candidate.objectType
  });
  if (error) await load();
}

async function toggleAll(selected: boolean) {
  pendingCandidates.value.forEach(item => {
    item.selected = selected;
  });
  await Promise.all(pendingCandidates.value.map(saveCandidate));
}

async function publish() {
  if (selectedCount.value === 0) {
    window.$message?.warning('请至少选择一条关系');
    return;
  }
  saving.value = true;
  await Promise.all(pendingCandidates.value.map(saveCandidate));
  const { error } = await publishDocumentGraph(props.fileMd5);
  if (!error) {
    window.$message?.success('知识图谱已发布');
    await load();
  }
  saving.value = false;
}

async function setEnabled(enabled: boolean) {
  saving.value = true;
  const { error } = await setDocumentGraphEnabled(props.fileMd5, enabled);
  if (!error) {
    window.$message?.success(enabled ? '已开始构建知识图谱' : '知识图谱已停用');
    await load();
  }
  saving.value = false;
}

async function rebuild() {
  saving.value = true;
  const { error } = await rebuildDocumentGraph(props.fileMd5);
  if (!error) {
    window.$message?.success('已重新开始抽取');
    await load();
  }
  saving.value = false;
}

watch(visible, value => {
  if (value) load();
});
</script>

<template>
  <NModal v-model:show="visible" preset="card" :title="`知识图谱 · ${fileName}`" class="w-1100px! max-w-[95vw]">
    <NSpin :show="loading">
      <div v-if="graph" class="flex flex-col gap-14px">
        <NAlert v-if="!graph.neo4jEnabled" type="warning">
          Neo4j 服务不可用。可以完成抽取和审核，但发布前需要启动 Neo4j，并确认 KNOWLEDGE_GRAPH_ENABLED=true。
        </NAlert>
        <div class="flex flex-wrap items-center justify-between gap-12px">
          <NSpace align="center">
            <NTag :type="graph.status === 'PUBLISHED' ? 'success' : graph.status === 'FAILED' ? 'error' : 'info'">
              {{ statusText[graph.status] }}
            </NTag>
            <NText v-if="graph.error" type="error">{{ graph.error }}</NText>
          </NSpace>
          <NSpace>
            <NButton v-if="!graph.enabled" type="primary" :loading="saving" @click="setEnabled(true)">启用并抽取</NButton>
            <NButton v-if="graph.enabled && ['FAILED', 'PUBLISHED'].includes(graph.status)" :loading="saving" @click="rebuild">
              重新抽取
            </NButton>
            <NButton v-if="graph.enabled" type="error" ghost :loading="saving" @click="setEnabled(false)">停用</NButton>
          </NSpace>
        </div>

        <template v-if="graph.status === 'PENDING_REVIEW'">
          <div class="flex items-center justify-between">
            <NText>AI 找到 {{ pendingCandidates.length }} 条关系，已选择 {{ selectedCount }} 条</NText>
            <NSpace>
              <NButton size="small" @click="toggleAll(true)">全选</NButton>
              <NButton size="small" @click="toggleAll(false)">取消全选</NButton>
            </NSpace>
          </div>
          <div class="max-h-560px overflow-auto rd-8px border border-#e5e7eb">
            <div
              v-for="candidate in pendingCandidates"
              :key="candidate.id"
              class="grid grid-cols-[36px_1fr_130px_1fr] gap-10px border-b border-#eef0f3 p-12px last:border-b-0"
            >
              <NCheckbox v-model:checked="candidate.selected" @update:checked="saveCandidate(candidate)" />
              <div class="grid grid-cols-[1fr_110px] gap-8px">
                <NInput v-model:value="candidate.subjectName" size="small" @change="saveCandidate(candidate)" />
                <NInput v-model:value="candidate.subjectType" size="small" @change="saveCandidate(candidate)" />
              </div>
              <NInput v-model:value="candidate.predicate" size="small" @change="saveCandidate(candidate)" />
              <div class="grid grid-cols-[1fr_110px] gap-8px">
                <NInput v-model:value="candidate.objectName" size="small" @change="saveCandidate(candidate)" />
                <NInput v-model:value="candidate.objectType" size="small" @change="saveCandidate(candidate)" />
              </div>
              <div class="col-start-2 col-span-3 text-12px text-#737985">
                切片 {{ candidate.evidenceChunkId }} · 可信度 {{ Math.round(candidate.confidence * 100) }}% ·
                {{ candidate.evidenceText }}
              </div>
            </div>
          </div>
        </template>

        <NEmpty v-else-if="graph.candidates.length === 0" description="暂无图谱关系" />
        <NText v-else depth="3">当前文档已有 {{ graph.candidates.filter(item => item.status === 'PUBLISHED').length }} 条已发布关系。</NText>
      </div>
    </NSpin>
    <template #footer>
      <div class="flex justify-end gap-12px">
        <NButton @click="visible = false">关闭</NButton>
        <NButton
          v-if="graph?.status === 'PENDING_REVIEW'"
          type="primary"
          :loading="saving"
          :disabled="!graph.neo4jEnabled"
          @click="publish"
        >
          确认并发布
        </NButton>
      </div>
    </template>
  </NModal>
</template>
