<script setup lang="ts">
import { CanvasEvent, EdgeEvent, Graph, NodeEvent } from '@antv/g6';
import type { GraphData, IElementEvent } from '@antv/g6';
import SvgIcon from '@/components/custom/svg-icon.vue';

defineOptions({ name: 'KnowledgeGraphCanvas' });

const props = defineProps<{
  nodes: Api.KnowledgeGraph.GraphNode[];
  edges: Api.KnowledgeGraph.GraphEdge[];
}>();

const themeStore = useThemeStore();
const containerRef = ref<HTMLElement | null>(null);
const frameRef = ref<HTMLElement | null>(null);
const keyword = ref('');
const selectedType = ref<string | null>(null);
const selectedEdgeId = ref<string | null>(null);
const selectedNodeId = ref<string | null>(null);
const rendering = ref(false);
let graph: Graph | null = null;

const typeColors: Record<string, string> = {
  PERSON: '#8b6fb3',
  ORGANIZATION: '#5875a8',
  SYSTEM: '#438899',
  SERVICE: '#4d917d',
  METHOD: '#b97a58',
  MODEL: '#a96d86',
  DATASET: '#6f9565',
  EVENT: '#b96868',
  LOCATION: '#6d7eaa',
  CONCEPT: '#7669ad',
  TECHNOLOGY: '#647b8f',
  OTHER: '#7b8794'
};

const typeOptions = computed(() => {
  const types = [...new Set(props.nodes.map(node => node.type))].sort();
  return types.map(type => ({ label: type, value: type }));
});

const visibleData = computed(() => {
  const normalizedKeyword = keyword.value.trim().toLocaleLowerCase();
  const directlyMatchedIds = new Set(
    props.nodes
      .filter(node => !selectedType.value || node.type === selectedType.value)
      .filter(node => !normalizedKeyword || node.name.toLocaleLowerCase().includes(normalizedKeyword))
      .map(node => node.id)
  );

  const visibleIds = new Set(directlyMatchedIds);
  if (normalizedKeyword) {
    props.edges.forEach(edge => {
      if (directlyMatchedIds.has(edge.source) || directlyMatchedIds.has(edge.target)) {
        visibleIds.add(edge.source);
        visibleIds.add(edge.target);
      }
    });
  }

  const nodes = props.nodes.filter(node => visibleIds.has(node.id));
  const edges = props.edges.filter(edge => visibleIds.has(edge.source) && visibleIds.has(edge.target));
  return { nodes, edges };
});

const selectedEdge = computed(() => props.edges.find(edge => edge.id === selectedEdgeId.value) || null);
const selectedNode = computed(() => props.nodes.find(node => node.id === selectedNodeId.value) || null);

function graphData(): GraphData {
  const nodes = visibleData.value.nodes;
  const edges = visibleData.value.edges;
  const adjacency = new Map(nodes.map(node => [node.id, new Set<string>()]));

  edges.forEach(edge => {
    adjacency.get(edge.source)?.add(edge.target);
    adjacency.get(edge.target)?.add(edge.source);
  });

  const unvisited = new Set(nodes.map(node => node.id));
  const components: string[][] = [];
  while (unvisited.size > 0) {
    const seed = unvisited.values().next().value as string;
    const component: string[] = [];
    const queue = [seed];
    unvisited.delete(seed);
    while (queue.length > 0) {
      const nodeId = queue.shift()!;
      component.push(nodeId);
      adjacency.get(nodeId)?.forEach(neighborId => {
        if (!unvisited.delete(neighborId)) return;
        queue.push(neighborId);
      });
    }
    components.push(component);
  }

  components.sort((left, right) => right.length - left.length);
  const anchorOf = (component: string[]) =>
    [...component].sort((left, right) => (adjacency.get(right)?.size || 0) - (adjacency.get(left)?.size || 0))[0];
  const primaryAnchor = components[0] ? anchorOf(components[0]) : null;
  const layoutEdges = primaryAnchor
    ? components.slice(1).map((component, index) => ({
        id: `__layout-${index}`,
        source: primaryAnchor,
        target: anchorOf(component),
        data: { layoutOnly: true }
      }))
    : [];

  return {
    nodes: nodes.map(node => ({
      id: node.id,
      data: { ...node }
    })),
    edges: [
      ...edges.map(edge => ({
      id: edge.id,
      source: edge.source,
      target: edge.target,
      data: { ...edge }
      })),
      ...layoutEdges
    ]
  };
}

