import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import FieldDecisionControls from './FieldDecisionControls.tsx';
import { MISSING_FIELD, NET_PAY } from '../../test/fixtures.ts';

describe('FieldDecisionControls', () => {
  it('confirms with one click', async () => {
    const onDecide = vi.fn().mockResolvedValue(undefined);
    render(
      <FieldDecisionControls field={NET_PAY} documentPageCount={2} currentDocumentPageIndex={0} onDecide={onDecide} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(onDecide).toHaveBeenCalledWith(NET_PAY.id, { action: 'CONFIRM' });
  });

  it('rejects with one click', async () => {
    const onDecide = vi.fn().mockResolvedValue(undefined);
    render(
      <FieldDecisionControls field={NET_PAY} documentPageCount={2} currentDocumentPageIndex={0} onDecide={onDecide} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Reject' }));

    expect(onDecide).toHaveBeenCalledWith(NET_PAY.id, { action: 'REJECT' });
  });

  it('correct opens a dialog whose page defaults to the page in view and sends value + page', async () => {
    const onDecide = vi.fn().mockResolvedValue(undefined);
    render(
      <FieldDecisionControls field={NET_PAY} documentPageCount={3} currentDocumentPageIndex={1} onDecide={onDecide} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Correct' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText('Page')).toHaveValue('1');

    await userEvent.type(screen.getByLabelText('Value as printed'), '4,760.69');
    await userEvent.selectOptions(screen.getByLabelText('Page'), '2');
    await userEvent.click(screen.getByRole('button', { name: 'Save correction' }));

    expect(onDecide).toHaveBeenCalledWith(NET_PAY.id, { action: 'CORRECT', value: '4,760.69', pageIndex: 2 });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a missing field offers Correct and Reject but not Confirm', () => {
    render(
      <FieldDecisionControls field={MISSING_FIELD} documentPageCount={1} currentDocumentPageIndex={0} onDecide={vi.fn()} />,
    );

    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Correct' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument();
  });

  it('save is disabled until a value is typed', async () => {
    render(
      <FieldDecisionControls field={NET_PAY} documentPageCount={1} currentDocumentPageIndex={0} onDecide={vi.fn()} />,
    );

    await userEvent.click(screen.getByRole('button', { name: 'Correct' }));

    expect(screen.getByRole('button', { name: 'Save correction' })).toBeDisabled();
  });
});
