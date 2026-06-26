<script setup lang="tsx">
import type { DataTableSortState, DropdownOption } from 'naive-ui';
import type { UploadFileInfo } from 'naive-ui';
import { NButton, NDropdown, NEllipsis, NModal, NProgress, NTag, NTooltip, NUpload } from 'naive-ui';
import { uploadAccept } from '@/constants/common';
import { fakePaginationRequest } from '@/service/request';
import { UploadStatus } from '@/enum';
import SvgIcon from '@/components/custom/svg-icon.vue';
import FilePreview from '@/components/custom/file-preview.vue';
import { getToken } from '@/store/modules/auth/shared';
import { getServiceBaseURL } from '@/utils/service';
import UploadDialog from './modules/upload-dialog.vue';
import SearchDialog from './modules/search-dialog.vue';
import ChunkDialog from './modules/chunk-dialog.vue';

const appStore = useAppStore();
const authStore = useAuthStore();

// 文件预览相关状态
const previewVisible = ref(false);
const previewFileName = ref('');
const chunkVisible = ref(false);
const chunkFileMd5 = ref('');
const chunkFileName = ref('');
const chunkActualParseEngine = ref<Api.KnowledgeBase.UploadTask['actualParseEngine']>(null);
type SortableColumnKey = 'processingDurationMillis' | 'createdAt';
type ActiveSortState = {
  columnKey: SortableColumnKey;
  order: Exclude<DataTableSortState['order'], false>;
};
type KnowledgeBaseListParams = CommonType.RecordNullable<
  Api.Common.CommonSearchParams & {
    filterOrgTags: string;
    isPublic: boolean;
  }
>;

const visibilityOptions = [
  { label: '全部', value: 'all' },
  { label: '公开', value: 'public' },
  { label: '私有', value: 'private' }
];
const orgTagFilter = ref<Array<string | number>>([]);
const visibilityFilter = ref<'all' | 'public' | 'private'>('all');
const selectedOrgTagNames = ref<Record<string, string>>({});
const orgTagOptions = ref<Api.OrgTag.Item[]>([]);

const sortState = ref<ActiveSortState>({
  columnKey: 'createdAt',
  order: 'descend'
});

function apiFn(params: KnowledgeBaseListParams) {
  return fakePaginationRequest<Api.KnowledgeBase.List>({ url: '/documents/accessible', params });
}

function renderIcon(fileName: string) {
  const ext = getFileExt(fileName);
  if (ext) {
    if (uploadAccept.split(',').includes(`.${ext}`)) return <SvgIcon localIcon={ext} class="text-44px" />;
    return <SvgIcon localIcon="dflt" class="text-44px" />;
  }
  return null;
}

// 处理文件预览
function handleFilePreview(fileName: string) {
  previewFileName.value = fileName;
  previewVisible.value = true;
}

// 关闭文件预览
function closeFilePreview() {
  previewVisible.value = false;
  previewFileName.value = '';
}

function handleChunkView(row: Api.KnowledgeBase.UploadTask) {
  chunkFileMd5.value = row.fileMd5;
  chunkFileName.value = row.fileName;
  chunkActualParseEngine.value = row.actualParseEngine ?? row.parseEngine ?? null;
  chunkVisible.value = true;
}

function getFileActionOptions(row: Api.KnowledgeBase.UploadTask): DropdownOption[] {
  return [
    {
      label: '查看切片',
      key: 'chunks',
      disabled: row.status !== UploadStatus.Completed
    },
    {
      label: '删除文件',
      key: 'delete'
    }
  ];
}

function handleFileAction(key: string, row: Api.KnowledgeBase.UploadTask) {
  if (key === 'chunks') {
    handleChunkView(row);
    return;
  }

  if (key === 'delete') confirmDelete(row);
}

function confirmDelete(row: Api.KnowledgeBase.UploadTask) {
  window.$dialog?.warning({
    title: '删除文件',
    content: `确认删除“${row.fileName}”吗？`,
    positiveText: '删除',
    negativeText: '取消',
    positiveButtonProps: {
      type: 'error'
    },
    onPositiveClick: () => handleDelete(row.fileMd5)
  });
}

