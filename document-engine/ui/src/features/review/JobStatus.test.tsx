import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import JobStatus from './JobStatus.tsx';
import { COMPLETED_JOB, FAILED_JOB } from '../../test/fixtures.ts';

function stageRow(stage: string): HTMLElement {
  const row = document.querySelector<HTMLElement>(`[data-testid="stage-row"][data-stage="${stage}"]`);
  if (!row) throw new Error(`no stage row for ${stage}`);
  return row;
}

describe('JobStatus', () => {
  it('renders nothing before a job exists', () => {
    const { container } = render(<JobStatus job={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('collapses the trail once the work is done, but keeps every stage reachable', async () => {
    // The trail is a progress indicator. Once a job settles cleanly it is history, and it used to
    // sit on top of the extracted data — pushing the fields off-screen entirely (found by the
    // browser e2e). Collapsed by default now; the detail must still be one click away.
    render(<JobStatus job={COMPLETED_JOB} />);

    expect(screen.getByTestId('job-status')).toHaveAttribute('data-job-status', 'COMPLETED');
    expect(screen.queryAllByTestId('stage-row')).toHaveLength(0);

    await userEvent.click(screen.getByTestId('job-trail-toggle'));

    expect(screen.getAllByTestId('stage-row')).toHaveLength(COMPLETED_JOB.stages.length);
  });

  it('leaves the trail OPEN when a stage failed — that is when the detail earns its space', () => {
    render(<JobStatus job={FAILED_JOB} />);

    expect(screen.getAllByTestId('stage-row')).toHaveLength(FAILED_JOB.stages.length);
  });

  it('surfaces a failed stage’s error code — acceptance criterion 4', () => {
    render(<JobStatus job={FAILED_JOB} />);

    const row = stageRow('OCR_PROCESSING');
    expect(row.dataset.status).toBe('FAILED');
    const code = within(row).getByTestId('stage-error-code');
    expect(code).toHaveAttribute('data-error-code', 'WORKER_TIMEOUT');
    // Legible to a human, not only to a selector.
    expect(code).toHaveTextContent('WORKER_TIMEOUT');
  });

  it('names the failure in an alert, so it is not just a row in a list', () => {
    render(<JobStatus job={FAILED_JOB} />);

    const summary = screen.getByTestId('job-failure-summary');
    expect(summary).toHaveTextContent('OCR_PROCESSING');
    expect(summary).toHaveTextContent('WORKER_TIMEOUT');
    // The consequence matters as much as the code.
    expect(summary).toHaveTextContent(/may be incomplete/i);
  });

  it('shows why a skipped stage was skipped', async () => {
    render(<JobStatus job={COMPLETED_JOB} />);
    await userEvent.click(screen.getByTestId('job-trail-toggle'));

    expect(within(stageRow('OCR_PROCESSING')).getByTestId('stage-skip-reason')).toHaveTextContent(
      'native text layer present',
    );
  });

  it('shows the attempt count only once a stage has been retried', () => {
    render(<JobStatus job={FAILED_JOB} />);
    expect(stageRow('OCR_PROCESSING')).toHaveTextContent('attempt 3');

    expect(stageRow('RENDERING')).not.toHaveTextContent('attempt');
  });

  it('offers a retry only when something actually failed', async () => {
    const onResume = vi.fn();
    const { rerender } = render(<JobStatus job={COMPLETED_JOB} onResume={onResume} />);
    expect(screen.queryByTestId('resume-job')).not.toBeInTheDocument();

    rerender(<JobStatus job={FAILED_JOB} onResume={onResume} />);
    await userEvent.click(screen.getByTestId('resume-job'));
    expect(onResume).toHaveBeenCalledTimes(1);
  });
});
