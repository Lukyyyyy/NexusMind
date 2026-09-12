<script setup lang="ts">
import { computed, reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useFormRules, useNaiveForm } from '@/hooks/common/form';
import { localStg } from '@/utils/storage';
import { $t } from '@/locales';
import { useRouterPush } from '@/hooks/common/router';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { formRef, validate } = useNaiveForm();
const { toggleLoginModule } = useRouterPush();

interface FormModel {
  email: string;
  password: string;
  remember: boolean;
}

const rememberedLogin = localStg.get('rememberedLogin');
if (rememberedLogin) {
  // 重写旧结构，立即清除历史版本曾保存到 localStorage 的明文密码字段。
  localStg.set('rememberedLogin', { email: rememberedLogin.email || '', remember: Boolean(rememberedLogin.remember) });
}

const model: FormModel = reactive({
  email: rememberedLogin?.email || '',
  password: '',
  remember: rememberedLogin?.remember || false
});

const rules = computed<Partial<Record<keyof FormModel, App.Global.FormRule[]>>>(() => {
  // inside computed to make locale reactive, if not apply i18n, you can define it without computed
  const { formRules } = useFormRules();

  return {
    email: formRules.email,
    password: formRules.pwd
  };
});

async function handleSubmit() {
  await validate();
  await authStore.login(model.email.trim().toLowerCase(), model.password);

  if (!authStore.isLogin) {
    return;
  }

  if (model.remember) {
    localStg.set('rememberedLogin', {
      email: model.email.trim().toLowerCase(),
      remember: true
    });
  } else {
    localStg.remove('rememberedLogin');
  }
}
</script>

<template>
  <NForm
    ref="formRef"
    class="pwd-login-form"
    :model="model"
    :rules="rules"
    size="large"
    :show-label="false"
    @keyup.enter="handleSubmit"
  >
    <NFormItem path="email">
      <NInput v-model:value="model.email" placeholder="请输入邮箱">
        <template #prefix><icon-ant-design:mail-outlined /></template>
      </NInput>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :placeholder="$t('page.login.common.passwordPlaceholder')"
      >
        <template #prefix>
          <icon-ant-design:key-outlined />
        </template>
      </NInput>
    </NFormItem>
    <div class="login-options">
      <NCheckbox v-model:checked="model.remember">记住邮箱</NCheckbox>
      <button type="button" class="forgot-link" @click="toggleLoginModule('reset-pwd')">忘记密码</button>
    </div>
    <div class="login-actions">
      <NButton type="primary" size="large" block :loading="authStore.loginLoading" @click="handleSubmit">
        {{ $t('page.login.common.login') }}
      </NButton>
    </div>
  </NForm>
</template>

<style scoped lang="scss">
.pwd-login-form {
  :deep(.n-form-item) {
    margin-bottom: 2px;
  }

  :deep(.n-input) {
    height: 38px;
    font-size: 13px;
  }
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 2px 0 18px;
  color: #64748b;
  font-size: 13px;
}

.forgot-link {
  border: 0;
  padding: 0;
  background: transparent;
  color: #245bdb;
  cursor: pointer;
  font-size: 13px;
}

.login-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;

  :deep(.n-button) {
    height: 40px;
    font-size: 15px;
    letter-spacing: 0;
  }
}
</style>
