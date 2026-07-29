<script setup lang="ts">
import {
  fetchDocumentGraph,
  publishDocumentGraph,
  rebuildDocumentGraph,
  setDocumentGraphEnabled,
  updateGraphCandidate
} from '@/service/api';
import KnowledgeGraphCanvas from './knowledge-graph-canvas.vue';

defineOptions({ name: 'GraphReviewDialog' });

const props = defineProps<{ fileMd5: string; fileName: string }>();
const emit = defineEmits<{
  statusChange: [status: Api.KnowledgeGraph.Status];
}>();
const visible = defineModel<boolean>('visible', { default: false });
const loading = ref(false);
const saving = ref(false);
const graph = ref<Api.KnowledgeGraph.DocumentGraph | null>(null);
const activeTab = ref<'review' | 'preview'>('review');
const pollingStatuses = new Set<Api.KnowledgeGraph.Status>(['QUEUED', 'EXTRACTING']);
let pollingTimer: ReturnType<typeof setTimeout> | null = null;

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
const visualizedCandidates = computed(
  () => graph.value?.candidates.filter(item => item.status === 'PUBLISHED' || (item.status === 'PENDING' && item.selected)) || []
);
const previewGraph = computed(() => {
  const nodes = new Map<string, Api.KnowledgeGraph.GraphNode>();
  const edges: Api.KnowledgeGraph.GraphEdge[] = [];
  visualizedCandidates.value.forEach(candidate => {
    const source = graphNodeId(candidate.subjectType, candidate.subjectName);
    const target = graphNodeId(candidate.objectType, candidate.objectName);
    const sourceNode = nodes.get(source) || {
      id: source,
      name: candidate.subjectName,
      type: candidate.subjectType,
      degree: 0
    };
    const targetNode = nodes.get(target) || {
      id: target,
      name: candidate.objectName,
      type: candidate.objectType,
      degree: 0
    };
    sourceNode.degree += 1;
    targetNode.degree += 1;
    nodes.set(source, sourceNode);
    nodes.set(target, targetNode);
    edges.push({
      id: `candidate-${candidate.id}`,
      source,
      target,
      predicate: candidate.predicate,
      confidence: candidate.confidence,
      evidenceChunkId: candidate.evidenceChunkId,
      evidenceText: candidate.evidenceText,
      status: candidate.status
    });
  });
  return { nodes: [...nodes.values()], edges };
});

function graphNodeId(type: string, name: string) {
  const normalizedName = name.normalize('NFKC').trim().toLocaleLowerCase().replace(/[\s·•._\-—–]+/g, '');
  return `${type.trim().toLocaleUpperCase()}|${normalizedName}`;
}

async function load(showLoading = true) {
  if (!props.fileMd5) return;
  if (showLoading) loading.value = true;
  const { data, error } = await fetchDocumentGraph(props.fileMd5);
  if (!error) {
    const previousStatus = graph.value?.status;
    graph.value = data;
    activeTab.value = data.status === 'PENDING_REVIEW' ? 'review' : 'preview';
    if (previousStatus && previousStatus !== data.status) emit('statusChange', data.status);
  }
  if (showLoading) loading.value = false;
}

function stopPolling() {
  if (pollingTimer) clearTimeout(pollingTimer);
  pollingTimer = null;
}

function syncPolling() {
  stopPolling();
  if (!visible.value || !graph.value || !pollingStatuses.has(graph.value.status)) return;

  pollingTimer = setTimeout(async () => {
    await load(false);
    syncPolling();
  }, 2000);
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
    syncPolling();
  }
  saving.value = false;
}

async function rebuild() {
  saving.value = true;
  const { error } = await rebuildDocumentGraph(props.fileMd5);
  if (!error) {
    window.$message?.success('已重新开始抽取');
    await load();
    syncPolling();
  }
  saving.value = false;
}

watch(visible, async value => {
  if (value) {
    await load();
    syncPolling();
  } else {
    stopPolling();
  }
});

onUnmounted(stopPolling);
</script>

<template>
  <NModal
    v-model:show="visible"
    preset="card"
    :title="`知识图谱 · ${fileName}`"
    class="graph-review-modal"
    :style="{ width: 'min(1280px, calc(100vw - 32px))', maxHeight: 'calc(100vh - 32px)' }"
    content-style="min-height: 0; overflow: auto;"
  >
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
            <NText v-if="graph.error" :type="graph.status === 'FAILED' ? 'error' : 'warning'">
              {{ graph.error }}
            </NText>
          </NSpace>
          <NSpace>
            <NButton v-if="!graph.enabled" type="primary" :loading="saving" @click="setEnabled(true)">启用并抽取</NButton>
            <NButton
              v-if="
                graph.enabled &&
                (['FAILED', 'PUBLISHED'].includes(graph.status) || (graph.status === 'PENDING_REVIEW' && graph.error))
              "
              :loading="saving"
              @click="rebuild"
            >
              重新抽取
            </NButton>
            <NButton v-if="graph.enabled" type="error" ghost :loading="saving" @click="setEnabled(false)">停用</NButton>
          </NSpace>
        </div>

        <NTabs v-if="graph.candidates.length > 0" v-model:value="activeTab" type="line" animated>
          <NTabPane v-if="graph.status === 'PENDING_REVIEW'" name="review" tab="关系审核" display-directive="show:lazy">
            <div class="mb-12px flex items-center justify-between">
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
          </NTabPane>
          <NTabPane name="preview" tab="图谱预览" display-directive="show:lazy">
            <NAlert v-if="graph.status === 'PENDING_REVIEW'" type="info" class="mb-12px">
              预览会跟随审核勾选实时更新；点击关系可查看对应原文证据。
            </NAlert>
            <KnowledgeGraphCanvas :nodes="previewGraph.nodes" :edges="previewGraph.edges" />
          </NTabPane>
        </NTabs>

        <NEmpty v-else description="暂无图谱关系" />
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

<style scoped lang="scss">
:global(.graph-review-modal.n-card) {
  display: flex;
  overflow: hidden;
  flex-direction: column;
}

:global(.graph-review-modal .n-card__content) {
  min-height: 0;
  flex: 1;
}

:global(.graph-review-modal .n-card-header),
:global(.graph-review-modal .n-card__footer) {
  flex: none;
}

:global(.graph-review-modal .n-card-header__main) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 640px) {
  :global(.graph-review-modal.n-card) {
    width: calc(100vw - 16px) !important;
    max-height: calc(100vh - 16px) !important;
  }
}
</style>
