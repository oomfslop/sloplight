<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useI18n } from 'vue-i18n';

import { ApiError, apiDelete, apiGet, apiPost } from '@/services/client';
import {
  AppButton,
  ConfirmDialog,
  InlineAlert,
  LoadingSkeleton,
  PageHeader,
  UiIcon,
} from '@/components/ui';

interface RouteDefinition {
  path: string;
  methods: string[];
}

interface TokenScope {
  path: string;
  methods: string[];
}

interface TokenRecord {
  hash: string;
  username?: string;
  created_at?: number | string | null;
  scopes: TokenScope[];
}

interface RouteCatalogResponse {
  routes?: unknown;
}

interface TokenResponse {
  token?: unknown;
}

interface MutationResponse {
  status?: boolean;
  error?: string;
  message?: string;
}

const METHOD_ORDER = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] as const;
const METHOD_ORDER_SET = new Set<string>(METHOD_ORDER);

const { locale, t } = useI18n();

const routeCatalog = ref<RouteDefinition[]>([]);
const routeCatalogLoading = ref(false);
const routeCatalogError = ref('');
const routeSearch = ref('');
const draft = reactive<{ path: string; methods: string[] }>({ path: '', methods: [] });
const scopes = ref<TokenScope[]>([]);

const tokens = ref<TokenRecord[]>([]);
const tokensLoading = ref(false);
const tokensError = ref('');
const tokenSearch = ref('');

const creating = ref(false);
const createError = ref('');
const notice = ref('');
const createdToken = ref('');
const copied = ref(false);
const showTokenDialog = ref(false);
const tokenDialog = ref<HTMLDialogElement | null>(null);
let copiedTimer: number | undefined;

const pendingRevoke = ref<TokenRecord | null>(null);
const revokeDialogOpen = ref(false);
const revokingHash = ref('');
const revokeError = ref('');

const filteredRoutes = computed(() => {
  const query = routeSearch.value.trim().toLocaleLowerCase(locale.value);
  if (!query) return routeCatalog.value;
  return routeCatalog.value.filter((route) =>
    route.path.toLocaleLowerCase(locale.value).includes(query),
  );
});

const selectedRoute = computed(() => routeCatalog.value.find((route) => route.path === draft.path));

const availableMethods = computed(() => selectedRoute.value?.methods ?? []);
const canAddScope = computed(
  () => Boolean(draft.path) && draft.methods.length > 0 && availableMethods.value.length > 0,
);

const filteredTokens = computed(() => {
  const query = tokenSearch.value.trim().toLocaleLowerCase(locale.value);
  return tokens.value
    .filter((token) => {
      if (!query) return true;
      return (
        token.hash.toLocaleLowerCase(locale.value).includes(query) ||
        token.scopes.some((scope) => scope.path.toLocaleLowerCase(locale.value).includes(query))
      );
    })
    .slice()
    .sort((left, right) => timestampValue(right.created_at) - timestampValue(left.created_at));
});

function orderMethods(input: unknown): string[] {
  const methods = Array.from(
    new Set(
      (Array.isArray(input) ? input : [])
        .map((method) =>
          String(method ?? '')
            .trim()
            .toUpperCase(),
        )
        .filter(Boolean),
    ),
  );
  return [
    ...METHOD_ORDER.filter((method) => methods.includes(method)),
    ...methods.filter((method) => !METHOD_ORDER_SET.has(method)).sort(),
  ];
}

function normalizeRoute(value: unknown): RouteDefinition | null {
  if (!value || typeof value !== 'object') return null;
  const route = value as { path?: unknown; methods?: unknown };
  const path = typeof route.path === 'string' ? route.path.trim() : '';
  if (!path) return null;
  return { path, methods: orderMethods(route.methods) };
}

function normalizeScope(value: unknown): TokenScope | null {
  if (!value || typeof value !== 'object') return null;
  const scope = value as { path?: unknown; route?: unknown; methods?: unknown; verbs?: unknown };
  const path = String(scope.path ?? scope.route ?? '').trim();
  const methods = orderMethods(scope.methods ?? scope.verbs);
  return path && methods.length ? { path, methods } : null;
}