const {
  columns,
  columnChecks,
  data,
  getData,
  getDataByPage,
  loading,
  reloadColumns,
  searchParams,
  resetSearchParams
} = useTable({
  apiFn,
  apiParams: {
    filterOrgTags: null,
    isPublic: null
  },
  immediate: false,
  columns: () => [
    {
      key: 'fileName',
      title: '文件名',
      minWidth: 460,
      render: row => (
        <div class="flex items-center gap-12px py-6px">
          <div class="h-52px w-44px shrink-0 flex-center overflow-visible">{renderIcon(row.fileName)}</div>
          <div class="min-w-0 flex-1">
            <NEllipsis lineClamp={2} tooltip>
              <span
                class="cursor-pointer text-14px text-#1f2937 transition-colors hover:text-primary"
                onClick={() => handleFilePreview(row.fileName)}
              >
                {row.fileName}
              </span>
            </NEllipsis>
            <div class="mt-4px flex items-center gap-8px text-12px text-#8a8f99">
              <span>{fileSize(row.totalSize)}</span>
              <span class="h-3px w-3px rd-full bg-#d0d5dd" />
              <span>{formatParseEngine(row.actualParseEngine ?? row.parseEngine)}</span>
            </div>
          </div>
        </div>
      )
    },
    {
      key: 'status',
      title: '状态',
      width: 240,
      render: row => renderPipelineStatus(row)
    },
    {
      key: 'processingDurationMillis',
      title: '处理',
      width: 170,
      sorter: true,
      sortOrder: sortState.value.columnKey === 'processingDurationMillis' ? sortState.value.order : false,
      render: row => (
        <div class="leading-5">
          <div class="text-14px text-#1f2937">{formatDuration(resolveProcessingDuration(row, durationNow.value))}</div>
          <div class="mt-2px text-12px text-#8a8f99">{formatProcessingSummary(row)}</div>
        </div>
      )
    },
    {
      key: 'uploaderName',
      title: '归属',
      width: 230,
      render: row => renderOwnership(row)
    },
    {
      key: 'createdAt',
      title: '上传时间',
      width: 170,
      sorter: true,
      sortOrder: sortState.value.columnKey === 'createdAt' ? sortState.value.order : false,
      render: row => dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss')
    },
    {
      key: 'operate',
      title: '操作',
      fixed: 'right',
      width: 170,
      render: row => (
        <div class="flex flex-nowrap items-center gap-8px">
          {renderResumeUploadButton(row)}
          <NButton
            type="primary"
            ghost
            size="small"
            onClick={() => handleFilePreview(row.fileName)}
          >
            预览
          </NButton>
          <NDropdown
            trigger="click"
            options={getFileActionOptions(row)}
            onSelect={key => handleFileAction(String(key), row)}
          >
            <NButton size="small" quaternary>
              更多
            </NButton>
          </NDropdown>
        </div>
      )
    }
  ]
});

const store = useKnowledgeBaseStore();
const { tasks } = storeToRefs(store);
const durationNow = ref(Date.now());
const sortedTasks = computed(() => {
  const sorted = [...tasks.value];
  const { columnKey, order } = sortState.value;

  sorted.sort((a, b) => {
    const result = compareTasksByColumn(a, b, columnKey, order);
    if (result !== 0) return result;

    return compareTasksByColumn(a, b, 'createdAt', 'descend');
  });

  return sorted;
});
const activeFilterCount = computed(() => {
  let count = 0;
  if (orgTagFilter.value.length > 0) count += 1;
  if (visibilityFilter.value !== 'all') count += 1;
  return count;
});
const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);
let durationRefreshTimer: ReturnType<typeof setInterval> | null = null;
let statusEventSource: EventSource | null = null;
onMounted(async () => {
  await loadOrgTagOptions();
  await getList();
  startProcessingStatusEvents();
  durationRefreshTimer = setInterval(() => {
    durationNow.value = Date.now();
  }, 1000);
});

onUnmounted(() => {
  if (durationRefreshTimer) clearInterval(durationRefreshTimer);
  statusEventSource?.close();
  statusEventSource = null;
});