function nodeColor(type: string) {
  return typeColors[type] || typeColors.OTHER;
}

function nodeStyle(datum: { data?: Record<string, unknown> }) {
  const node = datum.data as unknown as Api.KnowledgeGraph.GraphNode;
  const color = nodeColor(node.type);
  return {
    size: Math.min(58, 34 + Math.max(node.degree - 1, 0) * 4),
    fill: color,
    fillOpacity: themeStore.darkMode ? 0.86 : 0.9,
    stroke: themeStore.darkMode ? '#e2e8f0' : '#ffffff',
    lineWidth: 2,
    shadowColor: `${color}55`,
    shadowBlur: 12,
    labelText: node.name,
    labelPlacement: 'bottom' as const,
    labelOffsetY: 8,
    labelFill: themeStore.darkMode ? '#e5e7eb' : '#334155',
    labelFontSize: 12,
    labelFontWeight: 500,
    labelWordWrap: true,
    labelMaxWidth: 120
  };
}

function edgeStyle(datum: { data?: Record<string, unknown> }) {
  if (datum.data?.layoutOnly) {
    return {
      strokeOpacity: 0,
      opacity: 0,
      endArrow: false,
      label: false
    };
  }
  const edge = datum.data as unknown as Api.KnowledgeGraph.GraphEdge;
  return {
    stroke: themeStore.darkMode ? '#718096' : '#a5b1c1',
    lineWidth: selectedEdgeId.value === edge.id ? 3 : 1.5,
    opacity: selectedEdgeId.value && selectedEdgeId.value !== edge.id ? 0.35 : 0.82,
    endArrow: true,
    endArrowSize: 8,
    labelText: edge.predicate,
    labelFill: themeStore.darkMode ? '#cbd5e1' : '#475569',
    labelFontSize: 11,
    labelBackground: true,
    labelBackgroundFill: themeStore.darkMode ? '#1f2937' : '#ffffff',
    labelBackgroundFillOpacity: 0.9,
    labelPadding: [2, 5],
    labelRadius: 4
  };
}

async function createGraph() {
  if (!containerRef.value || visibleData.value.nodes.length === 0) return;
  graph?.destroy();
  graph = new Graph({
    container: containerRef.value,
    autoResize: true,
    padding: 48,
    autoFit: { type: 'view', options: { when: 'always', direction: 'both' } },
    animation: false,
    data: graphData(),
    layout: {
      type: 'd3-force',
      animation: false,
      link: { distance: 108, strength: 0.8 },
      manyBody: { strength: -250 },
      center: { strength: 0.55 },
      collide: { radius: 40 },
      iterations: 280
    },
    node: {
      type: 'circle',
      style: nodeStyle,
      state: {
        selected: {
          halo: true,
          haloStroke: '#6366f1',
          haloLineWidth: 8,
          haloStrokeOpacity: 0.22
        }
      }
    },
    edge: {
      type: 'line',
      style: edgeStyle,
      state: {
        selected: {
          stroke: '#4f46e5',
          lineWidth: 3,
          opacity: 1
        }
      }
    },
    behaviors: ['drag-canvas', 'zoom-canvas', 'drag-element', 'hover-activate']
  });

  graph.on(EdgeEvent.CLICK, (event: IElementEvent) => selectEdge(String(event.target.id)));
  graph.on(NodeEvent.CLICK, (event: IElementEvent) => selectNode(String(event.target.id)));
  graph.on(CanvasEvent.CLICK, clearSelection);
  rendering.value = true;
  try {
    await graph.render();
    await fitView();
  } finally {
    rendering.value = false;
  }
}

