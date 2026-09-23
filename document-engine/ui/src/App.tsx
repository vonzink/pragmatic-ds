import PackageView from './features/review/PackageView.tsx';
import { API_BASE } from './lib/api/config.ts';

/**
 * The whole application is the review workspace. `PackageView` owns the
 * upload, the polling and the two panes; this file exists only to mount it and
 * to say, in the corner, which API it is talking to — which is the first
 * question anyone debugging a blank pane asks.
 *
 * `?packageId=` skips the upload step, so a reviewer can be sent a link to a
 * package someone else uploaded.
 */
export default function App() {
  const parameters = new URLSearchParams(window.location.search);
  const packageId = parameters.get('packageId') ?? undefined;
  const jobId = parameters.get('jobId') ?? undefined;

  return (
    <div className="flex h-full flex-col">
      <PackageView initialPackageId={packageId} initialJobId={jobId} />
      <footer className="shrink-0 border-t border-slate-200 bg-white px-4 py-1 text-right">
        <span className="font-mono text-[10px] text-slate-400">API {API_BASE}</span>
      </footer>
    </div>
  );
}
