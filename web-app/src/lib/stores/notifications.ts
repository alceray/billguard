import { writable } from "svelte/store";
import { browser } from "$app/environment";

export type NotificationKind = "success" | "error" | "info";

export interface Notification {
  id: number;
  kind: NotificationKind;
  message: string;
}

const MIN_DURATION_MS = 5_000;
const MAX_DURATION_MS = 10_000;
const MAX_VISIBLE = 4;

const store = writable<Notification[]>([]);
const timers = new Map<number, ReturnType<typeof setTimeout>>();
let nextId = 0;

function dismiss(id: number) {
  const timer = timers.get(id);
  if (timer) {
    clearTimeout(timer);
    timers.delete(id);
  }
  store.update((items) => items.filter((item) => item.id !== id));
}

function show(
  message: string,
  kind: NotificationKind = "info",
  durationMs = 7_000,
) {
  const id = ++nextId;
  const duration = Math.min(
    MAX_DURATION_MS,
    Math.max(MIN_DURATION_MS, durationMs),
  );

  store.update((items) => {
    const duplicateIds = items
      .filter((item) => item.message === message && item.kind === kind)
      .map((item) => item.id);
    for (const duplicateId of duplicateIds) {
      const timer = timers.get(duplicateId);
      if (timer) clearTimeout(timer);
      timers.delete(duplicateId);
    }

    const next = [
      ...items.filter((item) => !duplicateIds.includes(item.id)),
      { id, kind, message },
    ];
    const visible = next.slice(-MAX_VISIBLE);
    const visibleIds = new Set(visible.map((item) => item.id));
    for (const item of next) {
      if (!visibleIds.has(item.id)) {
        const timer = timers.get(item.id);
        if (timer) clearTimeout(timer);
        timers.delete(item.id);
      }
    }
    return visible;
  });

  if (browser) {
    timers.set(
      id,
      setTimeout(() => dismiss(id), duration),
    );
  }
  return id;
}

export const notifications = {
  subscribe: store.subscribe,
  dismiss,
};

export const notify = {
  success: (message: string, durationMs = 6_000) =>
    show(message, "success", durationMs),
  error: (message: string, durationMs = 9_000) =>
    show(message, "error", durationMs),
  info: (message: string, durationMs = 7_000) =>
    show(message, "info", durationMs),
};