watch(sortState, () => {
  reloadColumns();
});

async function loadOrgTagOptions() {
  if (authStore.isAdmin) {
    const { error, data } = await fetchGetOrgTagList();
    if (!error) orgTagOptions.value = flattenOrgTags(data.data);
    return;
  }

  const { error, data } = await request<Api.OrgTag.Mine>({ url: '/users/org-tags' });
  if (!error) {
    orgTagOptions.value = data.orgTagDetails.map(tag => ({
      tagId: tag.tagId,
      name: tag.name,
      description: tag.description,
      parentTag: null
    }));
  }
}

function flattenOrgTags(tags: Api.OrgTag.Item[] = []): Api.OrgTag.Item[] {
  return tags.flatMap(tag => [tag, ...flattenOrgTags(tag.children || [])]);
}

function handleSorterUpdate(sorter: DataTableSortState | DataTableSortState[] | null) {
  const nextSorter = Array.isArray(sorter) ? sorter[0] : sorter;

  if (!isSortableColumnKey(nextSorter?.columnKey) || !nextSorter.order) {
    sortState.value = {
      columnKey: 'createdAt',
      order: 'descend'
    };
    return;
  }

  sortState.value = {
    columnKey: nextSorter.columnKey,
    order: nextSorter.order
  };
}

function isSortableColumnKey(key: unknown): key is SortableColumnKey {
  return key === 'processingDurationMillis' || key === 'createdAt';
}

function compareTasksByColumn(
  a: Api.KnowledgeBase.UploadTask,
  b: Api.KnowledgeBase.UploadTask,
  columnKey: SortableColumnKey,
  order: ActiveSortState['order']
) {
  const direction = order === 'ascend' ? 1 : -1;

  if (columnKey === 'processingDurationMillis') {
    return (
      compareNullableNumbers(resolveProcessingDuration(a, durationNow.value), resolveProcessingDuration(b, durationNow.value)) *
      direction
    );
  }

  return compareNullableNumbers(parseDate(a.createdAt), parseDate(b.createdAt)) * direction;
}

function compareNullableNumbers(a: number | null | undefined, b: number | null | undefined) {
  const hasA = typeof a === 'number' && Number.isFinite(a);
  const hasB = typeof b === 'number' && Number.isFinite(b);

  if (!hasA && !hasB) return 0;
  if (!hasA) return 1;
  if (!hasB) return -1;
  return a - b;
}

/** 异步获取列表函数 该函数主要用于更新或初始化上传任务列表 它首先调用getData函数获取数据，然后根据获取到的数据状态更新任务列表 */
async function getList(pageNum?: number) {
  // 等待获取最新数据
  if (pageNum) await getDataByPage(pageNum);
  else await getData();

  const previousTasks = [...tasks.value];
  const nextTasks = data.value.map(item => {
    const previousTask = previousTasks.find(task => task.fileMd5 === item.fileMd5);

    if (item.status === UploadStatus.Completed) {
      return previousTask ? Object.assign(previousTask, item) : item;
    }

    item.status = UploadStatus.Break;
    return previousTask ? Object.assign(previousTask, item) : item;
  });

  const serverFileMd5Set = new Set(nextTasks.map(item => item.fileMd5));
  const localUploadTasks = previousTasks.filter(task => {
    if (serverFileMd5Set.has(task.fileMd5)) return false;
    if (task.status === UploadStatus.Completed || task.status === UploadStatus.Break) return false;
    return matchesActiveFilters(task);
  });

  tasks.value = [...localUploadTasks, ...nextTasks];
}

async function handleFilterSearch() {
  searchParams.filterOrgTags = orgTagFilter.value.length > 0 ? orgTagFilter.value.map(String).join(',') : null;
  searchParams.isPublic = visibilityFilter.value === 'all' ? null : visibilityFilter.value === 'public';
  await getList(1);
}

async function handleFilterReset() {
  resetSearchParams();
  orgTagFilter.value = [];
  visibilityFilter.value = 'all';
  selectedOrgTagNames.value = {};
  await getList(1);
}

