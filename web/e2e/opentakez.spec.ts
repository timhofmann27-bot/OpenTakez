import { expect, test, type Page } from '@playwright/test'
import { MEDIA, mockApi } from './mock-api'

const LINK = 'https://youtu.be/abc'

async function resolveAndDownload(page: Page, format = 'MP3') {
  await page.goto('/')
  await page.getByLabel('Link').fill(LINK)
  await page.getByRole('button', { name: 'Check link' }).click()
  await expect(page.getByRole('heading', { name: MEDIA.title })).toBeVisible()
  await page.getByText(format, { exact: true }).click()
  await page.getByRole('button', { name: 'Download' }).click()
  await expect(page).toHaveURL(/\/downloads$/)
}

test('share target prefills the link and resolves it', async ({ page }) => {
  const mock = await mockApi(page)
  await page.goto('/share?text=Look%20https%3A%2F%2Fyoutu.be%2Fabc')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByLabel('Link')).toHaveValue(`Look ${LINK}`)
  await expect(page.getByRole('heading', { name: MEDIA.title })).toBeVisible()
  expect(mock.resolveBodies).toEqual([{ url: `Look ${LINK}` }])
})

test('download flow: resolve, pick MP3, finish via SSE, save, history', async ({ page }) => {
  const mock = await mockApi(page)
  await resolveAndDownload(page)
  expect(mock.jobBodies).toEqual([{ url: MEDIA.url, option: 'audio-mp3' }])

  await expect(page.getByRole('status').filter({ hasText: /^Done$/ })).toBeVisible()
  const download = page.waitForEvent('download')
  await page.getByRole('button', { name: 'Save' }).click()
  const url = (await download).url()
  expect(url).toMatch(/\/api\/jobs\/job1\/files\/0\?ticket=tk-job1-\d+$/)
  expect(url).not.toContain('token=')
  expect(mock.mediaRequests.every((u) => !u.includes('token='))).toBe(true)

  await page.getByRole('link', { name: 'History' }).click()
  await expect(page).toHaveURL(/\/history$/)
  await expect(page.getByText(MEDIA.title)).toBeVisible()
})

test.describe('errors', () => {
  test('unsupported site shows the translated message', async ({ page }) => {
    await mockApi(page, { resolveError: { status: 400, detail: "This site isn't supported" } })
    await page.goto('/')
    await page.getByLabel('Link').fill('https://example.com/x')
    await page.getByRole('button', { name: 'Check link' }).click()
    await expect(page.getByRole('alert')).toHaveText('This site isn’t supported yet.')
  })

  test('platform error shows the server detail', async ({ page }) => {
    await mockApi(page, { resolveError: { status: 422, detail: 'Video unavailable: private video' } })
    await page.goto('/')
    await page.getByLabel('Link').fill(LINK)
    await page.getByRole('button', { name: 'Check link' }).click()
    await expect(page.getByRole('alert')).toHaveText('Video unavailable: private video')
  })
})

test('token dialog: wrong token rejected, right token accepted and sent', async ({ page }) => {
  const mock = await mockApi(page, {
    health: { status: 'ok', version: '0.1.0', yt_dlp: 'x', auth_required: true, platforms: ['youtube'] },
    tokenCheck: (auth) => (auth === 'Bearer secret' ? 404 : 401),
  })
  await page.goto('/')
  const dialog = page.getByRole('dialog', { name: 'Access token required' })
  await expect(dialog).toBeVisible()

  await dialog.getByLabel('Access token').fill('nope')
  await dialog.getByRole('button', { name: 'Continue' }).click()
  await expect(dialog.getByRole('alert')).toHaveText('That token was not accepted.')
  await expect(dialog).toBeVisible()

  await dialog.getByLabel('Access token').fill('secret')
  await dialog.getByRole('button', { name: 'Continue' }).click()
  await expect(dialog).toBeHidden()

  await page.getByLabel('Link').fill(LINK)
  await page.getByRole('button', { name: 'Check link' }).click()
  await expect(page.getByRole('heading', { name: MEDIA.title })).toBeVisible()
  const resolveCall = mock.auth.filter((a) => a.path === '/api/resolve').at(-1)
  expect(resolveCall?.authorization).toBe('Bearer secret')
})

test('expired history item offers "Download again"', async ({ page }) => {
  const mock = await mockApi(page)
  await resolveAndDownload(page)
  await expect(page.getByRole('status').filter({ hasText: /^Done$/ })).toBeVisible()
  await page.getByRole('link', { name: 'History' }).click()
  await expect(page.getByText(MEDIA.title)).toBeVisible()

  mock.jobLookup = 'gone'
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByText('No longer on the server')).toBeVisible()
  await page.getByRole('button', { name: 'Download again' }).click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByLabel('Link')).toHaveValue(LINK)
})