function normalizeToken(value: unknown): TokenRecord | null {
  if (!value || typeof value !== 'object') return null;
  const token = value as {
    hash?: unknown;
    id?: unknown;
    token_hash?: unknown;
    username?: unknown;
    created_at?: unknown;
    scopes?: unknown;
  };
  const hash = String(token.hash ?? token.id ?? token.token_hash ?? '').trim();
  if (!hash) return null;
  const scopes = (Array.isArray(token.scopes) ? token.scopes : [])
    .map(normalizeScope)
    .filter((scope): scope is TokenScope => Boolean(scope));
  return {
    hash,
    username: typeof token.username === 'string' ? token.username : undefined,
    created_at:
      typeof token.created_at === 'number' || typeof token.created_at === 'string'
        ? token.created_at
        : null,
    scopes,
  };
}

function timestampValue(value: number | string | null | undefined): number {
  if (value === null || value === undefined || value === '') return 0;
  const numeric = typeof value === 'number' ? value : Number(value);
  if (Number.isFinite(numeric)) return numeric < 10_000_000_000 ? numeric * 1000 : numeric;
  const parsed = Date.parse(String(value));
  return Number.isFinite(parsed) ? parsed : 0;
}

function formatCreatedAt(value: number | string | null | undefined): string {
  const timestamp = timestampValue(value);
  if (!timestamp) return t('ui.api_tokens.unknown_date');
  return new Intl.DateTimeFormat(locale.value || undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(timestamp));
}

function shortHash(hash: string): string {
  return hash.length > 12 ? `${hash.slice(0, 6)}…${hash.slice(-4)}` : hash;
}

function errorDetail(cause: unknown): string {
  if (cause instanceof ApiError && cause.payload && typeof cause.payload === 'object') {
    const payload = cause.payload as { error?: unknown; message?: unknown };
    const detail = payload.error ?? payload.message;
    if (typeof detail === 'string' && detail.trim()) return detail.trim();
  }
  if (cause instanceof Error && !(cause instanceof ApiError) && cause.message.trim()) {
    return cause.message.trim();
  }
  return '';
}

function withDetail(prefix: string, cause: unknown): string {
  const detail = errorDetail(cause);
  return detail ? `${prefix}: ${detail}` : prefix;
}

async function loadRouteCatalog(): Promise<void> {
  routeCatalogLoading.value = true;
  routeCatalogError.value = '';
  try {
    const payload = await apiGet<RouteCatalogResponse | unknown[]>('/api/token/routes');
    const rawRoutes = Array.isArray(payload)
      ? payload
      : Array.isArray(payload.routes)
        ? payload.routes
        : [];
    routeCatalog.value = rawRoutes
      .map(normalizeRoute)
      .filter((route): route is RouteDefinition => Boolean(route))
      .sort((left, right) => left.path.localeCompare(right.path));
    if (draft.path && !routeCatalog.value.some((route) => route.path === draft.path)) {
      draft.path = '';
      draft.methods = [];
    }
  } catch (cause) {
    routeCatalog.value = [];
    routeCatalogError.value = withDetail(t('ui.api_tokens.errors.routes_load'), cause);
  } finally {
    routeCatalogLoading.value = false;
  }
}

async function loadTokens(): Promise<void> {
  tokensLoading.value = true;
  tokensError.value = '';
  try {
    const payload = await apiGet<unknown>('/api/tokens');
    const rawTokens = Array.isArray(payload)
      ? payload
      : payload &&
          typeof payload === 'object' &&
          Array.isArray((payload as { tokens?: unknown }).tokens)
        ? (payload as { tokens: unknown[] }).tokens
        : [];
    tokens.value = rawTokens
      .map(normalizeToken)
      .filter((token): token is TokenRecord => Boolean(token));
  } catch (cause) {
    tokensError.value = withDetail(t('ui.api_tokens.errors.tokens_load'), cause);
  } finally {
    tokensLoading.value = false;
  }
}