async function handleFilterTagClose(key: 'orgTag' | 'isPublic') {
  if (key === 'orgTag') {
    orgTagFilter.value = [];
    selectedOrgTagNames.value = {};
  } else {
    visibilityFilter.value = 'all';
  }
  await handleFilterSearch();
}

async function handleSingleOrgTagClose(tagId: string | number) {
  orgTagFilter.value = orgTagFilter.value.filter(item => item !== tagId);
  const nextNames = { ...selectedOrgTagNames.value };
  delete nextNames[String(tagId)];
  selectedOrgTagNames.value = nextNames;
  await handleFilterSearch();
}

async function handleOrgTagFilter(tagId: string | null, tagName?: string | null) {
  if (!tagId) return;

  orgTagFilter.value = [tagId];
  selectedOrgTagNames.value = { [tagId]: tagName || tagId };
  await handleFilterSearch();
}

async function handleVisibilityFilterUpdate(value: 'all' | 'public' | 'private') {
  visibilityFilter.value = value;
  await handleFilterSearch();
}

async function toggleOrgTagFilter(tag: Api.OrgTag.Item) {
  const isSelected = orgTagFilter.value.map(String).includes(tag.tagId);

  if (isSelected) {
    orgTagFilter.value = orgTagFilter.value.filter(item => String(item) !== tag.tagId);
    const nextNames = { ...selectedOrgTagNames.value };
    delete nextNames[tag.tagId];
    selectedOrgTagNames.value = nextNames;
  } else {
    orgTagFilter.value = [...orgTagFilter.value, tag.tagId];
    selectedOrgTagNames.value = {
      ...selectedOrgTagNames.value,
      [tag.tagId]: tag.name
    };
  }

  await handleFilterSearch();
}

function isOrgTagSelected(tagId: string) {
  return orgTagFilter.value.map(String).includes(tagId);
}

function matchesActiveFilters(task: Api.KnowledgeBase.UploadTask) {
  const selectedOrgTags = orgTagFilter.value.map(String);
  if (selectedOrgTags.length > 0 && (!task.orgTag || !selectedOrgTags.includes(task.orgTag))) return false;

  if (visibilityFilter.value === 'all') return true;
  const isPublic = task.public || task.isPublic;
  return visibilityFilter.value === 'public' ? isPublic : !isPublic;
}

function renderOwnership(row: Api.KnowledgeBase.UploadTask) {
  const tagName = row.orgTagName || '默认组织';
  const isPublic = row.public || row.isPublic;

  return (
    <div class="leading-5">
      <div class="text-14px text-#1f2937">{row.uploaderName || row.userId || '-'}</div>
      <div class="mt-3px flex min-w-0 flex-wrap items-center gap-6px text-12px text-#8a8f99">
        {row.orgTag ? (
          <NTooltip>
            {{
              trigger: () => (
                <span class="inline-flex cursor-pointer" onClick={() => handleOrgTagFilter(row.orgTag, row.orgTagName)}>
                  <NTag size="small" type="info" bordered={false} class="max-w-126px">
                    <NEllipsis tooltip={false}>{tagName}</NEllipsis>
                  </NTag>
                </span>
              ),
              default: () => `筛选：${tagName}`
            }}
          </NTooltip>
        ) : (
          <NTag size="small" bordered={false}>
            {tagName}
          </NTag>
        )}
        {isPublic ? <NTag size="small" type="success">公开</NTag> : <NTag size="small" type="warning">私有</NTag>}
      </div>
    </div>
  );
}

async function handleDelete(fileMd5: string) {
  const index = tasks.value.findIndex(task => task.fileMd5 === fileMd5);

  if (index !== -1) {
    tasks.value[index].requestIds?.forEach(requestId => {
      request.cancelRequest(requestId);
    });
  }

  // 如果文件一个分片也没有上传完成，则直接删除
  if (tasks.value[index].uploadedChunks && tasks.value[index].uploadedChunks.length === 0) {
    tasks.value.splice(index, 1);
    return;
  }

  const { error } = await request({ url: `/documents/${fileMd5}`, method: 'DELETE' });
  if (!error) {
    tasks.value.splice(index, 1);
    window.$message?.success('删除成功');
    await getData();
  }
}