async function refreshGraph() {
  if (visibleData.value.nodes.length === 0) {
    graph?.destroy();
    graph = null;
    return;
  }
  await createGraph();
}

async function fitView() {
  await graph?.fitView({ when: 'always', direction: 'both' }, { duration: 300 });
}

async function relayout() {
  rendering.value = true;
  try {
    await graph?.layout();
    await fitView();
  } finally {
    rendering.value = false;
  }
}

async function selectEdge(edgeId: string) {
  await resetElementStates();
  selectedEdgeId.value = edgeId;
  selectedNodeId.value = null;
  await graph?.setElementState(edgeId, ['selected'], true);
}

async function selectNode(nodeId: string) {
  await resetElementStates();
  selectedNodeId.value = nodeId;
  selectedEdgeId.value = null;
  await graph?.setElementState(nodeId, ['selected'], true);
}

async function resetElementStates() {
  if (!graph) return;
  const states = Object.fromEntries([
    ...visibleData.value.nodes.map(node => [node.id, []]),
    ...visibleData.value.edges.map(edge => [edge.id, []])
  ]);
  await graph.setElementState(states, false);
}

async function clearSelection() {
  await resetElementStates();
  selectedEdgeId.value = null;
  selectedNodeId.value = null;
}

async function toggleFullscreen() {
  if (!frameRef.value) return;
  if (document.fullscreenElement) await document.exitFullscreen();
  else await frameRef.value.requestFullscreen();
}

watch(
  () => [props.nodes, props.edges, keyword.value, selectedType.value, themeStore.darkMode],
  () => {
    selectedEdgeId.value = null;
    selectedNodeId.value = null;
    refreshGraph();
  },
  { deep: true }
);

onMounted(refreshGraph);
onBeforeUnmount(() => graph?.destroy());
</script>

<template>
  <div ref="frameRef" class="graph-frame">
    <div class="graph-toolbar">
      <div class="min-w-0 flex flex-1 flex-wrap items-center gap-8px">
        <NInput v-model:value="keyword" clearable size="small" placeholder="搜索实体" class="max-w-full" style="width: 210px">
          <template #prefix>
            <SvgIcon icon="mdi:magnify" />
          </template>
        </NInput>
        <NSelect
          v-model:value="selectedType"
          clearable
          size="small"
          placeholder="全部类型"
          :options="typeOptions"
          class="max-w-full"
          style="width: 150px"
        />
        <NText depth="3" class="text-12px">
          {{ visibleData.nodes.length }} 个实体 · {{ visibleData.edges.length }} 条关系
        </NText>
      </div>
      <NSpace :size="8">
        <NButton size="small" secondary @click="fitView">适应画布</NButton>
        <NButton size="small" secondary :loading="rendering" @click="relayout">重新布局</NButton>
        <NButton size="small" secondary @click="toggleFullscreen">全屏</NButton>
      </NSpace>
    </div>

    <div class="graph-legend">
      <span v-for="option in typeOptions" :key="option.value" class="graph-legend__item">
        <i :style="{ backgroundColor: nodeColor(option.value) }" />
        {{ option.label }}
      </span>
    </div>

    <div v-if="visibleData.nodes.length === 0" class="graph-empty flex items-center justify-center">
      <NEmpty description="没有符合条件的图谱关系" />
    </div>
    <div v-else class="graph-content">
      <div ref="containerRef" class="graph-canvas" />
      <aside v-if="selectedEdge || selectedNode" class="graph-inspector">
        <template v-if="selectedEdge">
          <NTag size="small" type="info">关系</NTag>
          <h3>{{ selectedEdge.predicate }}</h3>
          <NText depth="3" class="text-12px">
            置信度 {{ Math.round(selectedEdge.confidence * 100) }}% · 切片 {{ selectedEdge.evidenceChunkId }}
          </NText>
          <NDivider class="my-12px!" />
          <NText class="leading-22px">{{ selectedEdge.evidenceText }}</NText>
        </template>
        <template v-else-if="selectedNode">
          <NTag size="small" :color="{ color: `${nodeColor(selectedNode.type)}18`, textColor: nodeColor(selectedNode.type) }">
            {{ selectedNode.type }}
          </NTag>
          <h3>{{ selectedNode.name }}</h3>
          <NText depth="3">连接了 {{ selectedNode.degree }} 条关系</NText>
        </template>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.graph-frame {
  position: relative;
  overflow: hidden;
  border: 1px solid #dfe5ed;
  border-radius: 14px;
  background:
    radial-gradient(circle at 14% 4%, rgb(112 103 181 / 8%), transparent 34%),
    radial-gradient(circle at 88% 92%, rgb(67 136 153 / 6%), transparent 30%),
    #f8fafc;
  box-shadow: inset 0 1px 0 rgb(255 255 255 / 75%);
}

