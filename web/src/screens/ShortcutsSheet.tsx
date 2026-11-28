import { SHORTCUTS } from "../shortcuts";

// Phase 22: the "?" sheet.
export function ShortcutsSheet({ onClose }: { onClose: () => void }) {
  return (
    <div className="scrim" role="presentation" onClick={onClose}>
      <div className="sheet shortcuts" role="dialog" aria-label="Keyboard shortcuts" onClick={(e) => e.stopPropagation()}>
        <div className="sheet-title">Keyboard shortcuts</div>
        <table>
          <tbody>
            {SHORTCUTS.map((s) => (
              <tr key={s.action}>
                <td>
                  {s.keys.map((k) => (
                    <kbd key={k}>{k}</kbd>
                  ))}
                </td>
                <td>{s.label}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <button className="text" onClick={onClose}>
          Close
        </button>
      </div>
    </div>
  );
}
