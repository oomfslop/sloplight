<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    label: string;
    value: number | null | undefined;
    detail?: string;
    color?: string;
  }>(),
  {
    detail: '',
    color: 'var(--vs-color-accent-default)',
  },
);

const bounded = computed(() => {
  const value = typeof props.value === 'number' && Number.isFinite(props.value) ? props.value : 0;
  return Math.max(0, Math.min(100, value));
});
const display = computed(() =>
  typeof props.value === 'number' && Number.isFinite(props.value)
    ? `${Math.round(bounded.value)}%`
    : '—',
);
</script>

<template>
  <article class="metric-gauge" :style="{ '--gauge-color': color }">
    <div class="metric-gauge__head">
      <span>{{ label }}</span>
      <small v-if="detail">{{ detail }}</small>
    </div>
    <strong>{{ display }}</strong>
    <div
      class="metric-gauge__bar"
      role="meter"
      :aria-label="label"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuenow="bounded"
      :aria-valuetext="display"
    >
      <span :style="{ width: `${bounded}%` }" />
    </div>
  </article>
</template>

<style scoped>
.metric-gauge {
  display: grid;
  min-width: 0;
  gap: var(--vs-space-4);
  padding: var(--vs-space-12);
  border: 1px solid var(--vs-color-border-subtle);
  border-radius: var(--vs-radius-card);
  background: var(--vs-color-bg-surface);
}

.metric-gauge__head {
  display: flex;
  min-width: 0;
  justify-content: space-between;
  gap: var(--vs-space-8);
  color: var(--vs-color-text-secondary);
  font-size: var(--vs-type-size-metadata);
}

.metric-gauge__head small {
  overflow: hidden;
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-helper);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-gauge strong {
  font-size: 1.375rem;
  font-variant-numeric: tabular-nums;
  line-height: 1.75rem;
}

.metric-gauge__bar {
  height: 4px;
  overflow: hidden;
  border-radius: var(--vs-radius-pill);
  background: var(--vs-color-bg-subtle);
}

.metric-gauge__bar > span {
  display: block;
  height: 100%;
  background: var(--gauge-color);
  transition: width var(--vs-motion-duration-layout) var(--vs-motion-easing-standard);
}
</style>
