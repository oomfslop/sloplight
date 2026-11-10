<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';

import LinuxCaptureStatus from '@/components/settings/LinuxCaptureStatus.vue';
import VirtualDisplayRecovery from '@/components/VirtualDisplayRecovery.vue';
import { ApiError, apiGet } from '@/services/client';
import {
  AppButton,
  EmptyState,
  InlineAlert,
  LoadingSkeleton,
  PageHeader,
  UiIcon,
  type StatusTone,
} from '@/components/ui';
import type { HostInfo, HostStatsSnapshot } from '@/types/host';
import type { SessionStatus } from '@/types/sessions';
import { useSystemStore } from '@/stores/system';
import { formatBytes } from '@/utils/format';
import { displayRecoverySupported } from '@/utils/displayRecovery';

interface OverviewWarning {
  key: string;
  title: string;
  detail: string;
  to: string;
  action: string;
}

interface VigemHealth {
  status?: unknown;
  installed?: unknown;
  required?: unknown;
  version?: unknown;
}

const { locale, t } = useI18n();
const system = useSystemStore();
const session = ref<SessionStatus | null>(null);
const hostStats = ref<HostStatsSnapshot | null>(null);
const statsStale = ref(false);
const hostInfo = ref<HostInfo | null>(null);
const hostPlatform = ref('');
const vigemInstalled = ref<boolean | null>(null);
const vigemRequired = ref<boolean | null>(null);
const vigemVersion = ref('');
const controllerEnabled = ref<boolean | null>(null);
const loading = ref(true);
const refreshing = ref(false);
const fetchErrors = ref<string[]>([]);
const lastUpdatedAt = ref<number | null>(null);
const devices = ref<{ uuid: string; name: string; enabled: boolean; connected: boolean }[] | null>(
  null,
);
let pollTimer: number | undefined;

function errorMessage(cause: unknown, fallback: string): string {
  return cause instanceof ApiError ? fallback : cause instanceof Error ? cause.message : fallback;
}

function isWindowsPlatform(value: unknown): boolean {
  const platform = String(value ?? '')
    .trim()
    .toLocaleLowerCase();
  return platform === 'windows' || platform.startsWith('win');
}

function enabledValue(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  if (typeof value === 'number') return value !== 0;
  if (typeof value === 'string') {
    const normalized = value.trim().toLocaleLowerCase();
    return ['1', 'true', 'yes', 'on', 'enabled'].includes(normalized);
  }
  return false;
}

async function refreshVigem(platform: string): Promise<void> {
  // ViGEm is Windows-only. Do not request its endpoint on another host.
  if (!isWindowsPlatform(platform)) {
    controllerEnabled.value = null;
    vigemInstalled.value = null;
    vigemRequired.value = null;
    vigemVersion.value = '';
    return;
  }

  try {
    const config = await apiGet<Record<string, unknown>>('/api/config');
    if (config.status === false) throw new Error('vigem-config-rejected');
    const enabled = enabledValue(config.controller);
    controllerEnabled.value = enabled;
    if (!enabled) {
      vigemInstalled.value = null;
      vigemRequired.value = null;
      vigemVersion.value = '';
      return;
    }

    const health = await apiGet<VigemHealth>('/api/health/vigem');
    // Only a successful, explicit boolean false means the driver is absent.
    // Auth, network, malformed, and unknown responses remain unknown.
    if (health && health.status !== false && typeof health.installed === 'boolean') {
      vigemInstalled.value = health.installed;
      vigemRequired.value = typeof health.required === 'boolean' ? health.required : null;
      vigemVersion.value = typeof health.version === 'string' ? health.version : '';
    } else {
      vigemInstalled.value = null;
      vigemRequired.value = null;
      vigemVersion.value = '';
    }
  } catch {
    controllerEnabled.value = null;
    vigemInstalled.value = null;
    vigemRequired.value = null;
    vigemVersion.value = '';
  }
}