.graph-frame:fullscreen {
  padding: 20px;
  background: #f8fafc;
}

.graph-frame:fullscreen .graph-canvas {
  height: calc(100vh - 126px);
}

.graph-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-bottom: 1px solid #e4e9f0;
  background: rgb(255 255 255 / 88%);
  backdrop-filter: blur(12px);
}

.graph-legend {
  display: flex;
  min-height: 36px;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 14px;
  padding: 8px 14px;
  color: #64748b;
  font-size: 11px;
  background: rgb(248 250 252 / 72%);
}

.graph-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.graph-legend__item i {
  width: 8px;
  height: 8px;
  border-radius: 999px;
}

.graph-content {
  position: relative;
}

.graph-canvas {
  height: clamp(320px, calc(100vh - 390px), 600px);
}

.graph-empty {
  height: clamp(320px, calc(100vh - 390px), 600px);
}

.graph-inspector {
  position: absolute;
  top: 12px;
  right: 12px;
  width: min(300px, calc(100% - 24px));
  max-height: calc(100% - 24px);
  overflow: auto;
  padding: 16px;
  border: 1px solid rgb(148 163 184 / 24%);
  border-radius: 12px;
  background: rgb(255 255 255 / 94%);
  box-shadow: 0 16px 40px rgb(15 23 42 / 14%);
  backdrop-filter: blur(12px);
}

.graph-inspector h3 {
  margin: 10px 0 6px;
  color: #1e293b;
  font-size: 17px;
}

:global(html.dark) .graph-toolbar,
:global(html.dark) .graph-inspector {
  background: rgb(17 24 39 / 92%);
}

:global(html.dark) .graph-frame {
  border-color: #303b4d;
  background:
    radial-gradient(circle at 14% 4%, rgb(118 105 173 / 13%), transparent 34%),
    radial-gradient(circle at 88% 92%, rgb(67 136 153 / 10%), transparent 30%),
    #111827;
  box-shadow: none;
}

:global(html.dark) .graph-frame:fullscreen {
  background: #111827;
}

:global(html.dark) .graph-toolbar {
  border-color: #2b3546;
}

:global(html.dark) .graph-legend {
  color: #9ca9ba;
  background: rgb(17 24 39 / 72%);
}

:global(html.dark) .graph-inspector h3 {
  color: #f1f5f9;
}

@media (max-width: 640px) {
  .graph-toolbar {
    align-items: flex-start;
    flex-direction: column;
  }

  .graph-canvas {
    height: clamp(340px, calc(100vh - 360px), 520px);
  }

  .graph-empty {
    height: clamp(340px, calc(100vh - 360px), 520px);
  }
}
</style>
