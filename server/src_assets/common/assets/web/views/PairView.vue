<script setup lang="ts">
import { computed, ref } from 'vue';
import { useI18n } from 'vue-i18n';

import { ApiError, apiPost } from '@/services/client';
import { AppButton, InlineAlert, PageHeader, UiIcon } from '@/components/ui';

const { t } = useI18n();

interface PairResponse {
  status?: boolean;
}

const name = ref('');
const pin = ref('');
const submitting = ref(false);
const error = ref('');
const pairedName = ref('');

const normalizedPin = computed(() => pin.value.padStart(4, '0'));
const pinIsValid = computed(() => /^\d{1,4}$/.test(pin.value));

function onPinInput(event: Event): void {
  const input = event.target as HTMLInputElement;
  pin.value = input.value.replace(/\D/g, '').slice(0, 4);
  input.value = pin.value;
}

async function submit(): Promise<void> {
  error.value = '';
  pairedName.value = '';

  const friendlyName = name.value.trim();
  if (!friendlyName) {
    error.value = t('ui.pair.error.name_required');
    return;
  }
  if (!pinIsValid.value) {
    error.value = t('ui.pair.error.pin_invalid');
    return;
  }

  submitting.value = true;
  try {
    const response = await apiPost<PairResponse>('/api/pin', {
      pin: normalizedPin.value,
      name: friendlyName,
    });
    if (response.status !== true) {
      throw new Error(t('ui.pair.error.pin_rejected'));
    }
    pairedName.value = friendlyName;
    pin.value = '';
  } catch (cause) {
    error.value =
      cause instanceof ApiError
        ? t('ui.pair.error.submit')
        : cause instanceof Error
          ? cause.message
          : t('ui.pair.error.submit');
  } finally {
    submitting.value = false;
  }
}

function resetForm(): void {
  name.value = '';
  pin.value = '';
  pairedName.value = '';
  error.value = '';
}
</script>

