import { REQUEST_ID_KEY } from '~/packages/axios/src';
import { nanoid } from '~/packages/utils/src';

const maxChunkUploadsPerFile = 3;

export const useKnowledgeBaseStore = defineStore(SetupStoreId.KnowledgeBase, () => {
  const authStore = useAuthStore();
  const tasks = ref<Api.KnowledgeBase.UploadTask[]>([]);
  const activeUploads = ref<Set<string>>(new Set());
  const cancelledTasks = new WeakSet<Api.KnowledgeBase.UploadTask>();

  function cancelUpload(fileMd5: string) {
    const task = tasks.value.find(item => item.fileMd5 === fileMd5);
    if (!task) return;
    cancelledTasks.add(task);
    if (task.status !== UploadStatus.Completed) task.status = UploadStatus.Break;
    task.requestIds?.forEach(requestId => request.cancelRequest(requestId));
  }

  async function uploadChunk(task: Api.KnowledgeBase.UploadTask, chunkIndex: number): Promise<boolean> {
    if (cancelledTasks.has(task)) return false;
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    const chunkStart = chunkIndex * chunkSize;
    const chunkEnd = Math.min(chunkStart + chunkSize, task.totalSize);
    const chunk = task.file.slice(chunkStart, chunkEnd);

    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    const { error, data } = await request<Api.KnowledgeBase.Progress>({
      url: '/upload/chunk',
      method: 'POST',
      data: {
        file: chunk,
        fileMd5: task.fileMd5,
        uploadGeneration: task.uploadGeneration,
        chunkIndex,
        totalSize: task.totalSize,
        fileName: task.fileName,
        orgTag: task.orgTag,
        isPublic: task.isPublic ?? false,
        graphEnabled: task.graphEnabled ?? false,
        graphPromptTemplateId: task.graphPromptTemplateId
      },
      headers: {
        'Content-Type': 'multipart/form-data',
        [REQUEST_ID_KEY]: requestId
      },
      timeout: 10 * 60 * 1000
    });

    task.requestIds = task.requestIds.filter(id => id !== requestId);

    if (error || cancelledTasks.has(task)) return false;

    // 更新任务状态
    const updatedTask = tasks.value.find(t => t.fileMd5 === task.fileMd5);
    if (!updatedTask) return false;
    const uploadedChunkSet = new Set([...updatedTask.uploadedChunks, ...data.uploaded]);
    updatedTask.uploadedChunks = [...uploadedChunkSet].sort((a, b) => a - b);
    updatedTask.progress = Number.parseFloat(((updatedTask.uploadedChunks.length / totalChunks) * 100).toFixed(2));

    return true;
  }

  async function mergeFile(task: Api.KnowledgeBase.UploadTask) {
    if (cancelledTasks.has(task)) return false;
    const requestId = nanoid();
    task.requestIds ??= [];
    task.requestIds.push(requestId);
    try {
      const { error } = await request({
        url: '/upload/merge',
        headers: { [REQUEST_ID_KEY]: requestId },
        method: 'POST',
        data: { fileMd5: task.fileMd5, fileName: task.fileName, parseEngine: task.parseEngine, chunkSize: task.chunkSize, graphBatchChars: task.graphBatchChars, uploadGeneration: task.uploadGeneration }
      });
      if (error || cancelledTasks.has(task)) return false;

      // 更新任务状态为已完成
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      if (index === -1) return false;
      tasks.value[index].status = UploadStatus.Completed;
      tasks.value[index].processingStage = 'QUEUED';
      tasks.value[index].processingState = 'PENDING';
      tasks.value[index].processingMessage = '等待处理';
      return true;
    } catch {
      return false;
    } finally {
      task.requestIds = task.requestIds?.filter(id => id !== requestId);
    }
  }

  /**
   * 异步函数：将上传请求加入队列
   *
   * 本函数处理上传任务的排队和初始化工作它首先检查是否存在相同的文件， 如果不存在，则创建一个新的上传任务，并将其添加到任务队列中最后启动上传流程
   *
   * @param form 包含上传信息的表单，包括文件列表和是否公开的标签
   * @returns 返回一个上传任务对象，无论是已存在的还是新创建的
   */
  async function enqueueUpload(form: Api.KnowledgeBase.Form) {
    // 获取文件列表中的第一个文件
    const file = form.fileList![0].file!;
    // 计算文件的MD5值，用于唯一标识文件
    const md5 = await calculateMD5(file);

    // 检查是否已存在相同文件
    const existingTask = tasks.value.find(t => t.fileMd5 === md5);
    if (existingTask) {
      // 如果存在相同文件，直接返回该上传任务
      if (existingTask.status === UploadStatus.Completed) {
        window.$message?.error('文件已存在');
        return;
      } else if (existingTask.status === UploadStatus.Pending || existingTask.status === UploadStatus.Uploading) {
        window.$message?.error('文件正在上传中');
        return;
      } else if (existingTask.status === UploadStatus.Break) {
        existingTask.status = UploadStatus.Pending;
        startUpload();
        return;
      }
    }

    const { data: generationData, error: generationError } = await request<{ generation: number }>({
      url: '/upload/generation', params: { fileMd5: md5 }
    });
    if (generationError) return;

    // 创建新的上传任务对象
    const newTask: Api.KnowledgeBase.UploadTask = {
      file,
      chunk: null,
      chunkIndex: 0,
      fileMd5: md5,
      uploadGeneration: generationData.generation,
      fileName: file.name,
      totalSize: file.size,
      userId: authStore.userInfo.id ? String(authStore.userInfo.id) : undefined,
      uploaderName: authStore.userInfo.displayName || authStore.userInfo.username || undefined,
      public: form.isPublic,
      isPublic: form.isPublic,
      parseEngine: form.parseEngine,
      chunkSize: form.chunkSize,
      graphEnabled: form.graphEnabled,
      graphPromptTemplateId: form.graphPromptTemplateId,
      graphBatchChars: form.graphBatchChars,
      uploadedChunks: [],
      progress: 0,
      status: UploadStatus.Pending,
      orgTag: form.orgTag
    };

    newTask.orgTagName = form.orgTagName ?? null;

    // 将新的上传任务添加到任务队列中
    tasks.value.push(newTask);
    // 启动上传流程
    startUpload();
    // 返回新的上传任务
  }

  /** 启动文件上传的异步函数 该函数负责从待上传队列中启动文件上传任务，并管理并发上传的数量 */
  async function startUpload() {
    // 限制可同时上传的文件个数
    if (activeUploads.value.size >= 3) return;
    // 获取待上传的文件
    const pendingTasks = tasks.value.filter(
      t => t.status === UploadStatus.Pending && !activeUploads.value.has(t.fileMd5)
    );

    // 如果没有待上传的文件，则直接返回
    if (pendingTasks.length === 0) return;

    // 获取第一个待上传的文件
    const task = pendingTasks[0];
    cancelledTasks.delete(task);
    task.status = UploadStatus.Uploading;
    activeUploads.value.add(task.fileMd5);

    // 计算文件总片数
    const totalChunks = Math.ceil(task.totalSize / chunkSize);

    try {
      if (task.uploadGeneration === undefined) {
        const { data, error } = await request<{ generation: number }>({
          url: '/upload/generation', params: { fileMd5: task.fileMd5 }
        });
        if (error || cancelledTasks.has(task)) return;
        task.uploadGeneration = data.generation;
      }
      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
        return;
      }
      if (!task.uploadedChunks.includes(0)) {
        const success = await uploadChunk(task, 0);
        if (!success) throw new Error('分片上传失败');
      }

      const pendingChunks = Array.from({ length: totalChunks }, (_, index) => index).filter(
        chunkIndex => chunkIndex !== 0 && !task.uploadedChunks.includes(chunkIndex)
      );

      await runWithConcurrency(pendingChunks, maxChunkUploadsPerFile, async chunkIndex => {
        const success = await uploadChunk(task, chunkIndex);
        if (!success) throw new Error('分片上传失败');
      });

      if (task.uploadedChunks.length === totalChunks) {
        const success = await mergeFile(task);
        if (!success) throw new Error('文件合并失败');
      } else {
        throw new Error('分片上传未完成');
      }
    } catch (e) {
      if (cancelledTasks.has(task)) return;
      console.error('文件上传失败', e);
      // 如果上传失败，则将任务状态设置为中断
      const index = tasks.value.findIndex(t => t.fileMd5 === task.fileMd5);
      if (index !== -1) tasks.value[index].status = UploadStatus.Break;
    } finally {
      // 无论成功或失败，都从活跃队列中移除
      activeUploads.value.delete(task.fileMd5);
      // 继续下一个任务
      startUpload();
    }
  }

  async function runWithConcurrency<T>(
    items: T[],
    concurrency: number,
    worker: (item: T) => Promise<void>
  ): Promise<void> {
    let nextIndex = 0;
    const workerCount = Math.min(concurrency, items.length);

    await Promise.all(
      Array.from({ length: workerCount }, async () => {
        while (nextIndex < items.length) {
          const item = items[nextIndex];
          nextIndex += 1;
          await worker(item);
        }
      })
    );
  }

  return {
    tasks,
    activeUploads,
    cancelUpload,
    enqueueUpload,
    startUpload
  };
});