// #region 文件上传
const uploadVisible = ref(false);
function handleUpload() {
  uploadVisible.value = true;
}
// #endregion

// #region 检索知识库
const searchVisible = ref(false);
function handleSearch() {
  searchVisible.value = true;
}
// #endregion

function renderPipelineStatus(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) return <NTag type="error">上传中断</NTag>;

  if (row.processingState === 'FAILED') {
    const stageText = processingFailureText(row.processingStage);
    return (
      <NTooltip>
        {{
          trigger: () => <NTag type="error">{stageText}</NTag>,
          default: () => row.processingError || row.processingMessage || '处理失败'
        }}
      </NTooltip>
    );
  }

  if (row.processingState === 'SUCCEEDED') return <NTag type="success">已入库</NTag>;

  if (row.status !== UploadStatus.Completed) {
    return renderStatusProgress('上传中', normalizePercentage(row.progress));
  }

  return renderStatusProgress(processingStageActionText(row.processingStage), processingStageProgress(row.processingStage));
}

function renderStatusProgress(label: string, percentage: number) {
  return (
    <div class="max-w-180px">
      <div class="mb-4px text-12px text-#5f6673">{label}</div>
      <NProgress type="line" percentage={percentage} processing height={8} indicatorPlacement="inside" />
    </div>
  );
}

function normalizePercentage(value?: number | null) {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(100, Math.round(value)));
}

function processingStageProgress(stage: Api.KnowledgeBase.UploadTask['processingStage']) {
  const record: Record<string, number> = {
    QUEUED: 10,
    PARSING: 35,
    CHUNKING: 55,
    VECTORIZING: 75,
    INDEXING: 90,
    COMPLETED: 98,
    FAILED: 100
  };
  return stage ? record[stage] || 20 : 8;
}

function processingStageActionText(stage: Api.KnowledgeBase.UploadTask['processingStage']) {
  const record: Record<string, string> = {
    QUEUED: '等待解析',
    PARSING: '解析中',
    CHUNKING: '切片中',
    VECTORIZING: '向量化中',
    INDEXING: '入库中',
    COMPLETED: '入库中',
    FAILED: '处理失败'
  };
  return stage ? record[stage] || '处理中' : '解析中';
}

function processingFailureText(stage: Api.KnowledgeBase.UploadTask['processingStage']) {
  const record: Record<string, string> = {
    QUEUED: '等待处理失败',
    PARSING: '解析失败',
    CHUNKING: '切片失败',
    VECTORIZING: '向量化失败',
    INDEXING: '入库失败',
    COMPLETED: '入库失败',
    FAILED: '处理失败'
  };
  return stage ? record[stage] || '处理失败' : '处理失败';
}

function processingStageText(stage: Api.KnowledgeBase.UploadTask['processingStage']) {
  const record: Record<string, string> = {
    QUEUED: '等待处理',
    PARSING: '解析中',
    CHUNKING: '切片完成',
    VECTORIZING: '向量化中',
    INDEXING: '入库中',
    COMPLETED: '处理完成',
    FAILED: '处理'
  };
  return stage ? record[stage] || stage : '未开始';
}

function startProcessingStatusEvents() {
  const token = getToken();
  if (!token || statusEventSource) return;

  const query = new URLSearchParams({ token });
  statusEventSource = new EventSource(`${baseURL}/upload/status/events?${query.toString()}`);

  statusEventSource.addEventListener('connected', () => {
    refreshProcessingStatusesSilently();
  });
  statusEventSource.addEventListener('processing-status', event => {
    const data = JSON.parse((event as MessageEvent).data) as Api.KnowledgeBase.ProcessingStatus;
    applyProcessingStatus(data);
  });
  statusEventSource.onerror = () => {
    refreshProcessingStatusesSilently();
  };
}

async function refreshProcessingStatusesSilently() {
  const activeTasks = tasks.value.filter(task => {
    if (task.status !== UploadStatus.Completed) return false;
    return !task.processingState || task.processingState === 'PENDING' || task.processingState === 'RUNNING';
  });

  await Promise.all(activeTasks.map(refreshProcessingStatusSilently));
}

