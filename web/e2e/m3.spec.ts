import { expect, test, type Page } from '@playwright/test'
import { MEDIA, mockApi } from './mock-api'

const SHOTS = '../docs/design/evidence'

const PHOTO_POST = {
  url: 'https://www.instagram.com/p/abc/',
  platform: 'instagram',
  kind: 'image',
  title: 'Weekend trip',
  uploader: 'someone',
  thumbnail: null,
  duration: null,
  options: ['image-original'],
  item_count: 10,
  total_items: 14,
}

async function resolve(page: Page, link: string, title: string) {
  await page.goto('/')
  await page.getByLabel('Link').fill(link)
  await page.getByRole('button', { name: 'Check link' }).click()
  await expect(page.getByRole('heading', { name: title })).toBeVisible()
}

test('image post preview shows the item count and truncation notice', async ({ page }) => {
  const mock = await mockApi(page, { media: PHOTO_POST })
  await resolve(page, PHOTO_POST.url, PHOTO_POST.title)

  await expect(page.getByText('10 items')).toBeVisible()
  await expect(page.getByText('Only the first 10 of 14 items will be downloaded.')).toBeVisible()
  await expect(page.getByRole('radio', { name: 'Original' })).toBeChecked()
  await expect(page.getByText('Photos')).toBeVisible()
  await page.screenshot({ path: `${SHOTS}/390x844-m3-image-preview.png` })

  await page.getByRole('button', { name: 'Download' }).click()
  await expect(page).toHaveURL(/\/downloads$/)
  expect(mock.jobBodies).toEqual([{ url: PHOTO_POST.url, option: 'image-original' }])
})

test('multi-file job offers a ZIP and one link per file', async ({ page }) => {
  await mockApi(page, {
    media: PHOTO_POST,
    result: {
      files: ['IMG_0001.jpg', 'IMG_0002.jpg', 'a-very-long-photo-file-name-that-should-truncate-nicely-0003.jpg'],
      sizes: [2_400_000, 1_900_000, 3_100_000],
    },
  })
  await resolve(page, PHOTO_POST.url, PHOTO_POST.title)
  await page.getByRole('button', { name: 'Download' }).click()

  const zip = page.getByRole('button', { name: 'Save all as ZIP' })
  await expect(zip).toBeVisible()
  await expect(page.getByText('3 files')).toBeVisible()
  await expect(page.getByText('2.3 MB')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save IMG_0002.jpg' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)
  await page.screenshot({ path: `${SHOTS}/390x844-m3-multi-file.png` })

  // Clicking starts a browser download; page.route doesn't see those requests.
  const download = page.waitForEvent('download')
  await zip.click()
  const url = (await download).url()
  expect(url).toMatch(/\/api\/jobs\/job1\/zip\?ticket=tk-job1-\d+$/)
  expect(url).not.toContain('token=')
})

test('multi-file history item lists its files after the availability check', async ({ page }) => {
  await mockApi(page, { media: PHOTO_POST, result: { files: ['a.jpg', 'b.jpg'], sizes: [10, 20] } })
  await resolve(page, PHOTO_POST.url, PHOTO_POST.title)
  await page.getByRole('button', { name: 'Download' }).click()
  await expect(page.getByRole('button', { name: 'Save all as ZIP' })).toBeVisible()

  await page.getByRole('link', { name: 'History' }).click()
  await page.getByRole('button', { name: 'Save', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Save all as ZIP' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save b.jpg' })).toBeVisible()
})

test.describe('login required', () => {
  test('at resolve time points to Settings', async ({ page }) => {
    await mockApi(page, {
      resolveError: { status: 422, detail: { message: 'Login required', code: 'login_required' } },
    })
    await page.goto('/')
    await page.getByLabel('Link').fill('https://www.instagram.com/stories/x/1')
    await page.getByRole('button', { name: 'Check link' }).click()
    await expect(page.getByRole('alert')).toContainText('Upload cookies for Instagram in Settings')
    await page.getByRole('alert').getByRole('link', { name: 'Settings' }).click()
    await expect(page).toHaveURL(/\/settings$/)
    await expect(page.getByRole('heading', { name: 'Logins (cookies)' })).toBeVisible()
  })

  test('a failed job points to Settings', async ({ page }) => {
    await mockApi(page, {
      media: PHOTO_POST,
      result: { error: 'Login required', error_code: 'login_required' },
    })
    await resolve(page, PHOTO_POST.url, PHOTO_POST.title)
    await page.getByRole('button', { name: 'Download' }).click()
    await expect(page.getByRole('status')).toContainText('Upload cookies for Instagram')
    await page.getByRole('link', { name: 'Settings', exact: true }).first().click()
    await expect(page).toHaveURL(/\/settings$/)
  })
})

test('server busy shows the busy message', async ({ page }) => {
  await mockApi(page, { resolveError: { status: 503, detail: 'Server busy' } })
  await page.goto('/')
  await page.getByLabel('Link').fill(MEDIA.url)
  await page.getByRole('button', { name: 'Check link' }).click()
  await expect(page.getByRole('alert')).toHaveText('The server is busy. Try again in a moment.')
})

test.describe('cookies in Settings', () => {
  test('uploads, replaces and deletes a login', async ({ page }) => {
    const mock = await mockApi(page, { cookieDropped: 3 })
    await page.goto('/settings')
    await expect(page.getByText('No login')).toHaveCount(3)

    await page.getByTestId('cookie-file-instagram').setInputFiles({
      name: 'cookies.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t0\tsid\tx\n'),
    })
    await expect(page.getByText('Logged in · 7 cookies')).toBeVisible()
    await expect(page.getByText('3 cookies for other sites were ignored.')).toBeVisible()
    expect(mock.cookiePuts).toHaveLength(1)
    expect(mock.cookiePuts[0]).toMatchObject({ platform: 'instagram', contentType: 'text/plain' })
    await expect(page.getByRole('button', { name: 'Replace: Instagram' })).toBeVisible()
    await page.getByRole('heading', { name: 'Logins (cookies)' }).evaluate((el) => el.scrollIntoView({ block: 'start' }))
    await page.screenshot({ path: `${SHOTS}/390x844-m3-cookies.png` })

    page.once('dialog', (dialog) => {
      expect(dialog.message()).toBe('Delete the Instagram login from the server?')
      void dialog.accept()
    })
    await page.getByRole('button', { name: 'Delete: Instagram' }).click()
    await expect(page.getByText('No login')).toHaveCount(3)
  })

  test('shows the server error for an invalid file', async ({ page }) => {
    await mockApi(page)
    await page.goto('/settings')
    await page.getByTestId('cookie-file-tiktok').setInputFiles({
      name: 'cookies.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from('hello'),
    })
    await expect(page.getByRole('alert')).toHaveText('Not a valid cookies.txt')
  })

  test('rejects a file over 256 KB locally', async ({ page }) => {
    const mock = await mockApi(page)
    await page.goto('/settings')
    await page.getByTestId('cookie-file-tiktok').setInputFiles({
      name: 'cookies.txt',
      mimeType: 'text/plain',
      buffer: Buffer.alloc(256 * 1024 + 1, 'x'),
    })
    await expect(page.getByRole('alert')).toHaveText('That file is too large for a cookies.txt.')
    expect(mock.cookiePuts).toHaveLength(0)
  })
})
