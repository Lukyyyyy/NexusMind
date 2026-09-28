<script setup lang="ts">
import { useFullscreen } from '@vueuse/core';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import GlobalBreadcrumb from '../global-breadcrumb/index.vue';
import ThemeButton from './components/theme-button.vue';
import UserAvatar from './components/user-avatar.vue';
import NotificationCenter from './components/notification-center.vue';

defineOptions({
  name: 'GlobalHeader'
});

interface Props {
  /** Whether to show the logo */
  // showLogo?: App.Global.HeaderProps['showLogo'];
  /** Whether to show the menu toggler */
  showMenuToggler?: App.Global.HeaderProps['showMenuToggler'];
  /** Whether to show the menu */
  // showMenu?: App.Global.HeaderProps['showMenu'];
}

defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();
const { isFullscreen, toggle } = useFullscreen();

const isDev = import.meta.env.DEV;
</script>

<template>
  <DarkModeContainer class="nexus-global-header h-full w-full flex-y-center justify-between bg-container px-16px">
    <div class="min-w-0 flex-y-center gap-14px">
      <button
        v-if="showMenuToggler && !appStore.isMobile"
        type="button"
        class="nexus-menu-toggler"
        aria-label="切换侧边栏"
        @click="appStore.toggleSiderCollapse"
      >
        <SvgIcon icon="mdi:menu" />
      </button>
      <GlobalBreadcrumb v-if="!appStore.isMobile" />
      <div id="header-extra" class="min-w-0 flex-y-center"></div>
    </div>
    <button
      v-if="showMenuToggler && appStore.isMobile"
      type="button"
      class="nexus-menu-toggler"
      aria-label="打开导航菜单"
      @click="appStore.toggleSiderCollapse"
    >
      <SvgIcon icon="mdi:menu" />
    </button>
    <!--
    <div v-if="showMenu" :id="GLOBAL_HEADER_MENU_ID" class="h-full flex-y-center flex-1-hidden"></div>
    <div v-else class="h-full flex-y-center flex-1-hidden">
      <GlobalBreadcrumb v-if="!appStore.isMobile" class="ml-12px" />
    </div>
-->
    <div class="h-full flex-y-center justify-end gap-2px">
      <FullScreen v-if="!appStore.isMobile" :full="isFullscreen" @click="toggle" />
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :lang-options="appStore.localeOptions"
        @change-lang="appStore.changeLocale"
      />
      <ThemeSchemaSwitch
        :theme-schema="themeStore.themeScheme"
        :is-dark="themeStore.darkMode"
        @switch="themeStore.toggleThemeScheme"
      />
      <ThemeButton v-if="isDev" />
      <NotificationCenter />
      <UserAvatar />
    </div>
  </DarkModeContainer>
</template>

<style scoped>
.nexus-menu-toggler {
  display: grid;
  width: 32px;
  height: 32px;
  flex: 0 0 auto;
  place-items: center;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #4b5565;
  cursor: pointer;
  font-size: 21px;
  transition: color 160ms ease, background-color 160ms ease;
}

.nexus-menu-toggler:hover {
  background: #eef3ff;
  color: #245bdb;
}
</style>