async function refresh(): Promise<void> {
  await Promise.all([loadRouteCatalog(), loadTokens()]);
}

function addScope(): void {
  if (!canAddScope.value) return;
  const methods = orderMethods(draft.methods);
  const existing = scopes.value.find((scope) => scope.path === draft.path);
  if (existing) {
    existing.methods = orderMethods([...existing.methods, ...methods]);
  } else {
    scopes.value.push({ path: draft.path, methods });
  }
  draft.path = '';
  draft.methods = [];
  routeSearch.value = '';
}

function removeScope(path: string): void {
  scopes.value = scopes.value.filter((scope) => scope.path !== path);
}

function selectRoute(): void {
  draft.methods = [];
}

async function createToken(): Promise<void> {
  createError.value = '';
  notice.value = '';
  if (!scopes.value.length) {
    createError.value = t('ui.api_tokens.errors.scope_required');
    return;
  }

  creating.value = true;
  try {
    const payload = await apiPost<TokenResponse>('/api/token', {
      scopes: scopes.value.map((scope) => ({ path: scope.path, methods: scope.methods })),
    });
    const token = typeof payload?.token === 'string' ? payload.token : '';
    if (!token) throw new Error(t('ui.api_tokens.errors.token_missing'));
    createdToken.value = token;
    copied.value = false;
    showTokenDialog.value = true;
    scopes.value = [];
    draft.path = '';
    draft.methods = [];
    notice.value = t('ui.api_tokens.notices.created');
    await loadTokens();
  } catch (cause) {
    createError.value = withDetail(t('ui.api_tokens.errors.create'), cause);
  } finally {
    creating.value = false;
  }
}

async function copyToken(): Promise<void> {
  if (!createdToken.value) return;
  copied.value = false;
  try {
    await navigator.clipboard.writeText(createdToken.value);
  } catch {
    const textarea = document.createElement('textarea');
    textarea.value = createdToken.value;
    textarea.setAttribute('readonly', '');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    textarea.remove();
  }
  copied.value = true;
  if (copiedTimer !== undefined) window.clearTimeout(copiedTimer);
  copiedTimer = window.setTimeout(() => {
    copied.value = false;
  }, 1800);
}

async function syncTokenDialog(open: boolean): Promise<void> {
  await nextTick();
  const element = tokenDialog.value;
  if (!element) return;
  if (open && !element.open) element.showModal();
  if (!open && element.open) element.close();
}

function closeTokenDialog(): void {
  showTokenDialog.value = false;
}

function requestRevoke(token: TokenRecord): void {
  pendingRevoke.value = token;
  revokeError.value = '';
  revokeDialogOpen.value = true;
}

function updateRevokeDialog(open: boolean): void {
  revokeDialogOpen.value = open;
  if (!open && !revokingHash.value) pendingRevoke.value = null;
}

async function confirmRevoke(): Promise<void> {
  const token = pendingRevoke.value;
  if (!token || revokingHash.value) return;
  revokingHash.value = token.hash;
  revokeError.value = '';
  try {
    const result = await apiDelete<MutationResponse>(
      `/api/token/${encodeURIComponent(token.hash)}`,
      {},
    );
    if (result.status === false) throw new Error(result.error || result.message || '');
    tokens.value = tokens.value.filter((entry) => entry.hash !== token.hash);
    notice.value = t('ui.api_tokens.notices.revoked');
    revokeDialogOpen.value = false;
  } catch (cause) {
    revokeError.value = withDetail(t('ui.api_tokens.errors.revoke'), cause);
  } finally {
    revokingHash.value = '';
    if (!revokeDialogOpen.value) pendingRevoke.value = null;
  }
}

watch(showTokenDialog, (open) => {
  void syncTokenDialog(open);
});

onMounted(() => {
  void refresh();
});

onBeforeUnmount(() => {
  if (copiedTimer !== undefined) window.clearTimeout(copiedTimer);
  if (tokenDialog.value?.open) tokenDialog.value.close();
});
</script>