async function refresh(silent = false): Promise<void> {
  if (refreshing.value) return;
  refreshing.value = true;
  if (!silent) loading.value = true;

  void system.refreshHost();
  const results = await Promise.allSettled([
    apiGet<SessionStatus>('/api/session/status'),
    apiGet<HostStatsSnapshot>('/api/host/stats'),
    apiGet<HostInfo>('/api/host/info'),
    apiGet<{ named_certs?: Record<string, unknown>[] }>('/api/clients/list'),
  ]);

  const nextErrors: string[] = [];
  const [sessionResult, statsResult, infoResult, devicesResult] = results;

  if (sessionResult.status === 'fulfilled') {
    session.value = sessionResult.value;
  } else {
    nextErrors.push(errorMessage(sessionResult.reason, t('ui.overview.errors.streamStatus')));
  }

  if (statsResult.status === 'fulfilled') {
    hostStats.value = statsResult.value;
    statsStale.value = false;
  } else {
    statsStale.value = true;
    nextErrors.push(errorMessage(statsResult.reason, t('ui.overview.errors.hostMetrics')));
  }

  if (infoResult.status === 'fulfilled') {
    hostInfo.value = infoResult.value;
  } else {
    nextErrors.push(errorMessage(infoResult.reason, t('ui.overview.errors.hostInfo')));
  }

  if (devicesResult.status === 'fulfilled') {
    devices.value = (devicesResult.value.named_certs ?? []).map((device) => ({
      uuid: String(device.uuid ?? ''),
      name: String(device.name ?? ''),
      enabled: device.enabled !== false,
      connected: device.connected === true,
    }));
  } else {
    nextErrors.push(errorMessage(devicesResult.reason, t('ui.overview.errors.pairedDevices')));
  }

  // The system store discovers metadata independently; a display-driver wait
  // must not hold the dashboard's session status and metrics in loading state.
  hostPlatform.value = system.metadata?.platform ?? hostPlatform.value;
  await refreshVigem(hostPlatform.value || system.metadata?.platform || '');

  fetchErrors.value = [...new Set(nextErrors)];
  lastUpdatedAt.value = Date.now();
  refreshing.value = false;
  loading.value = false;
}

const isStreaming = computed(() =>
  Boolean(session.value?.appRunning || (session.value?.activeSessions ?? 0) > 0),
);

const warnings = computed<OverviewWarning[]>(() => {
  const result: OverviewWarning[] = [];
  if (fetchErrors.value.length) {
    result.push({
      key: 'partial-data',
      title: t('ui.overview.warnings.partialData.title'),
      detail: fetchErrors.value[0],
      to: '/logs',
      action: t('ui.overview.actions.openLogs'),
    });
  }
  if (session.value?.paused) {
    result.push({
      key: 'paused-app',
      title: t('ui.overview.warnings.paused.title'),
      detail: t('ui.overview.warnings.paused.detail', {
        app: session.value.appName || t('ui.overview.currentApplication'),
      }),
      to: '/stats',
      action: t('ui.overview.actions.openStats'),
    });
  }
  if (
    session.value?.lastEncoderProbeFailed ||
    system.metadata?.encoder_status?.state === 'failed'
  ) {
    result.push({
      key: 'encoder-probe-failed',
      title: t('ui.overview.warnings.encoderProbe.title'),
      detail: t('ui.overview.warnings.encoderProbe.detail'),
      to: '/logs',
      action: t('ui.overview.actions.openLogs'),
    });
  }
  const hottest = Math.max(hostStats.value?.cpu_percent ?? 0, hostStats.value?.gpu_percent ?? 0);
  if (hottest >= 95) {
    result.push({
      key: 'host-load',
      title: t('ui.overview.warnings.hostLoad.title'),
      detail: t('ui.overview.warnings.hostLoad.detail', { threshold: 95 }),
      to: '/stats',
      action: t('ui.overview.actions.openStats'),
    });
  }
  return result;
});

const showVigemBanner = computed(
  () =>
    isWindowsPlatform(hostPlatform.value || system.metadata?.platform) &&
    controllerEnabled.value === true &&
    vigemInstalled.value === false &&
    vigemRequired.value !== false,
);

