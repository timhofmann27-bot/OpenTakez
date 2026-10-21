import { render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { updateSettings } from '../lib/settings'
import type { MediaInfo } from '../lib/types'
import { PreviewCard } from './PreviewCard'

const image: MediaInfo = {
  url: 'https://www.instagram.com/p/x/',
  platform: 'instagram',
  kind: 'image',
  title: 'Holiday',
  options: ['image-original'],
  item_count: 10,
  total_items: 25,
}

beforeEach(() => updateSettings({ lang: 'en' }))

describe('PreviewCard for photos', () => {
  it('shows item count, truncation notice and the Original option under Photos', () => {
    render(<PreviewCard info={image} onDownload={async () => {}} />)

    expect(screen.getByText('10 items')).toBeInTheDocument()
    expect(screen.getByText('Only the first 10 of 25 items will be downloaded.')).toBeInTheDocument()
    expect(screen.getByText('Photos')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Original' })).toBeChecked()
  })

  it('has no notice when nothing was cut and no count for a single photo', () => {
    render(
      <PreviewCard info={{ ...image, item_count: null, total_items: null }} onDownload={async () => {}} />,
    )

    expect(screen.queryByText(/items/)).not.toBeInTheDocument()
  })

  it('has no truncation notice when all items are included', () => {
    render(<PreviewCard info={{ ...image, item_count: 5, total_items: 5 }} onDownload={async () => {}} />)

    expect(screen.getByText('5 items')).toBeInTheDocument()
    expect(screen.queryByText(/Only the first/)).not.toBeInTheDocument()
  })
})
