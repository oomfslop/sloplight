<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';
import { useRoute } from 'vue-router';
import { apiGet } from '@/services/client';
import { AppButton, UiIcon } from '@/components/ui';
import { useSystemStore } from '@/stores/system';
import type { ChangelogEntry } from '@/utils/changelog';
import {
  selectAvailableUpdate,
  fetchUpdateReleases,
  dismissedUpdate,
  dismissUpdate,
  prereleasesEnabled,
} from '@/utils/updates';

const system = useSystemStore();
const { t } = useI18n();
const route = useRoute();
const available = ref<ChangelogEntry | null>(null);
const failed = ref(false);
const loading = ref(false);
const showFailure = computed(() => failed.value && route.path !== '/maintenance');
let timer: ReturnType<typeof setTimeout> | undefined;
let stopped = false;
let controller: AbortController | undefined;

async function check(): Promise<void> {
  if (loading.value || !system.metadata?.version || stopped) return;
  clearTimeout(timer);
  loading.value = true;
  let nextCheck = 300000;
  controller = new AbortController();
  const timeout = setTimeout(() => controller?.abort(), 15000);
  try {
    const config = await apiGet<Record<string, unknown>>('/api/config', {
      signal: controller.signal,
    });
    const releases = await fetchUpdateReleases(
      controller.signal,
      system.metadata.platform,
      prereleasesEnabled(config.notify_pre_releases),
      system.metadata.architecture ?? 'unknown',
    );
    const selected = selectAvailableUpdate(
      system.metadata.version,
      releases,
      config.notify_pre_releases,
      system.metadata.platform,
      system.metadata.architecture ?? 'unknown',
    );
    available.value = selected && !dismissedUpdate(localStorage, selected.tag) ? selected : null;
    failed.value = false;
    const interval = Number(config.update_check_interval ?? 86400);
    nextCheck = Number.isFinite(interval) && interval >= 0 ? interval * 1000 : 86400000;
  } catch {
    available.value = null;
    if (!stopped) failed.value = true;
  } finally {
    clearTimeout(timeout);
    loading.value = false;
    if (!stopped && nextCheck > 0)
      timer = setTimeout(() => void check(), Math.min(2147483647, Math.max(60000, nextCheck)));
  }
}

watch(
  () => system.metadata?.version,
  () => void check(),
  { immediate: true },
);
onBeforeUnmount(() => {
  stopped = true;
  clearTimeout(timer);
  controller?.abort();
});
function dismiss(): void {
  if (available.value) dismissUpdate(localStorage, available.value.tag);
  available.value = null;
}
</script>

<template>
  <div v-if="available || showFailure" class="update-notice">
    <div v-if="available" class="update-notice__bar" role="status" aria-live="polite">
      <UiIcon name="info" :size="14" />
      <strong>{{ t('ui.updates.available', { version: available.tag }) }}</strong>
      <a
        :href="
          available.url?.startsWith('https://github.com/Nonary/vibeshine/releases/')
            ? available.url
            : 'https://github.com/Nonary/vibeshine/releases'
        "
        target="_blank"
        rel="noopener noreferrer"
        >{{ t('ui.maintenance.releases.open') }}</a
      >
      <button class="icon-button" type="button" aria-label="Dismiss this update" title="Dismiss" @click="dismiss">
        <UiIcon name="x" :size="14" />
      </button>
    </div>
    <div v-if="showFailure" class="update-notice__bar update-notice__bar--warning">
      <UiIcon name="alert-triangle" :size="14" />
      <span>{{ t('ui.maintenance.releases.unavailable') }}</span>
      <AppButton
        :label="t('ui.maintenance.releases.check')"
        :busy="loading"
        :busy-label="t('ui.maintenance.releases.loading')"
        variant="tertiary"
        size="compact"
        @click="check"
      />
    </div>
  </div>
</template>

<style scoped>
.update-notice__bar {
  display: flex;
  min-height: 36px;
  align-items: center;
  gap: var(--vs-space-8);
  padding: 0 var(--vs-space-12) 0 var(--vs-page-padding-desktop);
  border-bottom: 1px solid var(--vs-color-border-subtle);
  background: color-mix(in srgb, var(--vs-color-status-info) 7%, var(--vs-color-bg-surface));
  color: var(--vs-color-text-secondary);
  font-size: var(--vs-type-size-metadata);
}
.update-notice__bar > svg {
  color: var(--vs-color-status-info);
}
.update-notice__bar strong {
  color: var(--vs-color-text-primary);
  font-weight: var(--vs-type-weight-medium);
}
.update-notice__bar .icon-button {
  margin-left: auto;
}
.update-notice__bar--warning {
  background: color-mix(in srgb, var(--vs-color-status-warning) 7%, var(--vs-color-bg-surface));
}
.update-notice__bar--warning > svg {
  color: var(--vs-color-status-warning);
}
.update-notice__bar--warning > :last-child {
  margin-left: auto;
}
@media (max-width: 767px) {
  .update-notice__bar {
    padding-left: var(--vs-page-padding-mobile);
  }
}
</style>