const readiness = computed<{ label: string; detail: string; tone: StatusTone }>(() => {
  if (isStreaming.value) {
    return {
      label: t('ui.overview.readiness.streaming'),
      detail: session.value?.appName || t('ui.overview.readiness.remoteSessionActive'),
      tone: 'info',
    };
  }
  if (system.health === 'unknown')
    return {
      label: t('ui.settings.linux.states.unknown'),
      detail: t('ui.settings.linux.unverified'),
      tone: 'neutral',
    };
  if (system.health === 'warning' && !warnings.value.length)
    return {
      label: t('ui.status.needs_attention'),
      detail: t('ui.settings.linux.check_setup'),
      tone: 'warning',
    };
  if (warnings.value.length) {
    return {
      label: t('ui.overview.readiness.attention'),
      detail: warnings.value[0].title,
      tone: 'warning',
    };
  }
  return {
    label: t('ui.overview.readiness.ready'),
    detail: t('ui.overview.readiness.readyDetail'),
    tone: 'success',
  };
});

const lastUpdatedLabel = computed(() =>
  lastUpdatedAt.value
    ? new Intl.DateTimeFormat(locale.value || undefined, {
        hour: 'numeric',
        minute: '2-digit',
      }).format(lastUpdatedAt.value)
    : t('ui.overview.notUpdated'),
);

const readinessIcon = computed(() => {
  if (readiness.value.tone === 'warning') return 'alert-triangle';
  if (readiness.value.tone === 'neutral') return 'info';
  return isStreaming.value ? 'activity' : 'check-circle';
});
const primaryAction = computed(() => {
  if (isStreaming.value)
    return { to: '/stats', label: t('ui.overview.actions.openStats'), icon: 'activity' } as const;
  if (warnings.value.length)
    return {
      to: warnings.value[0].to,
      label: warnings.value[0].action,
      icon: 'chevron-right',
    } as const;
  if (system.health === 'unknown' || system.health === 'warning')
    return {
      to: '/settings?category=display',
      label: t('ui.overview.actions.reviewSetup'),
      icon: 'settings',
    } as const;
  return { to: '/pair', label: t('ui.overview.actions.pairDevice'), icon: 'plus' } as const;
});
const sortedDevices = computed(() =>
  [...(devices.value ?? [])].sort(
    (a, b) => Number(b.connected) - Number(a.connected) || a.name.localeCompare(b.name),
  ),
);
const metrics = computed(() => [
  { label: t('host.cpu'), value: hostStats.value?.cpu_percent },
  { label: t('host.gpu'), value: hostStats.value?.gpu_percent },
  { label: t('ui.overview.memory'), value: hostStats.value?.ram_percent },
  { label: t('host.vram'), value: hostStats.value?.vram_percent },
]);
function validPercent(value: number | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 100;
}
function percent(value: number | undefined): string {
  return validPercent(value)
    ? new Intl.NumberFormat(locale.value || undefined, {
        maximumFractionDigits: 0,
        style: 'percent',
      }).format((value ?? 0) / 100)
    : t('ui.overview.unavailable');
}

function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') void refresh(true);
}

onMounted(() => {
  void refresh();
  pollTimer = window.setInterval(() => {
    if (document.visibilityState === 'visible') void refresh(true);
  }, 10_000);
  document.addEventListener('visibilitychange', onVisibilityChange);
});

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer);
  document.removeEventListener('visibilitychange', onVisibilityChange);
});
</script>

