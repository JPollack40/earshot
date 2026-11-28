import { continueHere, dismissOffer, holderLabel, useHandoffOffer } from "../handoff";
import { formatClock } from "../util";

// Phase 20 (C5): "Playing on Galaxy Z Fold6: <episode> · 12:34. Continue here".
export function HandoffBanner() {
  const o = useHandoffOffer();
  if (!o) return null;
  return (
    <div className="config-banner handoff-banner" role="status">
      <span>
        {o.holder.playing ? "Playing on " : "Paused on "}
        {holderLabel(o.holder)} · {formatClock(o.holder.position_ms)}
        <br />
        <strong>{o.episode.title}</strong>
      </span>
      <button onClick={() => continueHere(o)}>Continue here</button>
      <button aria-label="Dismiss" onClick={dismissOffer}>
        ×
      </button>
    </div>
  );
}