async function refreshProcessingStatusSilently(task: Api.KnowledgeBase.UploadTask) {
  const { error, data } = await request<Api.KnowledgeBase.ProcessingStatus>({
    url: '/upload/status/processing',
    params: { file_md5: task.fileMd5 }
  });
  if (error || !data) return;

  task.processingStage = data.processingStage;
  task.processingState = data.processingState;
  task.processingMessage = data.processingMessage;
  task.processingError = data.processingError;
  task.parseEngine = data.parseEngine ?? task.parseEngine;
  task.actualParseEngine = data.actualParseEngine ?? task.actualParseEngine ?? task.parseEngine;
  task.actualChunkSize = data.actualChunkSize ?? task.actualChunkSize ?? task.chunkSize;
  task.processingDurationMillis = data.processingDurationMillis ?? task.processingDurationMillis;
  task.processingStartedAt = data.processingStartedAt ?? task.processingStartedAt;
  task.processingUpdatedAt = data.processingUpdatedAt ?? task.processingUpdatedAt;
  task.processingCompletedAt = data.processingCompletedAt ?? task.processingCompletedAt;
  task.serverTime = data.serverTime ?? task.serverTime;
  task.esDocumentCount = data.esDocumentCount;
  if (typeof data.parsedChunkCount === 'number') task.parsedChunkCount = data.parsedChunkCount;
  if (typeof data.vectorizedCount === 'number') task.vectorizedCount = data.vectorizedCount;
  if (typeof data.dbChunkCount === 'number') task.parsedChunkCount = data.dbChunkCount;
  if (typeof data.esDocumentCount === 'number') task.vectorizedCount = data.esDocumentCount;
}

function applyProcessingStatus(status: Api.KnowledgeBase.ProcessingStatus) {
  if (!status.fileMd5) return;
  const task = tasks.value.find(item => item.fileMd5 === status.fileMd5);
  if (!task) {
    refreshProcessingStatusesSilently();
    return;
  }

  task.processingStage = status.processingStage;
  task.processingState = status.processingState;
  task.processingMessage = status.processingMessage;
  task.processingError = status.processingError;
  task.parseEngine = status.parseEngine ?? task.parseEngine;
  task.actualParseEngine = status.actualParseEngine ?? task.actualParseEngine ?? task.parseEngine;
  task.actualChunkSize = status.actualChunkSize ?? task.actualChunkSize ?? task.chunkSize;
  task.processingDurationMillis = status.processingDurationMillis ?? task.processingDurationMillis;
  task.processingStartedAt = status.processingStartedAt ?? task.processingStartedAt;
  task.processingUpdatedAt = status.processingUpdatedAt ?? task.processingUpdatedAt;
  task.processingCompletedAt = status.processingCompletedAt ?? task.processingCompletedAt;
  task.serverTime = status.serverTime ?? task.serverTime;
  task.esDocumentCount = status.esDocumentCount ?? task.esDocumentCount;
  if (typeof status.parsedChunkCount === 'number') task.parsedChunkCount = status.parsedChunkCount;
  if (typeof status.vectorizedCount === 'number') task.vectorizedCount = status.vectorizedCount;
  if (typeof status.dbChunkCount === 'number') task.parsedChunkCount = status.dbChunkCount;
  if (typeof status.esDocumentCount === 'number') task.vectorizedCount = status.esDocumentCount;
}

function resolveProcessingDuration(row: Api.KnowledgeBase.UploadTask, now: number) {
  const startedAt = parseDate(row.processingStartedAt);
  if (startedAt) {
    const completedAt = parseDate(row.processingCompletedAt);
    const failedAt = row.processingState === 'FAILED' ? parseDate(row.processingUpdatedAt) : null;
    const endedAt = completedAt ?? failedAt ?? now;
    return Math.max(0, endedAt - startedAt);
  }

  return row.processingDurationMillis;
}

function parseDate(value?: string | null) {
  if (!value) return null;
  const time = dayjs(value).valueOf();
  return Number.isFinite(time) ? time : null;
}