<template>
  <div class="vs-page vs-page--settings pair-page">
    <PageHeader :title="t('ui.pair.page.title')">
      <template #actions>
        <RouterLink class="vs-button vs-button--secondary" to="/devices">
          <UiIcon name="chevron-left" aria-hidden="true" />
          <span>{{ t('ui.pair.action.back_to_devices') }}</span>
        </RouterLink>
      </template>
    </PageHeader>

    <section class="pair-card vs-surface" aria-labelledby="pair-steps-title">
      <h2 id="pair-steps-title" class="vs-sr-only">{{ t('ui.pair.steps.title') }}</h2>
      <ol class="pair-steps">
        <li>
          <span>{{ t('ui.pair.steps.number', { number: 1 }) }}</span>
          <div>
            <strong>{{ t('ui.pair.steps.open_moonlight_title') }}</strong>
            <p>{{ t('ui.pair.steps.open_moonlight_description') }}</p>
          </div>
        </li>
        <li>
          <span>{{ t('ui.pair.steps.number', { number: 2 }) }}</span>
          <div>
            <strong>{{ t('ui.pair.steps.start_pairing_title') }}</strong>
            <p>{{ t('ui.pair.steps.start_pairing_description') }}</p>
          </div>
        </li>
        <li>
          <span>{{ t('ui.pair.steps.number', { number: 3 }) }}</span>
          <div>
            <strong>{{ t('ui.pair.steps.approve_title') }}</strong>
            <p>{{ t('ui.pair.steps.approve_description') }}</p>
          </div>
        </li>
      </ol>

      <div class="pair-body">
        <h2 id="pair-form-title" class="vs-sr-only">{{ t('ui.pair.form.title') }}</h2>
        <InlineAlert
          v-if="error"
          tone="danger"
          :title="t('ui.pair.alert.failed')"
          announce="assertive"
        >
          {{ error }}
        </InlineAlert>

        <InlineAlert
          v-if="pairedName"
          tone="success"
          :title="t('ui.pair.alert.paired')"
          announce="polite"
        >
          {{ t('ui.pair.notice.connected', { name: pairedName }) }}
          <template #actions>
            <RouterLink class="vs-button vs-button--secondary vs-button--compact" to="/devices">
              {{ t('ui.pair.action.view_devices') }}
            </RouterLink>
            <AppButton
              :label="t('ui.pair.action.pair_another')"
              variant="tertiary"
              size="compact"
              @click="resetForm"
            />
          </template>
        </InlineAlert>

        <form v-if="!pairedName" class="pair-form" novalidate @submit.prevent="submit">
          <label class="vs-field" for="pair-name">
            <span class="vs-field__label">{{ t('pin.device_name') }}</span>
            <input
              id="pair-name"
              v-model="name"
              class="vs-input"
              name="device-name"
              autocomplete="off"
              maxlength="96"
              :placeholder="t('ui.pair.form.name_placeholder')"
              :aria-invalid="error && !name.trim() ? 'true' : undefined"
              required
            />
            <span class="vs-field__helper">{{ t('ui.pair.form.name_helper') }}</span>
          </label>

          <label class="vs-field" for="pair-pin">
            <span class="vs-field__label">{{ t('ui.pair.form.pin_label') }}</span>
            <input
              id="pair-pin"
              class="vs-input pair-pin"
              name="pin"
              type="text"
              inputmode="numeric"
              autocomplete="one-time-code"
              pattern="[0-9]{1,4}"
              maxlength="4"
              :placeholder="t('ui.pair.form.pin_placeholder')"
              :value="pin"
              :aria-invalid="error && !pinIsValid ? 'true' : undefined"
              aria-describedby="pair-pin-help"
              required
              @input="onPinInput"
            />
            <span id="pair-pin-help" class="vs-field__helper">
              {{
                pin
                  ? t('ui.pair.form.pin_submit_as', { pin: normalizedPin })
                  : t('ui.pair.form.pin_helper')
              }}
            </span>
          </label>

          <AppButton
            type="submit"
            variant="primary"
            :label="t('ui.pair.action.approve')"
            icon="check"
            :busy="submitting"
            :busy-label="t('ui.pair.action.approving')"
          />
        </form>

      </div>
    </section>
  </div>
</template>

<style scoped>
.pair-page {
  display: grid;
  gap: var(--vs-space-20);
}

.pair-card {
  overflow: hidden;
}

.pair-steps {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--vs-space-16);
  padding: var(--vs-space-16);
  margin: 0;
  border-bottom: 1px solid var(--vs-color-border-subtle);
  background: var(--vs-color-bg-subtle);
  list-style: none;
}

.pair-steps li {
  display: grid;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: var(--vs-space-8);
}

.pair-steps li > span {
  display: grid;
  width: 20px;
  height: 20px;
  place-items: center;
  border-radius: var(--vs-radius-pill);
  background: var(--vs-color-bg-raised);
  color: var(--vs-color-text-secondary);
  font-size: var(--vs-type-size-helper);
  font-weight: var(--vs-type-weight-semibold);
  font-variant-numeric: tabular-nums;
}

.pair-steps strong {
  display: block;
  font-size: var(--vs-type-size-control);
  line-height: 20px;
}

.pair-steps p {
  margin-top: var(--vs-space-2);
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-helper);
  line-height: var(--vs-type-line-height-helper);
}

.pair-body {
  display: grid;
  gap: var(--vs-space-12);
  padding: var(--vs-space-16);
}

.pair-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 10rem auto;
  align-items: start;
  gap: var(--vs-space-12);
}

.pair-form > :last-child {
  margin-top: calc(var(--vs-type-line-height-control) + var(--vs-space-4));
}

.pair-pin {
  font-family: var(--vs-type-family-mono);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.2em;
}

@media (max-width: 767px) {
  .pair-steps,
  .pair-form {
    grid-template-columns: minmax(0, 1fr);
  }

  .pair-form > :last-child {
    margin-top: 0;
  }
}
</style>
