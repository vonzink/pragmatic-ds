import { MutableRefObject, useEffect, useRef } from "react";

/**
 * The modal contract every dialog in this feature is held to, in one place.
 *
 * A dialog that renders `role="dialog"` and stops there is a dialog only to a validator. For
 * anyone driving the page from the keyboard it is a decoration: focus is still on the control they
 * pressed, Tab walks them straight out into the page they were asked to stop reading, and
 * dismissing it drops focus onto the body, which for a screen-reader user means starting the whole
 * page again. So this hook does the three things that make it real — move focus in, keep it in,
 * and put it back — and nothing else. No dependency is added for it; a focus trap is thirty lines
 * and the behaviour above is the whole of what this feature needs.
 *
 * **Where focus lands is the caller's decision, because it is a safety decision.** A dialog whose
 * primary control is destructive must not open with that control focused, or under the cursor of
 * an operator who reflexively presses Enter or Space. Such a dialog passes `"dialog"` and focus
 * lands on the dialog element itself: a screen reader announces its name and its text, and no
 * control is armed. A data-entry dialog passes `"first-field"` and lands on the field the user
 * came to fill in.
 *
 * **Escape is wired only where the dialog already offers a way out.** `onEscape` is nullable and
 * callers pass null whenever their own dismiss control is absent or disabled — while a request is
 * in flight, for instance, where dismissing would drop the idempotency key the attempt is
 * retrying under. Escape is a shortcut to a visible affordance, never a second, hidden one that
 * can do more than the buttons on screen: a keyboard user must not be able to discard something a
 * mouse user cannot.
 */

const FOCUSABLE = [
  "a[href]",
  "button:not([disabled])",
  'input:not([disabled]):not([type="hidden"])',
  "select:not([disabled])",
  "textarea:not([disabled])",
  '[tabindex]:not([tabindex="-1"])',
].join(",");

export interface ModalDialogOptions {
  /** Whether the dialog is on screen. Focus is captured on the false→true edge. */
  active: boolean;
  /**
   * Dismisses the dialog, or null where the dialog offers no dismissal right now.
   *
   * Read through a ref on every keystroke, so a caller may pass a fresh closure per render.
   */
  onEscape: (() => void) | null;
  /** Where focus lands on open. Defaults to the first field. */
  initialFocus?: "first-field" | "dialog";
}

/**
 * Wires the modal contract to a container element.
 *
 * The returned ref goes on the element carrying `role="dialog"`. That element needs
 * `tabIndex={-1}` so it can hold focus itself — it is where focus goes when the dialog has no
 * focusable content, and where `"dialog"` initial focus lands.
 */
export function useModalDialog<T extends HTMLElement>({
  active, onEscape, initialFocus = "first-field",
}: ModalDialogOptions): MutableRefObject<T | null> {
  const container = useRef<T | null>(null);
  const escape = useRef(onEscape);
  escape.current = onEscape;

  // Remember who opened this, move focus in, and give it back on the way out. The restore runs
  // from the cleanup so it covers both ways a dialog ends: closed by its own controls, and
  // unmounted by the screen around it.
  useEffect(() => {
    if (!active) return undefined;
    const opener = document.activeElement;
    const invoker = opener instanceof HTMLElement ? opener : null;
    const node = container.current;
    if (node) {
      const target = initialFocus === "dialog" ? node : (focusableWithin(node)[0] ?? node);
      target.focus();
    }
    return () => {
      // Only if it is still on the page: a dialog that replaced its own trigger has nothing to
      // give focus back to, and focusing a detached node would silently drop it on the body.
      if (invoker && invoker.isConnected) invoker.focus();
    };
  }, [active, initialFocus]);

  useEffect(() => {
    if (!active) return undefined;

    function onKeyDown(event: KeyboardEvent) {
      const node = container.current;
      if (!node) return;

      if (event.key === "Escape") {
        const dismiss = escape.current;
        if (!dismiss) return;
        event.preventDefault();
        event.stopPropagation();
        dismiss();
        return;
      }

      if (event.key !== "Tab") return;
      const focusable = focusableWithin(node);
      if (focusable.length === 0) {
        event.preventDefault();
        node.focus();
        return;
      }
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const current = document.activeElement;
      const outside = !(current instanceof Node) || !node.contains(current);
      if (event.shiftKey) {
        if (outside || current === first) {
          event.preventDefault();
          last.focus();
        }
        return;
      }
      if (outside || current === last) {
        event.preventDefault();
        first.focus();
      }
    }

    document.addEventListener("keydown", onKeyDown, true);
    return () => document.removeEventListener("keydown", onKeyDown, true);
  }, [active]);

  return container;
}

/**
 * The container's focusable controls, in tab order.
 *
 * Deliberately no visibility test: layout is the one thing jsdom does not have, so a check on
 * `offsetParent` would behave differently under test than in a browser, which is worse than not
 * checking at all. Explicitly hidden subtrees are excluded, which is what this feature's dialogs
 * actually use.
 */
function focusableWithin(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE))
    .filter((node) => node.closest("[aria-hidden='true'],[hidden]") === null);
}