function formatDuration(milliseconds?: number | null) {
  if (typeof milliseconds !== 'number' || milliseconds < 0) return '-';
  const totalSeconds = Math.floor(milliseconds / 1000);
  if (totalSeconds < 1) return '0s';
  if (totalSeconds < 60) return `${totalSeconds}s`;

  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes < 60) return seconds > 0 ? `${minutes}m${seconds}s` : `${minutes}m`;

  const hours = Math.floor(minutes / 60);
  const remainingMinutes = minutes % 60;
  const parts = [`${hours}h`];
  if (remainingMinutes > 0) parts.push(`${remainingMinutes}m`);
  if (seconds > 0) parts.push(`${seconds}s`);
  return parts.join('');
}

function formatParseEngine(engine?: Api.KnowledgeBase.UploadTask['actualParseEngine'] | Api.KnowledgeBase.UploadTask['parseEngine']) {
  const record: Record<string, string> = {
    AUTO: '自动解析',
    TIKA: 'Tika',
    MINERU: 'MinerU'
  };
  return engine ? record[engine] || engine : '自动解析';
}

function formatProcessingSummary(row: Api.KnowledgeBase.UploadTask) {
  if (typeof row.parsedChunkCount === 'number') return `${row.parsedChunkCount} 个切片`;
  if (typeof row.vectorizedCount === 'number') return `${row.vectorizedCount} 个向量`;
  if (typeof row.esDocumentCount === 'number') return `${row.esDocumentCount} 条索引`;
  return processingStageText(row.processingStage);
}

// #region 文件续传
function renderResumeUploadButton(row: Api.KnowledgeBase.UploadTask) {
  if (row.status === UploadStatus.Break) {
    if (row.file)
      return (
        <NButton type="primary" size="small" ghost onClick={() => resumeUpload(row)}>
          续传
        </NButton>
      );
    return (
      <NUpload
        show-file-list={false}
        default-upload={false}
        accept={uploadAccept}
        onBeforeUpload={options => onBeforeUpload(options, row)}
        class="w-fit"
      >
        <NButton type="primary" size="small" ghost>
          续传
        </NButton>
      </NUpload>
    );
  }
  return null;
}

// 任务列表存在文件，直接续传
function resumeUpload(row: Api.KnowledgeBase.UploadTask) {
  row.status = UploadStatus.Pending;
  store.startUpload();
}

async function onBeforeUpload(
  options: { file: UploadFileInfo; fileList: UploadFileInfo[] },
  row: Api.KnowledgeBase.UploadTask
) {
  const md5 = await calculateMD5(options.file.file!);
  if (md5 !== row.fileMd5) {
    window.$message?.error('两次上传的文件不一致');
    return false;
  }
  loading.value = true;
  const { error, data: progress } = await request<Api.KnowledgeBase.Progress>({
    url: '/upload/status',
    params: { file_md5: row.fileMd5 }
  });
  if (!error) {
    row.file = options.file.file!;
    row.status = UploadStatus.Pending;
    row.progress = progress.progress;
    row.uploadedChunks = progress.uploaded;
    store.startUpload();
    loading.value = false;
    return true;
  }
  loading.value = false;
  return false;
}
</script>

