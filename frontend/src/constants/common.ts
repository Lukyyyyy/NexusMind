import { transformRecordToOption } from '@/utils/common';

export const yesOrNoRecord: Record<CommonType.YesOrNo, App.I18n.I18nKey> = {
  Y: 'common.yesOrNo.yes',
  N: 'common.yesOrNo.no'
};

export const yesOrNoOptions = transformRecordToOption(yesOrNoRecord);

export const enableStatusOptions = [
  { label: '启用', value: 1 },
  { label: '禁用', value: 0 }
];

export const chunkSize = 5 * 1024 * 1024;

export const textChunkSizeMin = 256;

export const textChunkSizeMax = 4096;

export const defaultTextChunkSize = 512;

export const textChunkSizeOptions = [
  { label: '精细', value: 256 },
  { label: '标准', value: 512 },
  { label: '长上下文', value: 1024 }
];

export const uploadAccept = '.pdf,.doc,.docx,.txt';

export const parseEngineOptions = [
  { label: '自动选择（推荐）', value: 'AUTO' },
  { label: '快速解析（Apache Tika）', value: 'TIKA' },
  { label: '高精度解析（MinerU）', value: 'MINERU' }
];
