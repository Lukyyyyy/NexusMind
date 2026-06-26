import { request } from '../request';

export function fetchModelConfigOverview() {
  return request<Api.ModelConfig.Overview>({
    url: '/model-config'
  });
}

export function createModelConfig(data: Api.ModelConfig.Request) {
  return request<Api.ModelConfig.Item>({
    url: '/model-config',
    method: 'post',
    data
  });
}

export function updateModelConfig(id: number, data: Api.ModelConfig.Request) {
  return request<Api.ModelConfig.Item>({
    url: `/model-config/${id}`,
    method: 'put',
    data
  });
}

export function deleteModelConfig(id: number) {
  return request({
    url: `/model-config/${id}`,
    method: 'delete'
  });
}

export function updateModelPreference(data: Api.ModelConfig.PreferenceRequest) {
  return request<Api.ModelConfig.Preference>({
    url: '/model-config/preference',
    method: 'put',
    data
  });
}