<template>
  <div class="min-h-500px flex-col-stretch gap-16px overflow-hidden lt-sm:overflow-auto">
    <NCard title="文件列表" :bordered="false" size="small" class="sm:flex-1-hidden card-wrapper knowledge-file-card">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @add="handleUpload" @refresh="getList">
          <template #prefix>
            <NButton size="small" ghost type="primary" @click="handleSearch">
              <template #icon>
                <icon-ic-round-search class="text-icon" />
              </template>
              检索知识库
            </NButton>
          </template>
        </TableHeaderOperation>
      </template>
      <div class="knowledge-file-filter mb-14px flex flex-col gap-10px">
        <div class="filter-row">
          <span class="filter-label">组织标签</span>
          <div class="filter-options">
            <NButton
              v-for="tag in orgTagOptions"
              :key="tag.tagId"
              size="small"
              :type="isOrgTagSelected(tag.tagId) ? 'primary' : 'default'"
              :ghost="!isOrgTagSelected(tag.tagId)"
              @click="toggleOrgTagFilter(tag)"
            >
              {{ tag.name }}
            </NButton>
          </div>
        </div>
        <div class="filter-row">
          <span class="filter-label">可见范围</span>
          <div class="filter-options">
            <NButton
              v-for="option in visibilityOptions"
              :key="option.value"
              size="small"
              :type="visibilityFilter === option.value ? 'primary' : 'default'"
              :ghost="visibilityFilter !== option.value"
              @click="handleVisibilityFilterUpdate(option.value as 'all' | 'public' | 'private')"
            >
              {{ option.label }}
            </NButton>
          </div>
          <NButton v-if="activeFilterCount > 0" size="small" quaternary @click="handleFilterReset">
            重置
          </NButton>
        </div>
        <div v-if="activeFilterCount > 0" class="knowledge-active-filters flex min-w-0 items-center gap-8px text-12px text-#8a8f99">
          <span>已筛选</span>
          <NTag
            v-for="tagId in orgTagFilter"
            :key="tagId"
            size="small"
            closable
            @close="handleSingleOrgTagClose(tagId)"
          >
            {{ selectedOrgTagNames[String(tagId)] || tagId }}
          </NTag>
          <NTag v-if="visibilityFilter !== 'all'" size="small" closable @close="handleFilterTagClose('isPublic')">
            {{ visibilityFilter === 'public' ? '公开' : '私有' }}
          </NTag>
        </div>
      </div>
      <NDataTable
        striped
        :columns="columns"
        :data="sortedTasks"
        size="small"
        :flex-height="!appStore.isMobile"
        :scroll-x="1440"
        :loading="loading"
        remote
        :row-key="row => row.id"
        :pagination="false"
        @update:sorter="handleSorterUpdate"
        class="knowledge-file-table sm:h-full"
      />
    </NCard>
    <UploadDialog v-model:visible="uploadVisible" />
    <SearchDialog v-model:visible="searchVisible" />
    <ChunkDialog
      v-model:visible="chunkVisible"
      :file-md5="chunkFileMd5"
      :file-name="chunkFileName"
      :actual-parse-engine="chunkActualParseEngine"
    />
    
    <!-- 文件预览弹窗 -->
    <NModal
      v-model:show="previewVisible"
      preset="card"
      :title="previewFileName || '文件预览'"
      class="file-preview-modal"
      :style="{ width: 'min(1280px, 92vw)', height: 'min(900px, 92vh)', maxWidth: '1280px' }"
      @after-leave="closeFilePreview"
    >
      <FilePreview
        :file-name="previewFileName"
        :visible="previewVisible"
      />
    </NModal>
  </div>
</template>

<style scoped lang="scss">
.file-list-container {
  transition: width 0.3s ease;
}

:deep() {
  .n-progress-icon.n-progress-icon--as-text {
    white-space: nowrap;
  }
}

:deep(.knowledge-file-card > .n-card__content) {
  display: flex;
  min-height: 0;
  flex-direction: column;
  padding-bottom: 16px;
}

.knowledge-file-filter {
  flex-shrink: 0;
}

.filter-row {
  display: flex;
  align-items: flex-start;
  gap: 10px;
}

.filter-label {
  flex-shrink: 0;
  width: 64px;
  padding-top: 5px;
  color: #5f6673;
  font-size: 13px;
  line-height: 22px;
}

.filter-options {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-wrap: wrap;
  gap: 8px;
}

.knowledge-active-filters {
  max-width: min(680px, 100%);
  overflow-x: auto;
  white-space: nowrap;
}

.knowledge-active-filters :deep(.n-tag) {
  flex-shrink: 0;
}

.knowledge-file-table {
  min-height: 0;
  flex: 1;
}

:global(.file-preview-modal) {
  max-height: 92vh;
  display: flex;
  flex-direction: column;
}

:global(.file-preview-modal > .n-card-header) {
  flex-shrink: 0;
}

:global(.file-preview-modal > .n-card__content) {
  flex: 1;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
</style>