<template>
  <div class="page page--wide overview-page">
    <PageHeader :title="t('ui.overview.title')">
      <template #actions>
        <div class="overview-refresh">
          <span class="overview-updated">{{
            t('ui.overview.updated', { time: lastUpdatedLabel })
          }}</span>
          <AppButton
            icon="refresh"
            :label="t('_common.refresh')"
            variant="secondary"
            :busy="refreshing"
            :busy-label="t('ui.overview.refreshing')"
            @click="refresh(true)"
          />
        </div>
      </template>
    </PageHeader>
    <div class="visually-hidden" aria-live="polite" aria-atomic="true">{{ readiness.label }}</div>
    <template v-if="loading">
      <LoadingSkeleton variant="block" height="184px" :label="t('ui.overview.loadingReadiness')" />
      <LoadingSkeleton variant="block" height="320px" :label="t('ui.overview.loadingReadiness')" />
    </template>
    <template v-else>
      <section
        class="readiness-panel"
        :data-tone="readiness.tone"
        aria-labelledby="readiness-title"
      >
        <div class="readiness-panel__state">
          <span class="readiness-panel__icon" aria-hidden="true"
            ><UiIcon :name="readinessIcon" :size="18"
          /></span>
          <div class="readiness-panel__copy">
            <h2 id="readiness-title">{{ readiness.label }}</h2>
            <p>{{ readiness.detail }}</p>
            <p v-if="isStreaming" class="readiness-panel__sessions">
              {{
                t(
                  (session?.activeSessions ?? 0) === 1
                    ? 'ui.overview.activeSessions.one'
                    : 'ui.overview.activeSessions.other',
                  { count: session?.activeSessions ?? 0 },
                )
              }}
            </p>
          </div>
        </div>
        <div class="readiness-panel__actions">
          <RouterLink
            class="button"
            :class="readiness.tone === 'warning' ? 'button--primary' : 'button--secondary'"
            :to="primaryAction.to"
          >
            <UiIcon :name="primaryAction.icon" />{{ primaryAction.label }}
          </RouterLink>
          <RouterLink v-if="!isStreaming" class="button button--secondary" to="/stream">
            <UiIcon name="play" />{{ t('ui.overview.actions.startBrowserStream') }}
          </RouterLink>
        </div>
      </section>

      <div v-if="showVigemBanner || warnings.length" class="overview-notices">
        <InlineAlert v-if="showVigemBanner" tone="warning" :title="t('config.vigem_missing_title')">
          {{ t('config.vigem_missing_desc') }}
          <span v-if="vigemVersion" class="overview-vigem-version">
            ({{ t('config.vigem_detected_version') }}: {{ vigemVersion }})
          </span>
          <template #actions>
            <a
              href="https://github.com/nefarius/ViGEmBus/releases/latest"
              target="_blank"
              rel="noopener noreferrer"
            >
              {{ t('config.vigem_install') }}
              <UiIcon name="external-link" :size="14" aria-hidden="true" />
            </a>
          </template>
        </InlineAlert>
        <InlineAlert
          v-for="warning in warnings"
          :key="warning.key"
          tone="warning"
          :title="warning.title"
        >
          {{ warning.detail }}
          <template #actions
            ><RouterLink :to="warning.to">{{ warning.action }}</RouterLink></template
          >
        </InlineAlert>
      </div>
      <InlineAlert
        v-if="fetchErrors.length > 1"
        tone="warning"
        :title="t('ui.overview.additionalDataUnavailable')"
      >
        {{ fetchErrors.slice(1).join(' ') }}
      </InlineAlert>

      <div class="overview-detail-grid">
        <section class="overview-panel" aria-labelledby="host-metrics-title">
          <div class="overview-panel__heading">
            <div>
              <h2 id="host-metrics-title">{{ t('ui.overview.hostLoad.title') }}</h2>
              <p v-if="statsStale && hostStats" class="overview-stale">
                {{ t('ui.overview.hostLoad.staleDescription') }}
              </p>
            </div>
            <RouterLink class="overview-detail-link" to="/stats"
              >{{ t('ui.overview.actions.viewDetails') }}<UiIcon name="chevron-right"
            /></RouterLink>
          </div>
          <dl v-if="hostStats" class="metric-grid">
            <div
              v-for="metric in metrics"
              :key="metric.label"
              :class="{ 'metric--unavailable': !validPercent(metric.value) }"
            >
              <dt>{{ metric.label }}</dt>
              <dd>{{ percent(metric.value) }}</dd>
              <div class="metric-track" aria-hidden="true">
                <span
                  :style="{ width: `${validPercent(metric.value) ? metric.value : 0}%` }"
                  :data-high="validPercent(metric.value) && metric.value >= 95"
                />
              </div>
            </div>
          </dl>
          <EmptyState
            v-else
            compact
            icon="activity"
            :title="t('ui.overview.hostLoad.unavailableTitle')"
            :description="t('ui.overview.hostLoad.unavailableDescription')"
          />
          <p
            v-if="hostStats && hostStats.ram_total_bytes > 0 && hostStats.ram_used_bytes >= 0"
            class="metric-footnote"
          >
            {{
              t('ui.overview.hostLoad.memoryInUse', {
                total: formatBytes(hostStats.ram_total_bytes, locale),
                used: formatBytes(hostStats.ram_used_bytes, locale),
              })
            }}
          </p>
          <dl class="host-details" :aria-label="t('ui.overview.hostDetails')">
            <div>
              <dt>{{ t('ui.overview.processor') }}</dt>
              <dd>{{ hostInfo?.cpu_model || t('ui.overview.unavailable') }}</dd>
            </div>
            <div>
              <dt>{{ t('ui.overview.graphics') }}</dt>
              <dd>{{ hostInfo?.gpu_model || t('ui.overview.unavailable') }}</dd>
            </div>
          </dl>
        </section>

        <section class="overview-panel" aria-labelledby="devices-title">
          <div class="overview-panel__heading">
            <h2 id="devices-title">{{ t('ui.overview.devices.title') }}</h2>
            <RouterLink class="overview-detail-link" to="/devices"
              >{{ t('ui.overview.actions.manageDevices') }}<UiIcon name="chevron-right"
            /></RouterLink>
          </div>
          <ul v-if="sortedDevices.length" class="device-list">
            <li v-for="device in sortedDevices" :key="device.uuid">
              <UiIcon name="devices" :size="16" />
              <span class="device-list__name">{{ device.name || device.uuid }}</span>
              <span
                v-if="device.connected"
                class="device-list__state device-list__state--streaming"
                >{{ t('ui.devices.status.streaming') }}</span
              >
              <span v-else-if="!device.enabled" class="device-list__state device-list__state--blocked">{{
                t('ui.devices.status.blocked')
              }}</span>
            </li>
          </ul>
          <EmptyState
            v-else-if="devices"
            compact
            icon="devices"
            :title="t('ui.overview.devices.emptyTitle')"
          >
            <template #actions>
              <RouterLink class="button button--secondary button--compact" to="/pair">
                <UiIcon name="plus" />{{ t('ui.overview.actions.pairDevice') }}
              </RouterLink>
            </template>
          </EmptyState>
        </section>
      </div>
      <LinuxCaptureStatus
        v-if="system.metadata?.platform === 'linux'"
        :metadata="system.metadata"
        :virtual-mode="
          system.metadata.capture_status?.virtual_display_configured === false
            ? 'disabled'
            : undefined
        "
      />
    </template>
    <VirtualDisplayRecovery
      v-if="displayRecoverySupported(hostPlatform || system.metadata?.platform || '')"
      @recovered="refresh(true)"
    />
    <footer class="overview-footer">
      <span
        >{{ t('ui.overview.installedVersion') }}
        <strong>{{ system.metadata?.version || t('_common.unknown') }}</strong></span
      >
      <nav :aria-label="t('ui.overview.support')">
        <a
          href="https://github.com/Nonary/vibeshine/issues/new/choose"
          target="_blank"
          rel="noopener noreferrer"
          >{{ t('ui.overview.actions.reportBug') }}<UiIcon name="external-link" :size="14"
        /></a>
        <a
          href="https://github.com/Nonary/vibeshine/releases/latest"
          target="_blank"
          rel="noopener noreferrer"
          >{{ t('ui.overview.actions.checkUpdates') }}<UiIcon name="external-link" :size="14"
        /></a>
      </nav>
    </footer>
  </div>
