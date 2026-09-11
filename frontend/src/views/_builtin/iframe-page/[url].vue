<script setup lang="ts">
import { computed, onActivated, onMounted } from 'vue';
import { getSafeIframeUrl } from './safe-url';

interface Props {
  url: string;
}

const props = defineProps<Props>();

const safeUrl = computed(() => getSafeIframeUrl(props.url));

onMounted(() => {
  console.log('mounted');
});

onActivated(() => {
  console.log('activated');
});
</script>

<template>
  <div class="h-full">
    <iframe v-if="safeUrl" id="iframePage" class="size-full" :src="safeUrl"></iframe>
  </div>
</template>

<style scoped></style>