<template>
  <div class="vs-page api-tokens-page">
    <PageHeader :title="t('ui.api_tokens.title')">
      <template #actions>
        <AppButton
          :label="t('_common.refresh')"
          icon="refresh"
          :busy="routeCatalogLoading || tokensLoading"
          :busy-label="t('ui.api_tokens.actions.refreshing')"
          @click="refresh"
        />
      </template>
    </PageHeader>

    <div class="api-tokens-stack">
      <InlineAlert
        v-if="notice"
        tone="success"
        :title="t('ui.api_tokens.alert.success_title')"
        announce="polite"
        :dismiss-label="t('_common.dismiss')"
        @dismiss="notice = ''"
      >
        {{ notice }}
      </InlineAlert>
      <InlineAlert
        v-if="revokeError"
        tone="danger"
        :title="t('ui.api_tokens.alert.action_failed')"
        announce="assertive"
        :dismiss-label="t('_common.dismiss')"
        @dismiss="revokeError = ''"
      >
        {{ revokeError }}
      </InlineAlert>

      <section class="api-token-panel vs-surface" aria-labelledby="api-token-builder-title">
        <h2 id="api-token-builder-title" class="api-token-panel__title">
          {{ t('ui.api_tokens.builder.title') }}
        </h2>

        <InlineAlert
          v-if="routeCatalogError"
          tone="danger"
          :title="t('ui.api_tokens.alert.routes_unavailable')"
          :dismiss-label="t('_common.dismiss')"
          @dismiss="routeCatalogError = ''"
        >
          {{ routeCatalogError }}
        </InlineAlert>
        <InlineAlert
          v-else-if="!routeCatalogLoading && !routeCatalog.length"
          tone="warning"
          :title="t('ui.api_tokens.alert.no_routes_title')"
        >
          {{ t('ui.api_tokens.alert.no_routes_description') }}
        </InlineAlert>

        <div class="api-token-route-row">
          <label class="vs-field" for="api-token-route-search">
            <span class="vs-field__label">{{ t('ui.api_tokens.builder.search_label') }}</span>
            <span class="api-token-search-control">
              <UiIcon name="search" :size="14" aria-hidden="true" />
              <input
                id="api-token-route-search"
                v-model="routeSearch"
                class="vs-input"
                type="search"
                autocomplete="off"
                :placeholder="t('ui.api_tokens.builder.search_placeholder')"
                :disabled="routeCatalogLoading || !routeCatalog.length"
              />
            </span>
          </label>
          <label class="vs-field" for="api-token-route">
            <span class="vs-field__label">
              {{ t('ui.api_tokens.builder.route_label') }}
              <span class="api-token-muted">
                {{ t('ui.api_tokens.builder.route_count', { count: filteredRoutes.length }) }}
              </span>
            </span>
            <select
              id="api-token-route"
              v-model="draft.path"
              class="vs-select api-token-route-select"
              :disabled="routeCatalogLoading || !filteredRoutes.length"
              @change="selectRoute"
            >
              <option value="">{{ t('ui.api_tokens.builder.route_placeholder') }}</option>
              <option v-for="route in filteredRoutes" :key="route.path" :value="route.path">
                {{ route.path }}
              </option>
            </select>
          </label>
        </div>

        <div v-if="selectedRoute" class="api-token-methods" role="group" aria-labelledby="api-methods-title">
          <span id="api-methods-title" class="vs-field__label">
            {{ t('ui.api_tokens.builder.methods_label') }}
          </span>
          <label v-for="method in availableMethods" :key="method" class="vs-checkbox api-token-method">
            <input v-model="draft.methods" type="checkbox" :value="method" />
            <span class="api-token-method__code">{{ method }}</span>
          </label>
          <span v-if="!availableMethods.length" class="api-token-muted">
            {{ t('ui.api_tokens.builder.no_methods') }}
          </span>
          <AppButton
            :label="t('ui.api_tokens.actions.add_scope')"
            icon="plus"
            size="compact"
            :disabled="!canAddScope"
            @click="addScope"
          />
        </div>

        <ul v-if="scopes.length" class="api-token-rows" aria-live="polite">
          <li v-for="scope in scopes" :key="scope.path">
            <code>{{ scope.path }}</code>
            <span class="api-token-chips">
              <span v-for="method in scope.methods" :key="method" class="api-token-chip">
                {{ method }}
              </span>
            </span>
            <AppButton
              :label="t('ui.api_tokens.actions.remove_scope')"
              icon="x"
              variant="tertiary"
              size="compact"
              icon-only
              :aria-label="t('ui.api_tokens.actions.remove_scope_named', { path: scope.path })"
              @click="removeScope(scope.path)"
            />
          </li>
        </ul>
        <p v-else class="api-token-muted">{{ t('ui.api_tokens.builder.empty_description') }}</p>

        <InlineAlert
          v-if="createError"
          tone="danger"
          :title="t('ui.api_tokens.alert.create_failed')"
          :dismiss-label="t('_common.dismiss')"
          @dismiss="createError = ''"
        >
          {{ createError }}
        </InlineAlert>

        <div class="api-token-generate">
          <AppButton
            :label="t('ui.api_tokens.actions.generate')"
            icon="key"
            variant="primary"
            :disabled="!scopes.length"
            :busy="creating"
            :busy-label="t('ui.api_tokens.actions.generating')"
            @click="createToken"
          />
          <span class="api-token-muted">{{ t('ui.api_tokens.builder.generate_hint') }}</span>
        </div>
      </section>

      <section class="api-token-panel vs-surface" aria-labelledby="active-tokens-title">
        <header class="api-token-panel__heading">
          <h2 id="active-tokens-title" class="api-token-panel__title">
            {{ t('ui.api_tokens.active.title') }}
          </h2>
          <span class="api-token-muted" aria-live="polite">
            {{
              t('ui.api_tokens.active.result_count', {
                shown: filteredTokens.length,
                total: tokens.length,
              })
            }}
          </span>
          <span class="api-token-search-control api-token-panel__search">
            <UiIcon name="search" :size="14" aria-hidden="true" />
            <input
              id="api-token-search"
              v-model="tokenSearch"
              class="vs-input"
              type="search"
              autocomplete="off"
              :aria-label="t('ui.api_tokens.active.search_label')"
              :placeholder="t('ui.api_tokens.active.search_placeholder')"
            />
          </span>
        </header>

        <InlineAlert
          v-if="tokensError"
          tone="danger"
          :title="t('ui.api_tokens.alert.tokens_unavailable')"
          :dismiss-label="t('_common.dismiss')"
          @dismiss="tokensError = ''"
        >
          {{ tokensError }}
        </InlineAlert>

        <div v-if="tokensLoading" class="api-token-loading" :aria-label="t('_common.loading')">
          <LoadingSkeleton v-for="item in 2" :key="item" height="2rem" />
        </div>
        <p v-else-if="!tokens.length" class="api-token-muted">
          {{ t('ui.api_tokens.active.empty_title') }}
        </p>
        <p v-else-if="!filteredTokens.length" class="api-token-muted">
          {{ t('ui.api_tokens.active.filtered_empty_title') }}
        </p>
        <ul v-else class="api-token-rows api-token-rows--tokens">
          <li v-for="token in filteredTokens" :key="token.hash">
            <div class="api-token-record__identity">
              <code>{{ shortHash(token.hash) }}</code>
              <span class="api-token-muted">
                {{ t('ui.api_tokens.active.created', { date: formatCreatedAt(token.created_at) }) }}
                <template v-if="token.username"> · {{ token.username }}</template>
              </span>
            </div>
            <div class="api-token-record__scopes">
              <div v-for="scope in token.scopes" :key="scope.path" class="api-token-record__scope">
                <code>{{ scope.path }}</code>
                <span class="api-token-chips">
                  <span v-for="method in scope.methods" :key="method" class="api-token-chip">
                    {{ method }}
                  </span>
                </span>
              </div>
            </div>
            <AppButton
              :label="t('ui.api_tokens.actions.revoke')"
              icon="trash"
              variant="tertiary"
              size="compact"
              :busy="revokingHash === token.hash"
              :aria-label="t('ui.api_tokens.actions.revoke_named', { hash: shortHash(token.hash) })"
              @click="requestRevoke(token)"
            />
          </li>
        </ul>
      </section>
    </div>

    <dialog
      ref="tokenDialog"
      class="api-token-dialog"
      :aria-labelledby="'api-token-dialog-title'"
      @cancel="closeTokenDialog"
      @close="showTokenDialog = false"
    >
      <section class="api-token-dialog__panel">
        <div class="api-token-dialog__heading">
          <div class="api-token-dialog__icon" aria-hidden="true">
            <UiIcon name="key" :size="22" />
          </div>
          <div>
            <h2 id="api-token-dialog-title">{{ t('ui.api_tokens.reveal.title') }}</h2>
            <p>{{ t('ui.api_tokens.reveal.description') }}</p>
          </div>
        </div>
        <InlineAlert tone="warning" :title="t('ui.api_tokens.reveal.warning_title')">
          {{ t('ui.api_tokens.reveal.warning') }}
        </InlineAlert>
        <div class="api-token-dialog__value">
          <code>{{ createdToken }}</code>
        </div>
        <div class="api-token-dialog__actions">
          <AppButton
            :label="copied ? t('ui.api_tokens.actions.copied') : t('ui.api_tokens.actions.copy')"
            :icon="copied ? 'check' : 'copy'"
            variant="primary"
            @click="copyToken"
          />
          <AppButton :label="t('_common.dismiss')" @click="closeTokenDialog" />
        </div>
      </section>
    </dialog>

    <ConfirmDialog
      :open="revokeDialogOpen"
      :title="t('ui.api_tokens.revoke.title')"
      :description="
        pendingRevoke
          ? t('ui.api_tokens.revoke.description', { hash: shortHash(pendingRevoke.hash) })
          : ''
      "
      :confirm-label="t('ui.api_tokens.actions.revoke')"
      :cancel-label="t('_common.cancel')"
      tone="danger"
      :busy="Boolean(revokingHash)"
      :busy-label="t('ui.api_tokens.actions.revoking')"
      :close-on-confirm="false"
      @update:open="updateRevokeDialog"
      @confirm="confirmRevoke"
    />
  </div>