</template>

<style scoped>
.overview-page {
  display: grid;
  gap: var(--vs-space-16);
}
.overview-refresh {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vs-space-12);
}
.overview-updated {
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-metadata);
}
.readiness-panel,
.overview-panel {
  min-width: 0;
  border: var(--vs-border-width) solid var(--vs-color-border-subtle);
  border-radius: var(--vs-radius-card);
  background: var(--vs-color-bg-surface);
}
.readiness-panel {
  --readiness-color: var(--vs-color-status-success);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--vs-space-16);
  padding: var(--vs-space-12) var(--vs-space-16);
  border-left: 3px solid var(--readiness-color);
}
.readiness-panel[data-tone='info'] {
  --readiness-color: var(--vs-color-status-info);
}
.readiness-panel[data-tone='warning'] {
  --readiness-color: var(--vs-color-status-warning);
}
.readiness-panel[data-tone='neutral'] {
  --readiness-color: var(--vs-color-text-muted);
}
.readiness-panel__state {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: var(--vs-space-12);
}
.readiness-panel__copy {
  display: flex;
  min-width: 0;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--vs-space-4) var(--vs-space-12);
}
.readiness-panel__icon {
  flex: none;
  color: var(--readiness-color);
}
.readiness-panel h2 {
  font-size: var(--vs-type-size-section);
  line-height: var(--vs-type-line-height-section);
}
.readiness-panel p {
  color: var(--vs-color-text-secondary);
  font-size: var(--vs-type-size-control);
}
.readiness-panel__sessions {
  color: var(--vs-color-text-muted);
}
.readiness-panel__actions {
  display: flex;
  flex: 0 0 auto;
  flex-wrap: wrap;
  gap: var(--vs-space-8);
}
.overview-notices {
  display: grid;
  gap: var(--vs-space-8);
}
.overview-vigem-version {
  color: var(--vs-color-text-muted);
}
.overview-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: var(--vs-space-16);
}
.overview-panel {
  padding: var(--vs-space-16);
}
.overview-panel__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--vs-space-8);
}
.overview-panel h2 {
  font-size: var(--vs-type-size-section);
  line-height: var(--vs-type-line-height-section);
}
.overview-panel__heading p {
  flex-basis: 100%;
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-metadata);
}
.overview-panel__heading .overview-stale {
  color: var(--vs-color-status-warning);
}
.overview-detail-link {
  display: inline-flex;
  align-items: center;
  gap: var(--vs-space-2);
  font-size: var(--vs-type-size-metadata);
  text-decoration: none;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--vs-space-16);
  margin-top: var(--vs-space-16);
}
.metric-grid > div {
  min-width: 0;
}
.metric-grid dt {
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-metadata);
}
.metric-grid dd {
  margin: var(--vs-space-2) 0 var(--vs-space-8);
  font-size: 22px;
  line-height: 28px;
  font-weight: var(--vs-type-weight-medium);
  font-variant-numeric: tabular-nums;
}
.metric-grid .metric--unavailable dd {
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-metadata);
}
.metric-track {
  height: 3px;
  overflow: hidden;
  border-radius: var(--vs-radius-pill);
  background: var(--vs-color-border-subtle);
}
.metric-track > span {
  display: block;
  height: 100%;
  background: var(--vs-color-accent-default);
  border-radius: inherit;
}
.metric-track > span[data-high='true'] {
  background: var(--vs-color-status-warning);
}
.metric-footnote {
  margin-top: var(--vs-space-12);
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-helper);
}
.host-details {
  display: grid;
  gap: var(--vs-space-4);
  padding-top: var(--vs-space-12);
  margin-top: var(--vs-space-16);
  border-top: 1px solid var(--vs-color-border-subtle);
  font-size: var(--vs-type-size-metadata);
}
.host-details > div {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--vs-space-4) var(--vs-space-16);
}
.host-details dt {
  color: var(--vs-color-text-muted);
}
.host-details dd {
  color: var(--vs-color-text-secondary);
  overflow-wrap: anywhere;
}
.device-list {
  display: grid;
  margin: var(--vs-space-8) 0 0;
  padding: 0;
  list-style: none;
}
.device-list li {
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: var(--vs-space-8);
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-control);
}
.device-list li + li {
  border-top: 1px solid var(--vs-color-border-subtle);
}
.device-list__name {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: var(--vs-color-text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.device-list__state {
  font-size: var(--vs-type-size-helper);
}
.device-list__state--streaming {
  color: var(--vs-color-status-success);
}
.device-list__state--blocked {
  color: var(--vs-color-status-danger);
}
.overview-page :deep(.linux-capture) {
  margin-bottom: 0;
}
.overview-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: var(--vs-space-16);
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-helper);
}
.overview-footer strong {
  margin-left: var(--vs-space-4);
  font-weight: var(--vs-type-weight-medium);
  color: var(--vs-color-text-secondary);
}
.overview-footer nav {
  display: flex;
  flex-wrap: wrap;
  gap: var(--vs-space-16);
}
.overview-footer a {
  display: inline-flex;
  align-items: center;
  gap: var(--vs-space-4);
  color: var(--vs-color-text-muted);
  text-decoration: none;
}
.overview-footer a:hover {
  color: var(--vs-color-accent-default);
}
@media (max-width: 1023px) {
  .overview-detail-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 767px) {
  .readiness-panel {
    align-items: stretch;
    flex-direction: column;
  }
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
@media (forced-colors: active) {
  .metric-track {
    border: 1px solid CanvasText;
  }
  .metric-track > span {
    background: Highlight;
    forced-color-adjust: none;
  }
}
</style>
