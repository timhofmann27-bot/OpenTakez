import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { updateSettings } from '../lib/settings'
import { OptionPicker } from './OptionPicker'

describe('OptionPicker', () => {
  it('groups options and selects by clicking the label', async () => {
    updateSettings({ lang: 'en' })
    const onChange = vi.fn()
    render(
      <OptionPicker
        options={['video-best', 'video-720', 'audio-mp3']}
        value="video-best"
        onChange={onChange}
      />,
    )

    expect(screen.getByRole('radio', { name: 'Best' })).toBeChecked()
    expect(screen.getByText('Audio only')).toBeInTheDocument()

    await userEvent.click(screen.getByText('MP3'))

    expect(onChange).toHaveBeenCalledWith('audio-mp3')
  })

  it('hides the video group for audio-only sources', () => {
    updateSettings({ lang: 'en' })
    render(<OptionPicker options={['audio-mp3', 'audio-m4a']} value="audio-mp3" onChange={() => {}} />)

    expect(screen.queryByText('Video')).not.toBeInTheDocument()
    expect(screen.getAllByRole('radio')).toHaveLength(2)
  })
})