</template>

<style scoped>
.api-tokens-page {
  max-inline-size: var(--vs-content-width-general);
}

.api-tokens-stack {
  display: grid;
  gap: var(--vs-space-16);
  margin-block-start: var(--vs-space-16);
}

.api-token-panel {
  display: grid;
  gap: var(--vs-space-12);
  padding: var(--vs-space-16);
}

.api-token-panel__title {
  font-size: var(--vs-type-size-section);
  line-height: var(--vs-type-line-height-section);
}

.api-token-panel__heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vs-space-8) var(--vs-space-12);
}

.api-token-panel__search {
  inline-size: min(100%, 20rem);
  margin-inline-start: auto;
}

.api-token-muted {
  color: var(--vs-color-text-muted);
  font-size: var(--vs-type-size-helper);
  font-weight: var(--vs-type-weight-regular);
}

.api-token-route-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 2fr);
  gap: var(--vs-space-12);
}

.api-token-search-control {
  position: relative;
  display: block;
}

.api-token-search-control > .vs-icon {
  position: absolute;
  inset-block-start: 50%;
  inset-inline-start: var(--vs-space-12);
  color: var(--vs-color-text-muted);
  pointer-events: none;
  translate: 0 -50%;
}

.api-token-search-control > .vs-input {
  padding-inline-start: var(--vs-space-32);
}

