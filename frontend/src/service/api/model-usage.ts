import { request } from '../request';

export function fetchModelUsageOverview(params?: Api.ModelUsage.OverviewParams) {
  return request<Api.ModelUsage.Overview>({ url: '/model-usage/overview', params });
}

export function updateUserModelQuota(userId: number, data: Api.ModelUsage.QuotaRequest) {
  return request({ url: `/admin/model-usage/users/${userId}/quota`, method: 'put', data });
}

export function updateModelPricing(ruleId: number, data: Api.ModelUsage.PricingRequest) {
  return request({ url: `/admin/model-usage/pricing/${ruleId}`, method: 'put', data });
}

export function createModelPricing(data: Api.ModelUsage.CreatePricingRequest) {
  return request({ url: '/admin/model-usage/pricing', method: 'post', data });
}
