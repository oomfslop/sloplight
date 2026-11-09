import { test, expect, type Page } from '@playwright/test';

const uuid = '11111111-1111-4111-8111-111111111111';
const name = 'Display regression game';

async function setup(page: Page, app: Record<string, unknown>, host: Record<string, unknown> = {}) {
  const config = {
    virtual_display_mode: 'disabled',
    virtual_display_layout: 'extended_primary_isolated',
    dd_configuration_option: 'verify_only',
    output_name: '',
    capture: 'wgc',
    ...host,
  };
  let apps = [{ uuid, name, cmd: 'C:\\Games\\game.exe', ...app }];
  const saves: Record<string, unknown>[] = [];
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) {
      await route.continue();
      return;
    }
    let body: unknown = { status: true };
    if (path === '/api/auth/status') {
      body = { authenticated: true, login_required: false, credentials_configured: true };
    } else if (path === '/api/csrf-token') {
      body = { csrf_token: 'test-token' };
    } else if (path === '/api/configLocale') {
      body = { locale: 'en' };
    } else if (path === '/api/metadata') {
      body = { platform: 'windows', windows_build_number: 26100, version: '2.0.2' };
    } else if (path === '/api/config') {
      body = config;
    } else if (path === '/api/apps' && route.request().method() === 'POST') {
      const saved = route.request().postDataJSON();
      saves.push(saved);
      apps = [saved];
    } else if (path === '/api/apps') {
      body = { apps };
    } else if (path === '/api/auth/sessions') {
      body = { sessions: [] };
    } else if (path === '/api/playnite/status' || path === '/api/steam/status') {
      body = { installed: false, enabled: false, active: false };
    } else if (path.endsWith('/games') || path === '/api/display-devices') {
      body = [];
    }
    await route.fulfill({ json: body });
  });
  await page.route('**/assets/changelog.json', (route) =>
    route.fulfill({ json: { releases: [] } }),
  );
  return { saves, config };
}

async function open(page: Page) {
  await page.goto(`/library/${uuid}`);
  await expect(page.locator('#app-display-layout')).toBeVisible();
}

async function save(page: Page) {
  const button = page.getByRole('button', { name: 'Save application', exact: true }).last();
  // Save an unrelated edit as well: merely opening the editor must preserve display policy.
  if (!(await button.isEnabled())) {
    await page.locator('#app-name').fill(`${name} saved`);
  }
  await button.click();
}

for (const width of [1440, 393]) {
  test(`choosing a primary isolated layout enables it and survives save/reopen at ${width}px`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 1000 });
    const { saves } = await setup(page, {
      'virtual-screen': true,
      'virtual-display-mode': 'per_client',
      'virtual-display-layout': 'extended',
    });
    await open(page);
    await page.locator('#app-display-layout').selectOption('extended_primary_isolated');
    await expect(page.locator('#app-display-configuration')).toHaveValue('ensure_active');
    await expect(page.getByTestId('app-display-layout-conflict')).toHaveCount(0);
    await save(page);
    await expect.poll(() => saves.length).toBe(1);
    expect(saves[0]).toMatchObject({
      'virtual-screen': true,
      'virtual-display-mode': 'per_client',
      'virtual-display-layout': 'extended_primary_isolated',
      'dd-configuration-option': 'ensure_active',
    });
    await open(page);
    await expect(page.getByTestId('app-display-layout-conflict')).toHaveCount(0);
    await expect(page.locator('#app-display-mode')).toHaveValue('per_client');
    await expect(page.locator('#app-display-layout')).toHaveValue('extended_primary_isolated');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
  });
}

for (const action of ['verify_only', 'disabled']) {
  test(`exposes an existing ${action} conflict without changing it on load`, async ({ page }) => {
    const { saves } = await setup(page, {
      'virtual-screen': true,
      'virtual-display-mode': 'per_client',
      'virtual-display-layout': 'extended_primary_isolated',
      'dd-configuration-option': action,
    });
    await open(page);
    await expect(page.getByTestId('app-display-layout-conflict')).toBeVisible();
    if (action === 'verify_only') {
      await page.setViewportSize({ width: 393, height: 1000 });
      await page.getByTestId('app-display-layout-conflict').screenshot({
        path: test.info().outputPath('layout-warning.png'),
        timeout: 5000,
      });
    }
    await save(page);
    await expect.poll(() => saves.length).toBe(1);
    expect(saves[0]['dd-configuration-option']).toBe(action);
    await open(page);
    await page.getByRole('button', { name: 'Apply this layout for this game', exact: true }).click();
    await expect(page.getByTestId('app-display-layout-conflict')).toHaveCount(0);
    await save(page);
    await expect.poll(() => saves.length).toBe(2);
    expect(saves[1]['dd-configuration-option']).toBe('ensure_active');
  });
}

test('preserves explicit choices equal to host defaults when the host later changes', async ({
  page,
}) => {
  const { saves, config } = await setup(
    page,
    {
      'virtual-screen': true,
      'virtual-display-mode': 'per_client',
      'virtual-display-layout': 'extended_primary_isolated',
      'dd-configuration-option': 'ensure_active',
    },
    {
      virtual_display_mode: 'per_client',
      output_name: 'sunshine:virtual_display',
    },
  );
  await open(page);
  await save(page);
  await expect.poll(() => saves.length).toBe(1);
  expect(saves[0]).toMatchObject({
    'virtual-screen': true,
    'virtual-display-mode': 'per_client',
    'virtual-display-layout': 'extended_primary_isolated',
  });
  config.virtual_display_mode = 'disabled';
  config.virtual_display_layout = 'exclusive';
  config.output_name = '';
  await open(page);
  await expect(page.locator('#app-display-mode')).toHaveValue('per_client');
  await expect(page.locator('#app-display-layout')).toHaveValue('extended_primary_isolated');
});

test('switching a physical game to per-client virtual display persists the selection', async ({
  page,
}) => {
  const { saves } = await setup(page, {
    'virtual-display-mode': 'disabled',
    'virtual-screen': false,
  });
  await page.goto(`/library/${uuid}`);
  await page.getByRole('radio', { name: 'Use Vibeshine virtual display', exact: true }).check();
  await page.locator('#app-display-mode').selectOption('per_client');
  await page.locator('#app-display-layout').selectOption('extended_primary_isolated');
  await save(page);
  await expect.poll(() => saves.length).toBe(1);
  expect(saves[0]).toMatchObject({
    'virtual-screen': true,
    'virtual-display-mode': 'per_client',
    'virtual-display-layout': 'extended_primary_isolated',
    'dd-configuration-option': 'ensure_active',
  });
  await open(page);
});