.api-token-route-select,
.api-token-method__code,
.api-token-chip,
.api-token-rows code {
  font-family: var(--vs-type-family-mono);
  font-size: var(--vs-type-size-metadata);
}

.api-token-methods {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vs-space-12);
}

.api-token-rows {
  display: grid;
  margin: 0;
  padding: 0;
  border: var(--vs-border-width) solid var(--vs-color-border-subtle);
  border-radius: var(--vs-radius-control);
  list-style: none;
}

.api-token-rows > li {
  display: flex;
  align-items: center;
  gap: var(--vs-space-12);
  padding: var(--vs-space-4) var(--vs-space-4) var(--vs-space-4) var(--vs-space-12);
}

.api-token-rows > li + li {
  border-block-start: var(--vs-border-width) solid var(--vs-color-border-subtle);
}

.api-token-rows > li > :last-child {
  margin-inline-start: auto;
}

.api-token-rows code {
  min-inline-size: 0;
  overflow-wrap: anywhere;
}

.api-token-rows--tokens > li {
  align-items: flex-start;
  padding-block: var(--vs-space-8);
}

.api-token-record__identity {
  display: grid;
  flex: 0 0 14rem;
  gap: var(--vs-space-2);
}

.api-token-record__scopes {
  display: grid;
  min-inline-size: 0;
  gap: var(--vs-space-4);
}

.api-token-record__scope {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vs-space-8);
}

.api-token-chips {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--vs-space-4);
}

.api-token-chip {
  padding: 0 var(--vs-space-4);
  border-radius: var(--vs-radius-subtle);
  background: var(--vs-color-bg-subtle);
  color: var(--vs-color-text-secondary);
  font-size: var(--vs-type-size-helper);
}

.api-token-generate {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--vs-space-12);
}

.api-token-loading {
  display: grid;
  gap: var(--vs-space-8);
}

.api-token-dialog {
  inline-size: min(calc(100% - var(--vs-space-32)), 42rem);
  max-inline-size: calc(100% - var(--vs-space-32));
  padding: 0;
  border: var(--vs-border-width) solid var(--vs-color-border-strong);
  border-radius: var(--vs-radius-dialog);
  background: var(--vs-color-bg-surface);
  color: var(--vs-color-text-primary);
  box-shadow: var(--vs-shadow-overlay);
}

.api-token-dialog::backdrop {
  background: var(--vs-color-overlay-backdrop);
}

.api-token-dialog__panel {
  display: grid;
  gap: var(--vs-space-20);
  padding: var(--vs-space-24);
}

.api-token-dialog__heading {
  display: flex;
  align-items: flex-start;
  gap: var(--vs-space-12);
}

.api-token-dialog__icon {
  display: grid;
  inline-size: 2.5rem;
  block-size: 2.5rem;
  flex: none;
  place-items: center;
  border-radius: var(--vs-radius-card);
  background: color-mix(in srgb, var(--vs-color-status-success) 15%, transparent);
  color: var(--vs-color-status-success);
}

.api-token-dialog h2,
.api-token-dialog p {
  margin: 0;
}

.api-token-dialog h2 {
  font-size: var(--vs-type-size-panel);
  line-height: var(--vs-type-line-height-panel);
}

.api-token-dialog__heading p {
  margin-block-start: var(--vs-space-4);
  color: var(--vs-color-text-secondary);
}

.api-token-dialog__value {
  padding: var(--vs-space-16);
  border: var(--vs-border-width) solid var(--vs-color-border-strong);
  border-radius: var(--vs-radius-control);
  background: var(--vs-color-bg-canvas);
}

.api-token-dialog__value code {
  display: block;
  overflow-wrap: anywhere;
  color: var(--vs-color-text-primary);
  user-select: all;
}

.api-token-dialog__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: var(--vs-space-8);
}

@media (max-width: 767px) {
  .api-token-route-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .api-token-panel__search {
    inline-size: 100%;
  }

  .api-token-rows--tokens > li {
    flex-wrap: wrap;
  }

  .api-token-record__identity {
    flex-basis: 100%;
  }
}
</style>
